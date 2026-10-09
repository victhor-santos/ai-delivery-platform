package com.victhor.delivery.catalog.infrastructure.auth;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
public class AccessTokenConfiguration {

    /** A pinned public key wins over the key set URL; otherwise keys come from the User service. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${catalog.auth.jwk-set-uri:}") String jwkSetUri,
            @Value("${catalog.auth.public-key:}") String publicKey) {
        return publicKey.isBlank() ? AccessTokenVerifier.fromJwkSetUri(jwkSetUri, Clock.systemUTC())
                : AccessTokenVerifier.fromPublicKey(publicKey, Clock.systemUTC());
    }
}
