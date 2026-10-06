package com.victhor.delivery.user.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtAccessTokensTests {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtAccessTokens tokens = new JwtAccessTokens(SECRET, CLOCK);

    @Test
    void issuesUniqueSignedTokensWithUuidSubjectAudienceAndFifteenMinuteExpiry() {
        UUID id = UUID.randomUUID();
        var first = tokens.issue(id);
        var second = tokens.issue(id);
        var decoded = tokens.decoder().decode(first.value());
        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(first.expiresIn()).isEqualTo(900);
        assertThat(decoded.getSubject()).isEqualTo(id.toString());
        assertThat(decoded.getIssuer().toString()).isEqualTo(JwtAccessTokens.ISSUER);
        assertThat(decoded.getAudience()).containsExactly(JwtAccessTokens.AUDIENCE);
        assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(decoded.getIssuedAt()).isEqualTo(NOW);
        assertThat(decoded.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(first.toString()).doesNotContain(first.value());
    }

    @Test
    void verifiesTheSignatureAndDoesNotAcceptUnsignedTokens() {
        String token = tokens.issue(UUID.randomUUID()).value();
        String[] parts = token.split("\\.");
        assertThatThrownBy(() -> tokens.decoder().decode(parts[0] + "." + parts[1] + ".AAAA"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.decoder().decode("eyJhbGciOiJub25lIn0." + parts[1] + "."))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new JwtAccessTokens(Base64.getEncoder().encodeToString(new byte[32]), CLOCK)
                .decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsSignedTokensWithMissingOrInvalidClaims(JwtClaimsSet claims) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256")));
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        assertThatThrownBy(() -> tokens.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void doesNotTrustADifferentHmacAlgorithmEvenWithTheSameKey() {
        byte[] keyBytes = new byte[64];
        java.util.Arrays.fill(keyBytes, (byte) 42);
        String secret = Base64.getEncoder().encodeToString(keyBytes);
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(keyBytes, "HmacSHA384")));
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).build(), valid().build()))
                .getTokenValue();
        assertThatThrownBy(() -> new JwtAccessTokens(secret, CLOCK).decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    static Stream<JwtClaimsSet> invalidClaims() {
        return Stream.of(
                valid().issuer("other").build(), valid().audience(List.of("other")).build(),
                valid().claims(map -> map.remove("aud")).build(),
                valid().subject("not-a-uuid").build(), valid().subject("1-1-1-1-1").build(),
                valid().claims(map -> map.remove("sub")).build(), valid().claims(map -> map.remove("exp")).build(),
                valid().claims(map -> map.remove("iat")).build(),
                valid().issuedAt(NOW.minusSeconds(900)).notBefore(NOW.minusSeconds(900)).expiresAt(NOW.minusSeconds(60)).build(),
                valid().issuedAt(NOW.plusSeconds(60)).build(), valid().notBefore(NOW.plusSeconds(60)).build(),
                valid().expiresAt(NOW.plusSeconds(901)).build());
    }

    static JwtClaimsSet.Builder valid() {
        return JwtClaimsSet.builder().issuer(JwtAccessTokens.ISSUER).audience(List.of(JwtAccessTokens.AUDIENCE))
                .subject(UUID.randomUUID().toString()).issuedAt(NOW).notBefore(NOW).expiresAt(NOW.plusSeconds(900));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-base64", "c2hvcnQ="})
    void refusesMissingMalformedOrWeakSigningKeysWithoutEchoingThem(String secret) {
        assertThatThrownBy(() -> new JwtAccessTokens(secret, CLOCK)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_AUTH_SECRET must be Base64 encoding at least 32 random bytes");
    }
}
