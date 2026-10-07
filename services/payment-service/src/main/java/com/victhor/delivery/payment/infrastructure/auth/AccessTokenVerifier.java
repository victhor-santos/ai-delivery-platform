package com.victhor.delivery.payment.infrastructure.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Validates access tokens issued by the User service; this service never issues tokens itself. */
public final class AccessTokenVerifier {

    public static final String ISSUER = "https://delivery-order-system.local";
    public static final String AUDIENCE = "delivery-order-system";
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private AccessTokenVerifier() {
    }

    public static JwtDecoder decoder(String encodedSecret, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(signingKey(encodedSecret)).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER),
                jwt -> validateClaims(jwt, clock)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, Clock clock) {
        try {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(MAX_LIFETIME))
                    && !jwt.getIssuedAt().isAfter(clock.instant().plus(CLOCK_SKEW));
            if (valid) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            // A signed token still needs a usable subject and the required time claims.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }

    private static SecretKey signingKey(String encodedSecret) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("USER_AUTH_SECRET must be Base64 encoding at least 32 random bytes");
        }
        if (decoded.length < 32) {
            throw new IllegalArgumentException("USER_AUTH_SECRET must be Base64 encoding at least 32 random bytes");
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
