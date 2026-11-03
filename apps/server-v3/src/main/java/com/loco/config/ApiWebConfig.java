package com.loco.config;

import java.lang.reflect.AnnotatedElement;

import com.loco.security.AuthenticatedApi;
import com.loco.security.PublicApi;
import com.loco.security.JwtPrincipal;
import com.loco.users.UserRepository;
import com.loco.web.ApiController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
public class ApiWebConfig implements WebMvcConfigurer {
    private final UserRepository users;
    private final com.loco.security.RateLimitInterceptor rateLimit;
    public ApiWebConfig(UserRepository users, com.loco.security.RateLimitInterceptor rateLimit) {
        this.users = users; this.rateLimit = rateLimit;
    }
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", type -> AnnotatedElementUtils.hasAnnotation(type, ApiController.class));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit).order(-100);
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod method && isApi(method) && requiresAuthentication(method)) {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (!new AuthenticationTrustResolverImpl().isAuthenticated(authentication)) {
                        throw new AuthenticationCredentialsNotFoundException("Unauthorized");
                    }
                    if (!(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
                        throw new AuthenticationCredentialsNotFoundException("Unauthorized");
                    }
                    var member = users.findAuthenticationById(principal.userId())
                            .filter(user -> user.getDeletedAt() == null)
                            .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unauthorized"));
                    SecurityContextHolder.getContext().setAuthentication(
                            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                                    new JwtPrincipal(member.getId(), member.getEmail()), null, authentication.getAuthorities()));
                }
                return true;
            }
        });
    }

    @Bean
    SmartInitializingSingleton verifyApiDeclarations(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        return () -> mappings.getHandlerMethods().values().forEach(ApiWebConfig::verifyDeclaration);
    }

    public static void verifyDeclaration(HandlerMethod method) {
        Class<?> type = method.getBeanType();
        if (!type.getPackageName().startsWith("com.loco")
                || !AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
            return;
        }
        if (!isApi(method)) {
            throw new IllegalStateException("Application controllers must declare @ApiController: " + method);
        }
        requiresAuthentication(method);
    }

    private static boolean isApi(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ApiController.class);
    }

    private static boolean requiresAuthentication(HandlerMethod method) {
        AnnotatedElement declaration = method.getMethod();
        if (!AnnotatedElementUtils.hasAnnotation(declaration, PublicApi.class)
                && !AnnotatedElementUtils.hasAnnotation(declaration, AuthenticatedApi.class)) {
            declaration = method.getBeanType();
        }
        boolean publicApi = AnnotatedElementUtils.hasAnnotation(declaration, PublicApi.class);
        boolean authenticatedApi = AnnotatedElementUtils.hasAnnotation(declaration, AuthenticatedApi.class);
        if (publicApi == authenticatedApi) {
            throw new IllegalStateException("Declare exactly one of @PublicApi / @AuthenticatedApi: " + method);
        }
        return authenticatedApi;
    }
}
