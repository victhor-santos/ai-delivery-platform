package com.victhor.delivery.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.payment.infrastructure.auth.TestAccessTokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "PAYMENT_DB_PASSWORD=testcontainers-only")
@Testcontainers
class PaymentApiTests {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
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

    private final String token = TestAccessTokens.issue(CUSTOMER);

    @BeforeEach
    void clearAttempts() {
        jdbc.update("DELETE FROM payment_attempts");
    }

    @Test
    void approvesASimulatedPaymentAndExposesOnlyServerDecidedFields() throws Exception {
        var response = pay("checkout-0001", """
                {"orderId":"%s","amount":59.8,"method":"sim-card-approved","id":"%s","status":"DECLINED",
                 "customerId":"%s","currency":"USD"}
                """.formatted(ORDER, UUID.randomUUID(), UUID.randomUUID()), token);

        assertThat(response.statusCode()).isEqualTo(201);
        var body = json(response);
        String id = body.path("id").asString();
        assertThat(response.headers().firstValue("Location")).contains("/api/payments/" + id);
        assertThat(body.path("orderId").asString()).isEqualTo(ORDER.toString());
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo("59.80");
        assertThat(response.body()).contains("\"amount\":59.80");
        assertThat(body.path("currency").asString()).isEqualTo("BRL");
        assertThat(body.path("method").asString()).isEqualTo("sim-card-approved");
        assertThat(body.path("status").asString()).isEqualTo("APPROVED");
        assertThat(body.path("declineReason").isNull()).isTrue();
        assertThat(body.path("simulated").asBoolean()).isTrue();
        assertThat(Instant.parse(body.path("createdAt").asString())).isNotNull();
        assertThat(body.has("customerId")).isFalse();
        assertThat(json(send("GET", "/api/payments/" + id, null, token))).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM payment_attempts", UUID.class)).isEqualTo(CUSTOMER);
    }

