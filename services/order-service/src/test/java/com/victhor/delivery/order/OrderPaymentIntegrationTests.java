package com.victhor.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.order.infrastructure.auth.TestAccessTokens;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderPaymentIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID MENU_ITEM = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String TOKEN = TestAccessTokens.issue(CUSTOMER);
    private static final HttpServer REMOTE = startRemote();
    /** Attempts stored by the fake Payment Service, keyed by idempotency key, like the real one per customer. */
    private static final Map<String, ObjectNode> ATTEMPTS = new ConcurrentHashMap<>();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static volatile String mode;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void remoteUrls(DynamicPropertyRegistry properties) {
        String url = "http://localhost:" + REMOTE.getAddress().getPort();
        properties.add("order.integration.catalog-url", () -> url);
        properties.add("order.integration.delivery-url", () -> url);
        properties.add("order.integration.payment-url", () -> url);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM order_delivery_requests");
        jdbc.update("DELETE FROM order_payments");
        jdbc.update("DELETE FROM orders");
        ATTEMPTS.clear();
        AUTHORIZATIONS.clear();
        mode = "normal";
    }

    @AfterAll
    static void stopRemote() {
        REMOTE.stop(0);
    }

    @Test
    void approvalConfirmsTheOrderAndRepeatingTheKeyReturnsTheSamePayment() throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();

        var first = pay(order, key, "sim-card-approved");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode payment = JSON.readTree(first.body());
        assertThat(payment.path("status").asString()).isEqualTo("APPROVED");
        assertThat(payment.path("amount").decimalValue()).isEqualByComparingTo("51.80");
        assertThat(payment.path("currency").asString()).isEqualTo("BRL");
        assertThat(payment.path("method").asString()).isEqualTo("sim-card-approved");
        assertThat(AUTHORIZATIONS).containsExactly("Bearer " + TOKEN);
        JsonNode confirmed = get(order);
        assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("paymentId")).isEqualTo(payment.path("paymentId"));
        assertThat(confirmed.path("paymentRequestedAt").isNull()).isTrue();
        assertThat(confirmed.path("confirmedAt").asString()).isEqualTo(payment.path("completedAt").asString());

        var repeated = pay(order, key, "sim-card-approved");
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(repeated.body())).isEqualTo(payment);
        assertThat(AUTHORIZATIONS).hasSize(1);
        assertProblem(pay(order, UUID.randomUUID().toString(), "sim-card-approved"), 409);
        assertProblem(send("POST", order + "/cancel", null), 409);
        assertThat(get(order)).isEqualTo(confirmed);
        assertThat(ATTEMPTS).hasSize(1);
    }

    @Test
    void declineReleasesTheOrderForAnotherKeyAndKeepsTheDeclineReplayable() throws Exception {
        String order = newOrder();
        String declinedKey = UUID.randomUUID().toString();

        var declined = pay(order, declinedKey, "sim-card-insufficient-funds");
        assertThat(declined.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(declined.body()).path("status").asString()).isEqualTo("DECLINED");
        assertThat(JSON.readTree(declined.body()).path("declineReason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(get(order).path("status").asString()).isEqualTo("CREATED");
        assertThat(get(order).path("paymentRequestedAt").isNull()).isTrue();
        assertThat(JSON.readTree(pay(order, declinedKey, "sim-card-insufficient-funds").body()))
                .isEqualTo(JSON.readTree(declined.body()));
        assertProblem(pay(order, declinedKey, "sim-card-approved"), 422);

        var approved = pay(order, UUID.randomUUID().toString(), "sim-card-approved");
        assertThat(JSON.readTree(approved.body()).path("status").asString()).isEqualTo("APPROVED");
        assertThat(get(order).path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(ATTEMPTS).hasSize(2);
    }

    @Test
    void aLostResponseLeavesThePaymentPendingAndTheSameKeyRecoversItWithoutChargingTwice() throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();
        mode = "lost-response";

        assertProblem(pay(order, key, "sim-card-approved"), 503);
        JsonNode awaiting = get(order);
        assertThat(awaiting.path("status").asString()).isEqualTo("CREATED");
        assertThat(awaiting.path("paymentRequestedAt").isString()).isTrue();
        assertProblem(send("POST", order + "/cancel", null), 409);
        assertProblem(pay(order, UUID.randomUUID().toString(), "sim-card-approved"), 409);
        assertProblem(send("POST", order + "/delivery", null), 409);

        mode = "normal";
        var recovered = pay(order, key, "sim-card-approved");
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(recovered.body()).path("paymentId").asString())
                .isEqualTo(ATTEMPTS.get(key).path("id").asString());
        assertThat(ATTEMPTS).hasSize(1);
        assertThat(get(order).path("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void anUnavailablePaymentServiceKeepsTheOrderBlockedUntilTheRetry() throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();
        mode = "down";

        assertProblem(pay(order, key, "sim-card-declined"), 503);
        assertThat(ATTEMPTS).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM order_payments", String.class)).isEqualTo("PENDING");

        mode = "normal";
        assertThat(JSON.readTree(pay(order, key, "sim-card-declined").body()).path("status").asString())
                .isEqualTo("DECLINED");
        assertThat(send("POST", order + "/cancel", null).statusCode()).isEqualTo(200);
    }

    @Test
    void adoptsAnApprovalMadeDirectlyInPaymentWhileTheOrderWasPending() throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();
        mode = "down";
        assertProblem(pay(order, key, "sim-card-approved"), 503);
        // Someone charged the pending order through POST /api/payments with a different key meanwhile.
        String orderId = order.substring(order.lastIndexOf('/') + 1);
        ATTEMPTS.put("direct-key-0001", attempt(orderId, "51.80", "sim-card-approved"));

        mode = "normal";
        var settled = pay(order, key, "sim-card-approved");
        assertThat(settled.statusCode()).as(settled.body()).isEqualTo(200);
        assertThat(JSON.readTree(settled.body()).path("paymentId").asString())
                .isEqualTo(ATTEMPTS.get("direct-key-0001").path("id").asString());
        assertThat(get(order).path("paymentId").asString()).isEqualTo(ATTEMPTS.get("direct-key-0001").path("id").asString());
        assertThat(ATTEMPTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "reject", "conflict" })
    void aRefusalChargesNothingAndReleasesTheOrder(String refusal) throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();
        mode = refusal;

        assertProblem(pay(order, key, "sim-card-approved"), 409);
        assertThat(jdbc.queryForObject("SELECT status FROM order_payments", String.class)).isEqualTo("REJECTED");
        assertThat(get(order).path("paymentRequestedAt").isNull()).isTrue();
        mode = "normal";
        assertProblem(pay(order, key, "sim-card-approved"), 409);
        assertThat(ATTEMPTS).isEmpty();
        assertThat(send("POST", order + "/cancel", null).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentRequestsWithTheSameKeyChargeOnce() throws Exception {
        String order = newOrder();
        String key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<HttpResponse<String>>>();
            for (int index = 0; index < 6; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return pay(order, key, "sim-card-approved");
                }));
            }
            start.countDown();
            for (var result : results) {
                // A request that loses the race on the order version reports a conflict and changes nothing.
                assertThat(result.get(30, TimeUnit.SECONDS).statusCode()).isIn(200, 409);
            }
        }
        assertThat(ATTEMPTS).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isEqualTo(1);
        var settled = pay(order, key, "sim-card-approved");
        assertThat(JSON.readTree(settled.body()).path("status").asString()).isEqualTo("APPROVED");
        assertThat(get(order).path("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void validatesTheRequestBeforeRecordingAnIntent() throws Exception {
        String order = newOrder();

        assertProblem(pay(order, null, "sim-card-approved"), 400);
        assertProblem(pay(order, "short", "sim-card-approved"), 400);
        assertProblem(pay(order, UUID.randomUUID().toString(), "4111111111111111"), 400);
        assertProblem(send("POST", order + "/payment", "{}", UUID.randomUUID().toString()), 400);
        assertProblem(pay("/api/orders/invalid", UUID.randomUUID().toString(), "sim-card-approved"), 400);
        assertProblem(pay("/api/orders/" + UUID.randomUUID(), UUID.randomUUID().toString(), "sim-card-approved"), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isZero();
        assertThat(ATTEMPTS).isEmpty();
    }

    @Test
    void ignoresAnAmountInTheBodyAndChargesTheOrderTotal() throws Exception {
        String order = newOrder();

        var response = send("POST", order + "/payment", """
                {"method":"sim-card-approved","amount":0.01,"status":"APPROVED"}
                """, UUID.randomUUID().toString());
        assertThat(JSON.readTree(response.body()).path("amount").decimalValue()).isEqualByComparingTo("51.80");
        assertThat(ATTEMPTS.values()).singleElement()
                .satisfies(attempt -> assertThat(attempt.path("amount").decimalValue()).isEqualByComparingTo("51.80"));
    }

    @Test
    void refusesToPayCancelledAndPricelessOrders() throws Exception {
        String cancelled = newOrder();
        assertThat(send("POST", cancelled + "/cancel", null).statusCode()).isEqualTo(200);
        assertProblem(pay(cancelled, UUID.randomUUID().toString(), "sim-card-approved"), 409);
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id,customer_id,restaurant_id,destination_address,destination_latitude,
                    destination_longitude,status,created_at,updated_at)
                VALUES (?,?,?,'Legacy',0,0,'CREATED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, legacy, CUSTOMER, RESTAURANT);
        assertProblem(pay("/api/orders/" + legacy, UUID.randomUUID().toString(), "sim-card-approved"), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isZero();
        assertThat(ATTEMPTS).isEmpty();
    }

    @Test
    void hidesAnotherCustomersOrderAndRequiresAToken() throws Exception {
        String order = newOrder();
        String body = "{\"method\":\"sim-card-approved\"}";
        String key = UUID.randomUUID().toString();

        var foreign = send("POST", order + "/payment", body, key, TestAccessTokens.issue(UUID.randomUUID()));
        assertProblem(foreign, 404);
        assertProblem(send("POST", order + "/payment", body, key, null), 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isZero();
        assertThat(get(order).path("paymentRequestedAt").isNull()).isTrue();
        assertThat(ATTEMPTS).isEmpty();
    }

    private String newOrder() throws Exception {
        String body = """
                {"restaurantId":"%s","items":[{"menuItemId":"%s","quantity":2}],
                 "destination":{"address":"Rua Central, 42","latitude":-23.56,"longitude":-46.64}}
                """.formatted(RESTAURANT, MENU_ITEM);
        var response = send("POST", "/api/orders", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return "/api/orders/" + JSON.readTree(response.body()).path("id").asString();
    }

    private JsonNode get(String order) throws Exception {
        return JSON.readTree(send("GET", order, null).body());
    }

    private HttpResponse<String> pay(String order, String key, String method) throws Exception {
        return send("POST", order + "/payment", "{\"method\":\"" + method + "\"}", key);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(method, path, body, null, TOKEN);
    }

    private HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
        return send(method, path, body, key, TOKEN);
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.body()).doesNotContain("SQLException", "stackTrace", TOKEN);
    }

    private static HttpServer startRemote() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/catalog/restaurants", OrderPaymentIntegrationTests::catalog);
            server.createContext("/api/payments", OrderPaymentIntegrationTests::payments);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void catalog(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().contains("/menu-items/")) {
            respond(exchange, 200, JSON.createObjectNode().put("id", MENU_ITEM.toString())
                    .put("restaurantId", RESTAURANT.toString()).put("name", "Prato")
                    .put("price", new java.math.BigDecimal("25.90")).put("currency", "BRL").put("available", true)
                    .toString());
            return;
        }
        respond(exchange, 200, JSON.createObjectNode().put("id", RESTAURANT.toString()).put("name", "Central")
                .put("active", true).putNull("pickupLocation").toString());
    }

    /** Behaves like the Payment Service: one attempt per key, one approval per order, outcome fixed by method. */
    private static synchronized void payments(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            String orderId = exchange.getRequestURI().getQuery().replaceAll(".*orderId=([^&]+).*", "$1");
            var page = JSON.createObjectNode();
            page.set("items", JSON.valueToTree(ATTEMPTS.values().stream()
                    .filter(attempt -> attempt.path("orderId").asString().equals(orderId)).toList()));
            respond(exchange, 200, page.toString());
            return;
        }
        AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
        switch (mode) {
            case "down" -> {
                respond(exchange, 503, "{}");
                return;
            }
            case "reject" -> {
                respond(exchange, 400, "{}");
                return;
            }
            case "conflict" -> {
                respond(exchange, 409, "{}");
                return;
            }
            default -> {
                // continue
            }
        }
        JsonNode request = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        ObjectNode stored = ATTEMPTS.get(key);
        int status = 200;
        if (stored == null) {
            String orderId = request.path("orderId").asString();
            boolean paid = ATTEMPTS.values().stream().anyMatch(attempt -> attempt.path("orderId").asString()
                    .equals(orderId) && attempt.path("status").asString().equals("APPROVED"));
            if (paid) {
                respond(exchange, 409, "{}");
                return;
            }
            stored = attempt(orderId, request.path("amount").decimalValue().toPlainString(),
                    request.path("method").asString());
            ATTEMPTS.put(key, stored);
            status = 201;
        }
        respond(exchange, mode.equals("lost-response") ? 503 : status, stored.toString());
    }

    private static ObjectNode attempt(String orderId, String amount, String method) {
        String reason = switch (method) {
            case "sim-card-declined" -> "CARD_DECLINED";
            case "sim-card-insufficient-funds" -> "INSUFFICIENT_FUNDS";
            default -> null;
        };
        ObjectNode attempt = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("orderId", orderId)
                .put("amount", new java.math.BigDecimal(amount)).put("currency", "BRL").put("method", method)
                .put("status", reason == null ? "APPROVED" : "DECLINED").put("simulated", true)
                .put("createdAt", "2026-10-07T12:00:00Z");
        return reason == null ? attempt.putNull("declineReason") : attempt.put("declineReason", reason);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
