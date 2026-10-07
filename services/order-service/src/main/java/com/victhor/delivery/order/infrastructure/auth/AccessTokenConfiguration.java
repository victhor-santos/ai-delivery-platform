package com.victhor.delivery.order.infrastructure.auth;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
public class AccessTokenConfiguration {

    @Bean
    JwtDecoder jwtDecoder(@Value("${order.auth.secret}") String secret, Clock clock) {
        return AccessTokenVerifier.decoder(secret, clock);
    }
}
