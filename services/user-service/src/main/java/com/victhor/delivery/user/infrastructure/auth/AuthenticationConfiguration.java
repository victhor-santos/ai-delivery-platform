package com.victhor.delivery.user.infrastructure.auth;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.victhor.delivery.user.application.AuthAccountRepository;
import com.victhor.delivery.user.application.AuthenticationService;
import com.victhor.delivery.user.application.PasswordHasher;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {

    @Bean
    Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    JwtAccessTokens accessTokens(@Value("${user.auth.secret}") String secret, Clock clock) {
        return new JwtAccessTokens(secret, clock);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtAccessTokens tokens) {
        return tokens.decoder();
    }

    @Bean
    AuthenticationService authentication(AuthAccountRepository accounts, PasswordHasher passwords, JwtAccessTokens tokens) {
        return new AuthenticationService(accounts, passwords, tokens);
    }
}
