package com.victhor.delivery.user.api;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.user.infrastructure.auth.JwtAccessTokens;

/** Publishes the token verification keys for the other services; private key material never leaves this service. */
@RestController
public class PublicKeysController {

    private final JwtAccessTokens tokens;

    public PublicKeysController(JwtAccessTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/jwk-set+json")
    public ResponseEntity<Map<String, Object>> publicKeys() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(tokens.publicKeys());
    }
}
