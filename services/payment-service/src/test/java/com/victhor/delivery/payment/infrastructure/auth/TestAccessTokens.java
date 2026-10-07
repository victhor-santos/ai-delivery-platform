package com.victhor.delivery.payment.infrastructure.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Issues tokens shaped like the User service's, signed with the public test fixture secret. */
public final class TestAccessTokens {

    public static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private TestAccessTokens() {
    }

    public static String issue(UUID userId) {
        return issue(SECRET, Instant.now(), claims -> claims.subject(userId.toString()));
    }

    public static String issue(String secret, Instant issuedAt, Consumer<JwtClaimsSet.Builder> customizer) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        var claims = JwtClaimsSet.builder().issuer(AccessTokenVerifier.ISSUER).subject(UUID.randomUUID().toString())
                .audience(List.of(AccessTokenVerifier.AUDIENCE)).issuedAt(issuedAt).notBefore(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15))).id(UUID.randomUUID().toString());
        customizer.accept(claims);
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
