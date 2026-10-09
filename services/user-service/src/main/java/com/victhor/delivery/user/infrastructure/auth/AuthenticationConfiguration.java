package com.victhor.delivery.user.infrastructure.auth;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.victhor.delivery.user.application.AuthAccountRepository;
import com.victhor.delivery.user.application.AuthenticationService;
import com.victhor.delivery.user.application.PasswordHasher;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationConfiguration.class);

    @Bean
    Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    JwtAccessTokens accessTokens(@Value("${user.auth.private-key}") String privateKey, Clock clock) {
        return new JwtAccessTokens(privateKey, clock);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtAccessTokens tokens) {
        return tokens.decoder();
    }

    /** Operators are never self-registered: the only one is provisioned from the environment at startup. */
    @Bean
    ApplicationRunner operatorAccount(AuthenticationService authentication,
            @Value("${user.operator.email:}") String email, @Value("${user.operator.password:}") String password) {
        return arguments -> {
            if (email.isBlank() && password.isBlank()) {
                log.info("No operator account configured");
                return;
            }
            if (email.isBlank() || password.isBlank()) {
                throw new IllegalStateException("Set both USER_OPERATOR_EMAIL and USER_OPERATOR_PASSWORD, or neither");
            }
            authentication.ensureOperator(email, password);
            log.info("Operator account is provisioned");
        };
    }

    @Bean
    AuthenticationService authentication(AuthAccountRepository accounts, PasswordHasher passwords, JwtAccessTokens tokens) {
        return new AuthenticationService(accounts, passwords, tokens);
    }
}
