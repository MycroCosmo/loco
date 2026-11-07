package com.cms.core.web;

import com.cms.core.account.AdminService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@Import({
  AdminController.class,
  AdminAdvice.class,
  GovernanceController.class,
  HistoryController.class,
  FileController.class
})
public class AdminWebConfiguration {
  @Bean
  org.springframework.security.authentication.AuthenticationManager adminAuthenticationManager() {
    // Authentication happens through AdminService; never create Boot's fallback user/password.
    return authentication -> {
      throw new org.springframework.security.authentication.BadCredentialsException("Unauthorized");
    };
  }

  @Bean
  LoginAttempts adminLoginAttempts(Clock clock) {
    return new LoginAttempts(clock);
  }

  @Bean
  HttpSessionSecurityContextRepository adminSecurityContexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CookieCsrfTokenRepository adminCsrf(
      @Value("${cms.cookie.secure:${CMS_COOKIE_SECURE:true}}") boolean secure) {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookiePath("/admin");
    csrf.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(secure));
    return csrf;
  }

  @Bean
  ServletContextInitializer adminSessionCookie(
      @Value("${cms.cookie.secure:${CMS_COOKIE_SECURE:true}}") boolean secure) {
    return context -> {
      var cookie = context.getSessionCookieConfig();
      cookie.setName("CMSSESSION");
      cookie.setPath("/admin");
      cookie.setHttpOnly(true);
      cookie.setSecure(secure);
      cookie.setAttribute("SameSite", "Strict");
      context.setSessionTimeout(30);
      context.setSessionTrackingModes(java.util.Set.of(jakarta.servlet.SessionTrackingMode.COOKIE));
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain adminSecurityFilterChain(
      HttpSecurity http,
      AdminService admins,
      Clock clock,
      CookieCsrfTokenRepository csrf,
      HttpSessionSecurityContextRepository contexts)
      throws Exception {
    var handler = new CsrfTokenRequestAttributeHandler();
    handler.setCsrfRequestAttributeName(null);
    http.securityMatcher("/admin", "/admin/**")
        .securityContext(
            context -> context.securityContextRepository(contexts).requireExplicitSave(true))
        .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/admin/api/core/auth/csrf")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/admin/api/core/auth/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            AdminAdvice.write(response, 401, "Unauthorized"))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            AdminAdvice.write(response, 403, "Forbidden")))
        .addFilterAfter(new AdminSessionFilter(admins, clock), SecurityContextHolderFilter.class);
    return http.build();
  }
}
