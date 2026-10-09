package com.victhor.delivery.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.infrastructure.auth.JwtAccessTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
@ActiveProfiles("test")
class AuthenticationApiTests {

    private static final String PASSWORD = "correct-password-123";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Value("${user.auth.private-key}")
    private String signingKey;

    @Test
    void registersHashesLogsInAndResolvesTheTokenSubjectWithoutAcceptingClientIdentity() throws Exception {
        String email = email();
        var registered = post("/register", Map.of("name", " Cliente ", "email", " " + email.toUpperCase(java.util.Locale.ROOT) + " ",
                "password", PASSWORD, "id", UUID.randomUUID(), "role", "ADMIN"));
        assertThat(registered.statusCode()).isEqualTo(201);
        JsonNode profile = body(registered);
        assertThat(profile.size()).isEqualTo(3);
        assertThat(profile.path("email").asString()).isEqualTo(email);
        assertThat(registered.headers().firstValue("Location")).contains("/api/users/" + profile.path("id").asString());
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_credentials WHERE user_id = ?", String.class,
                UUID.fromString(profile.path("id").asString()));
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        var login = post("/login", Map.of("email", email, "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(login.headers().firstValue("Set-Cookie")).isEmpty();
        JsonNode token = body(login);
        assertThat(token.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(token.path("expiresIn").asLong()).isEqualTo(900);
        var me = getMe(token.path("accessToken").asString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me).path("role").asString()).isEqualTo("CUSTOMER");
        assertThat(((tools.jackson.databind.node.ObjectNode) body(me)).without("role")).isEqualTo(profile);
        assertThat(me.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void wrongPasswordUnknownEmailAndLegacyProfileReturnTheSameLoginProblem() throws Exception {
        String registeredEmail = email();
        assertThat(post("/register", Map.of("name", "Cliente", "email", registeredEmail, "password", PASSWORD)).statusCode())
                .isEqualTo(201);
        String legacyEmail = email();
        UUID legacyId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id,name,email) VALUES (?,?,?)", legacyId, "Legado", legacyEmail);
        var wrong = post("/login", Map.of("email", registeredEmail, "password", "incorrect-password"));
        var absent = post("/login", Map.of("email", email(), "password", PASSWORD));
        var legacy = post("/login", Map.of("email", legacyEmail, "password", PASSWORD));
        assertProblem(wrong, 401);
        assertProblem(absent, 401);
        assertProblem(legacy, 401);
        assertThat(body(wrong).path("detail")).isEqualTo(body(absent).path("detail")).isEqualTo(body(legacy).path("detail"));
        assertThat(post("/register", Map.of("name", "Takeover", "email", legacyEmail, "password", PASSWORD)).statusCode())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE user_id = ?", Integer.class, legacyId)).isZero();
    }

    @Test
    void duplicateRegistrationDoesNotReplaceTheOriginalPassword() throws Exception {
        String email = email();
        assertThat(post("/register", Map.of("name", "Cliente", "email", email, "password", PASSWORD)).statusCode()).isEqualTo(201);
        assertProblem(post("/register", Map.of("name", "Other", "email", email.toUpperCase(java.util.Locale.ROOT),
                "password", "replacement-password")), 409);
        assertThat(post("/login", Map.of("email", email, "password", PASSWORD)).statusCode()).isEqualTo(200);
        assertProblem(post("/login", Map.of("email", email, "password", "replacement-password")), 401);
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void invalidRegistrationDoesNotCreateAProfileOrLeakThePassword(String password) throws Exception {
        String email = email();
        var response = post("/register", Map.of("name", "Cliente", "email", email, "password", password));
        assertProblem(response, 400);
        if (!password.isBlank()) assertThat(response.body()).doesNotContain(password);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    static Stream<String> invalidPasswords() {
        return Stream.of("short", " ".repeat(12), "x".repeat(73), "é".repeat(37), "😀".repeat(11));
    }

    @Test
    void rejectsMalformedRegistrationAndDoesNotSerializeSensitiveDtos() throws Exception {
        assertProblem(postJson("/register", "{\"name\":\"Cliente\",\"email\":\"a@example.test\",\"password\":true}"), 400);
        assertProblem(postJson("/login", "{}"), 400);
        assertThat(new com.victhor.delivery.user.api.LoginRequest("a@example.test", PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(new com.victhor.delivery.user.api.RegisterAccountRequest("Cliente", "a@example.test", PASSWORD).toString())
                .doesNotContain(PASSWORD);
    }

    @Test
    void missingMalformedExpiredAndTamperedTokensAreUnauthorizedAndCannotUseClientUserIds() throws Exception {
        assertProblem(getMe(null), 401);
        assertProblem(getMe("not-a-token"), 401);
        String email = email();
        var profile = body(post("/register", Map.of("name", "Cliente", "email", email, "password", PASSWORD)));
        UUID id = UUID.fromString(profile.path("id").asString());
        String expired = new JwtAccessTokens(signingKey, Clock.fixed(Instant.now().minusSeconds(1000), ZoneOffset.UTC))
                .issue(id, Role.CUSTOMER).value();
        assertProblem(getMe(expired), 401);
        String token = body(post("/login", Map.of("email", email, "password", PASSWORD))).path("accessToken").asString();
        String[] parts = token.split("\\.");
        assertProblem(getMe(parts[0] + "." + parts[1] + ".AAAA"), 401);
        assertThat(getMe(token).statusCode()).isEqualTo(200);
    }

    @Test
    void locksAnAccountAfterFiveAttemptsEvenForTheRightPasswordWithoutAffectingOthers() throws Exception {
        String email = email();
        post("/register", Map.of("name", "Cliente", "email", email, "password", PASSWORD));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertProblem(post("/login", Map.of("email", email, "password", "wrong-password-" + attempt)), 401);
        }
        var locked = post("/login", Map.of("email", email.toUpperCase(java.util.Locale.ROOT), "password", PASSWORD));
        assertProblem(locked, 429);
        assertThat(locked.headers().firstValue("Retry-After").map(Long::parseLong)).hasValueSatisfying(
                seconds -> assertThat(seconds).isBetween(1L, 900L));
        assertThat(body(locked).path("detail").asString())
                .isEqualTo("Muitas tentativas de login. Tente novamente mais tarde.");

        String other = email();
        post("/register", Map.of("name", "Cliente", "email", other, "password", PASSWORD));
        assertThat(post("/login", Map.of("email", other, "password", PASSWORD)).statusCode()).isEqualTo(200);
    }

    @Test
    void publishesOnlyThePublicVerificationKeyForTheOtherServices() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/jwks.json"))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var keys = com.nimbusds.jose.jwk.JWKSet.parse(response.body()).getKeys();
        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key.isPrivate()).isFalse();
            assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
            assertThat(key.getKeyUse().identifier()).isEqualTo("sig");
        });
        assertThat(mapper.readTree(response.body()).path("keys").path(0).has("d")).isFalse();
        String email = email();
        post("/register", Map.of("name", "Cliente", "email", email, "password", PASSWORD));
        String token = body(post("/login", Map.of("email", email, "password", PASSWORD))).path("accessToken").asString();
        assertThat(com.nimbusds.jwt.SignedJWT.parse(token).getHeader().getKeyID()).isEqualTo(keys.get(0).getKeyID());
    }

    private String email() {
        return "auth-" + UUID.randomUUID() + "@example.test";
    }

    private HttpResponse<String> post(String path, Map<String, ?> value) throws Exception {
        return postJson(path, mapper.writeValueAsString(value));
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/auth" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMe(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/auth/me"))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(body(response).path("status").asInt()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("passwordHash", "stackTrace", "SQLException", PASSWORD);
        if (status == 401) assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }
}
