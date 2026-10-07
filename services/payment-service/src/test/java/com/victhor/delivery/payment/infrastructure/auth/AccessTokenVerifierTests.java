package com.victhor.delivery.payment.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class AccessTokenVerifierTests {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final JwtDecoder DECODER = AccessTokenVerifier.decoder(TestAccessTokens.SECRET,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsATokenIssuedByTheUserServiceForItsSubject() {
        UUID userId = UUID.randomUUID();
        String token = TestAccessTokens.issue(TestAccessTokens.SECRET, NOW.minusSeconds(60),
                claims -> claims.subject(userId.toString()));

        assertThat(DECODER.decode(token).getSubject()).isEqualTo(userId.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void rejectsTokensThatTheUserServiceWouldNotHaveIssued(String description, String token) {
        assertThatThrownBy(() -> DECODER.decode(token)).isInstanceOf(JwtException.class);
    }

    static Stream<Arguments> rejectedTokens() {
        String foreignSecret = "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=";
        return Stream.of(
                Arguments.of("malformed", "not-a-token"),
                Arguments.of("foreign key", TestAccessTokens.issue(foreignSecret, NOW, claims -> { })),
                Arguments.of("expired", TestAccessTokens.issue(TestAccessTokens.SECRET, NOW.minusSeconds(1000),
                        claims -> { })),
                Arguments.of("issued in the future", signed(NOW.plusSeconds(120), claims -> { })),
                Arguments.of("other issuer", signed(NOW, claims -> claims.issuer("https://other.example"))),
                Arguments.of("other audience", signed(NOW, claims -> claims.audience(List.of("other")))),
                Arguments.of("non-UUID subject", signed(NOW, claims -> claims.subject("admin"))),
                Arguments.of("lifetime above 15 minutes", signed(NOW,
                        claims -> claims.expiresAt(NOW.plusSeconds(16 * 60)))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "not base64!", "c2hvcnQ=" })
    void refusesToStartWithoutAStrongSecret(String secret) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccessTokenVerifier.decoder(secret, Clock.systemUTC()));
    }

    private static String signed(Instant issuedAt, Consumer<JwtClaimsSet.Builder> customizer) {
        return TestAccessTokens.issue(TestAccessTokens.SECRET, issuedAt, customizer);
    }
}
