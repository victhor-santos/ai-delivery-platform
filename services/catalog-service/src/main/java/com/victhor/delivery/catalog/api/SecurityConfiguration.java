package com.victhor.delivery.catalog.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.victhor.delivery.catalog.infrastructure.auth.AccessTokenVerifier;
import tools.jackson.databind.ObjectMapper;

/** Anyone may browse the catalog; only operators change restaurants, locations and menus. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain catalogSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**", "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/catalog/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(response, mapper))
                        .accessDeniedHandler((request, response, exception) -> forbidden(response, mapper)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(AccessTokenVerifier.authenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(response, mapper))
                        .accessDeniedHandler((request, response, exception) -> forbidden(response, mapper)))
                .build();
    }

    private static void unauthorized(HttpServletResponse response, ObjectMapper mapper) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problem(response, mapper, HttpStatus.UNAUTHORIZED, "Credenciais de acesso inválidas ou ausentes.");
    }

    private static void forbidden(HttpServletResponse response, ObjectMapper mapper) throws IOException {
        problem(response, mapper, HttpStatus.FORBIDDEN, "Operação permitida apenas a operadores.");
    }

    private static void problem(HttpServletResponse response, ObjectMapper mapper, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