    @Test
    void recordsDeclinesAndAllowsANewKeyUntilTheOrderIsApproved() throws Exception {
        var insufficient = json(pay("checkout-0001", body("sim-card-insufficient-funds"), token));
        var declined = json(pay("checkout-0002", body("sim-card-declined"), token));
        var approved = pay("checkout-0003", body("sim-card-approved"), token);

        assertThat(insufficient.path("status").asString()).isEqualTo("DECLINED");
        assertThat(insufficient.path("declineReason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(declined.path("declineReason").asString()).isEqualTo("CARD_DECLINED");
        assertThat(approved.statusCode()).isEqualTo(201);
        var paid = pay("checkout-0004", body("sim-card-approved"), token);
        assertProblem(paid, 409);
        assertThat(json(paid).path("detail").asString()).isEqualTo("O pedido já possui um pagamento aprovado.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isEqualTo(3);
    }

    @Test
    void replaysTheSameKeyAndIntentWithTheStoredAttemptAndRejectsAnotherIntent() throws Exception {
        var created = pay("checkout-0001", body("sim-card-declined"), token);
        var replayed = pay("checkout-0001", body("sim-card-declined").replace("59.80", "59.8"), token);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(json(replayed)).isEqualTo(json(created));
        assertThat(replayed.headers().firstValue("Location")).isEqualTo(created.headers().firstValue("Location"));

        assertProblem(pay("checkout-0001", body("sim-card-approved"), token), 422);
        assertProblem(pay("checkout-0001", body("sim-card-declined").replace("59.80", "59.81"), token), 422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isEqualTo(1);
    }

    @Test
    void hidesAnotherCustomersAttemptsAndKeepsTheirKeysIndependent() throws Exception {
        String id = json(pay("checkout-0001", body("sim-card-approved"), token)).path("id").asString();
        String other = TestAccessTokens.issue(UUID.randomUUID());

        var foreign = send("GET", "/api/payments/" + id, null, other);
        assertProblem(foreign, 404);
        assertThat(json(foreign).path("detail").asString()).isEqualTo("Pagamento não encontrado.");
        assertThat(json(send("GET", "/api/payments?orderId=" + ORDER, null, other)).path("totalElements").asLong())
                .isZero();
        var sameKey = pay("checkout-0001", body("sim-card-declined"), other);
        assertThat(sameKey.statusCode()).isEqualTo(201);
        assertThat(json(sameKey).path("id").asString()).isNotEqualTo(id);
        assertProblem(send("GET", "/api/payments/" + UUID.randomUUID(), null, token), 404);
    }

    @Test
    void listsTheCustomersAttemptsForAnOrderInCreationOrder() throws Exception {
        String first = json(pay("checkout-0001", body("sim-card-declined"), token)).path("id").asString();
        String second = json(pay("checkout-0002", body("sim-card-approved"), token)).path("id").asString();
        pay("checkout-0003", body("sim-card-approved").replace(ORDER.toString(), UUID.randomUUID().toString()), token);

        var page = json(send("GET", "/api/payments?orderId=" + ORDER + "&page=0&size=1", null, token));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(first);
        var all = json(send("GET", "/api/payments?orderId=" + ORDER, null, token));
        assertThat(all.path("size").asInt()).isEqualTo(20);
        assertThat(all.path("items").valueStream().map(item -> item.path("id").asString()))
                .containsExactly(first, second);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidPaymentRequestsWithoutStoringThem(String body) throws Exception {
        assertProblem(pay("checkout-0001", body, token), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isZero();
    }

    static Stream<String> invalidBodies() {
        String order = "\"orderId\":\"" + ORDER + "\"";
        return Stream.of("", "{", "null", "[]", "{}",
                "{\"amount\":10,\"method\":\"sim-card-approved\"}",
                "{\"orderId\":\"invalid\",\"amount\":10,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":\"10.00\",\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":0,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":-1,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":10.001,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":494999999950.51,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":true,\"method\":\"sim-card-approved\"}",
                "{" + order + ",\"amount\":10}",
                "{" + order + ",\"amount\":10,\"method\":\" \"}",
                "{" + order + ",\"amount\":10,\"method\":\"4111111111111111\"}",
                "{" + order + ",\"amount\":10,\"method\":123}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "short", "has space key", "chave-com-acentuação" })
    void requiresAWellFormedIdempotencyKey(String key) throws Exception {
        assertProblem(pay(key, body("sim-card-approved"), token), 400);
        assertProblem(send("POST", "/api/payments", body("sim-card-approved"), token), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "page=-1", "size=0", "size=101", "page=abc", "orderId=invalid", "" })
    void rejectsInvalidListQueries(String query) throws Exception {
        String orderParameter = query.startsWith("orderId") || query.isEmpty() ? "" : "orderId=" + ORDER + "&";
        assertProblem(send("GET", "/api/payments?" + orderParameter + query, null, token), 400);
    }

    @ParameterizedTest
    @MethodSource("invalidCredentials")
    void requiresAValidAccessTokenBeforeTouchingData(String credential) throws Exception {
        for (var response : new HttpResponse<?>[] { pay("checkout-0001", body("sim-card-approved"), credential),
                send("GET", "/api/payments/" + UUID.randomUUID(), null, credential),
                send("GET", "/api/payments?orderId=" + ORDER, null, credential) }) {
            @SuppressWarnings("unchecked")
            var typed = (HttpResponse<String>) response;
            assertProblem(typed, 401);
            assertThat(typed.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Bearer"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Integer.class)).isZero();
    }

    static Stream<String> invalidCredentials() {
        return Stream.of(null, "not-a-token",
                TestAccessTokens.issue("ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=", Instant.now(), claims -> { }),
                TestAccessTokens.issue(TestAccessTokens.SECRET, Instant.now().minusSeconds(1000), claims -> { }));
    }

    @Test
    void keepsPingAndActuatorPublic() throws Exception {
        var ping = send("GET", "/api/payments/ping", null, null);
        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(json(ping).path("service").asString()).isEqualTo("payment-service");
        assertThat(json(send("GET", "/actuator/health", null, null)).path("status").asString()).isEqualTo("UP");
        assertThat(send("GET", "/actuator/info", null, null).statusCode()).isEqualTo(200);
    }

    private static String body(String method) {
        return "{\"orderId\":\"%s\",\"amount\":59.80,\"method\":\"%s\"}".formatted(ORDER, method);
    }

    private HttpResponse<String> pay(String key, String body, String credential) throws Exception {
        return send("POST", "/api/payments", body, credential, key);
    }

    private HttpResponse<String> send(String method, String path, String body, String credential) throws Exception {
        return send(method, path, body, credential, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String credential, String key)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (credential != null) {
            request.header("Authorization", "Bearer " + credential);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(json(response).path("status").asInt()).isEqualTo(status);
        assertThat(json(response).path("detail").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("org.hibernate", "SQLException", "stackTrace", "com.victhor");
    }
}
