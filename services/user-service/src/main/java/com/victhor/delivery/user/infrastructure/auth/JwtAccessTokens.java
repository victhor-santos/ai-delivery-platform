package com.victhor.delivery.user.infrastructure.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.victhor.delivery.user.application.AccessToken;
import com.victhor.delivery.user.application.AccessTokenIssuer;

public class JwtAccessTokens implements AccessTokenIssuer {

    public static final String ISSUER = "https://delivery-order-system.local";
    public static final String AUDIENCE = "delivery-order-system";
    public static final Duration LIFETIME = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtAccessTokens(String encodedSecret, Clock clock) {
        this.clock = clock;
        SecretKey key = signingKey(encodedSecret);
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(ISSUER), this::validateClaims));
        decoder = nimbus;
    }

    @Override
    public AccessToken issue(UUID userId) {
        var now = clock.instant();
        var expiresAt = now.plus(LIFETIME);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(userId.toString())
                .audience(List.of(AUDIENCE)).issuedAt(now).notBefore(now).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt, LIFETIME.toSeconds());
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(LIFETIME))
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
