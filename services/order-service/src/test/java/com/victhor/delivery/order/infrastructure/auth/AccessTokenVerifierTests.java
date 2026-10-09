package com.victhor.delivery.order.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
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
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JwtDecoder DECODER = AccessTokenVerifier.fromPublicKey(TestAccessTokens.PUBLIC_KEY, CLOCK);

    @Test
    void acceptsATokenSignedByTheUserServiceForItsSubject() {
        UUID userId = UUID.randomUUID();
        String token = signed(NOW.minusSeconds(60), claims -> claims.subject(userId.toString()));

        assertThat(DECODER.decode(token).getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void fetchesThePublicKeysFromTheUserServiceKeySetOnceAndCachesThem() throws Exception {
        var requests = new AtomicInteger();
        byte[] keySet = new JWKSet(TestAccessTokens.rsaKey(TestAccessTokens.PRIVATE_KEY).toPublicJWK()).toString()
                .getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keySet.length);
            exchange.getResponseBody().write(keySet);
            exchange.close();
        });
        server.start();
        try {
            var decoder = AccessTokenVerifier.fromJwkSetUri(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json", CLOCK);
            assertThat(decoder.decode(signed(NOW, claims -> { })).getSubject()).isNotBlank();
            assertThat(decoder.decode(signed(NOW, claims -> { })).getSubject()).isNotBlank();
            assertThatThrownBy(() -> decoder.decode(TestAccessTokens.issue(TestAccessTokens.FOREIGN_PRIVATE_KEY, NOW,
                    claims -> { }))).isInstanceOf(JwtException.class);
            assertThat(requests.get()).isBetween(1, 2);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void rejectsTokensThatTheUserServiceWouldNotHaveIssued(String description, String token) {
        assertThatThrownBy(() -> DECODER.decode(token)).isInstanceOf(JwtException.class);
    }

    static Stream<Arguments> rejectedTokens() {
        return Stream.of(
                Arguments.of("malformed", "not-a-token"),
                Arguments.of("foreign key", TestAccessTokens.issue(TestAccessTokens.FOREIGN_PRIVATE_KEY, NOW, claims -> { })),
                Arguments.of("unsigned", "eyJhbGciOiJub25lIn0." + signed(NOW, claims -> { }).split("\\.")[1] + "."),
                Arguments.of("expired", signed(NOW.minusSeconds(1000), claims -> { })),
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
    void refusesToStartWithoutAUsablePublicKey(String publicKey) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccessTokenVerifier.fromPublicKey(publicKey, CLOCK));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "user-service/.well-known/jwks.json", "file:///etc/jwks.json" })
    void refusesToStartWithoutAnHttpKeySetUrl(String jwkSetUri) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccessTokenVerifier.fromJwkSetUri(jwkSetUri, CLOCK));
    }

    private static String signed(Instant issuedAt, Consumer<JwtClaimsSet.Builder> customizer) {
        return TestAccessTokens.issue(TestAccessTokens.PRIVATE_KEY, issuedAt, customizer);
    }
}
