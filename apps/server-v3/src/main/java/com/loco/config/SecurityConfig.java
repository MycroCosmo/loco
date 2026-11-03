package com.loco.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loco.security.JwtAuthenticationFilter;
import com.loco.web.ApiExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(@Value("${loco.jwt.secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (secret.isBlank() || key.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes for HS256");
        }
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        // v2 jsonwebtoken의 기본 만료 허용 오차는 0초다.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                jwt -> jwt.getSubject() != null && !jwt.getSubject().isEmpty()
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                            ObjectMapper mapper) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            mapper.writeValue(response.getOutputStream(), ApiExceptionHandler.unauthorized());
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            mapper.writeValue(response.getOutputStream(), ApiExceptionHandler.forbidden());
                        }))
                .addFilterBefore(new JwtAuthenticationFilter(decoder), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
