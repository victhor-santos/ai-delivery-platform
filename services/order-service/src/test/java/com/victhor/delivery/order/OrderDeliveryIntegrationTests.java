package com.victhor.delivery.order;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.order.application.DeliveryRequestRepository;
import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.DeliveryRequest;
import com.victhor.delivery.order.domain.OrderStatus;
import com.victhor.delivery.order.domain.OrderStateConflictException;
import com.victhor.delivery.order.infrastructure.auth.TestAccessTokens;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderDeliveryIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID MENU_ITEM = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final HttpServer REMOTE = startRemote();
    private static final AtomicInteger CATALOG_CALLS = new AtomicInteger();
    private static final AtomicInteger CREATED_DELIVERIES = new AtomicInteger();
    private static volatile int catalogStatus;
    private static volatile String mode;
    private static volatile ObjectNode receipt;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryRequestRepository requests;

    @Autowired
    private OrderRepository orders;

    private final HttpClient client = HttpClient.newHttpClient();

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
        catalogStatus = 200;
        mode = "normal";
        receipt = null;
        CATALOG_CALLS.set(0);
        CREATED_DELIVERIES.set(0);
    }

    @AfterAll
    static void stopRemote() {
        REMOTE.stop(0);
    }

    @Test
    void requestsDeliveryWithSnapshotsAndRepeatsWithoutFetchingCatalogAgain() throws Exception {
        String orderPath = confirmedOrder();
        var first = send("POST", orderPath + "/delivery", null);
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode response = JSON.readTree(first.body());
        assertThat(response.path("status").asString()).isEqualTo("CREATED");
        assertThat(response.path("orderId").asString()).isEqualTo(orderPath.substring(orderPath.lastIndexOf('/') + 1));
        assertThat(receipt.path("origin").path("description").asString()).isEqualTo("Restaurant Central");
        assertThat(receipt.path("destination").path("description").asString()).isEqualTo("Rua Central, 42");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_delivery_requests", Integer.class)).isEqualTo(1);
        JsonNode order = JSON.readTree(send("GET", orderPath, null).body());
        assertThat(Instant.parse(order.path("deliveryRequestedAt").asString()))
                .isAfterOrEqualTo(Instant.parse(order.path("confirmedAt").asString()));
        var repeated = send("POST", orderPath + "/delivery", null);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(repeated.body())).isEqualTo(response);
        assertThat(CATALOG_CALLS.get()).isEqualTo(1);
        assertThat(CREATED_DELIVERIES.get()).isEqualTo(1);
        assertThat(send("POST", orderPath + "/cancel", null).statusCode()).isEqualTo(409);
    }

    @Test
    void recoversFromLostResponseUsingFrozenSnapshotAndSameDelivery() throws Exception {
        String order = confirmedOrder();
        mode = "lost-response";
        assertProblem(send("POST", order + "/delivery", null), 503);
        UUID originalDelivery = UUID.fromString(receipt.path("id").asString());
        assertThat(send("POST", order + "/cancel", null).statusCode()).isEqualTo(409);
        catalogStatus = 404;
        mode = "normal";
        var retried = send("POST", order + "/delivery", null);
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(retried.body()).path("deliveryId").asString()).isEqualTo(originalDelivery.toString());
        assertThat(CATALOG_CALLS.get()).isEqualTo(1);
        assertThat(CREATED_DELIVERIES.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "no-location"})
    void invalidRestaurantRecordsNoDeliveryIntentAndAllowsALaterRetry(String responseMode) throws Exception {
        String order = confirmedOrder();
        mode = responseMode;
        assertProblem(send("POST", order + "/delivery", null), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_delivery_requests", Integer.class)).isZero();
        assertThat(JSON.readTree(send("GET", order, null).body()).path("deliveryRequestedAt").isNull()).isTrue();
        // A paid order is never cancelled without a refund, which V1 does not offer.
        assertProblem(send("POST", order + "/cancel", null), 409);
        mode = "normal";
        assertThat(send("POST", order + "/delivery", null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void missingOrUnavailableCatalogDoesNotCreateIntent(int remoteStatus) throws Exception {
        String order = confirmedOrder();
        catalogStatus = remoteStatus;
        assertProblem(send("POST", order + "/delivery", null), remoteStatus == 404 ? 409 : 503);
        assertThat(CREATED_DELIVERIES.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_delivery_requests", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-catalog", "invalid-delivery", "delivery-down", "delivery-conflict"})
    void doesNotReportDeliverySuccessForInvalidOrFailedResponses(String responseMode) throws Exception {
        String order = confirmedOrder();
        mode = responseMode;
        assertProblem(send("POST", order + "/delivery", null), mode.equals("delivery-conflict") ? 409 : 503);
        int expectedIntents = mode.equals("invalid-catalog") ? 0 : 1;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_delivery_requests", Integer.class)).isEqualTo(expectedIntents);
    }

    @Test
    void onlyExistingConfirmedOrdersCanRequestDelivery() throws Exception {
        String order = newOrder();
        assertProblem(send("POST", order + "/delivery", null), 409);
        send("POST", order + "/cancel", null);
        assertProblem(send("POST", order + "/delivery", null), 409);
        assertProblem(send("POST", "/api/orders/" + UUID.randomUUID() + "/delivery", null), 404);
        assertProblem(send("POST", "/api/orders/not-a-uuid/delivery", null), 400);
        assertThat(CATALOG_CALLS.get()).isZero();
    }

    @Test
    void migrationPreservesExistingConfirmedOrders() {
        String schema = "orders_before_delivery";
        var v1 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).target("1").load();
        v1.migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders_before_delivery.orders (id, restaurant_id, destination_address,
                    destination_latitude, destination_longitude, status, created_at, updated_at, confirmed_at)
                VALUES (?, ?, 'Legacy address', 0, 0, 'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, RESTAURANT);
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).target("2").load().migrate();
        var row = jdbc.queryForMap("SELECT status, destination_address, delivery_requested_at FROM orders_before_delivery.orders WHERE id = ?", id);
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("destination_address", "Legacy address")
                .containsEntry("delivery_requested_at", null);
    }

    @Test
    void concurrentCancellationAndDeliveryIntentCannotBothCommit() throws Exception {
        String path = confirmedOrder();
        UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        var order = orders.findById(id).orElseThrow();
        var request = new DeliveryRequest(id, new DeliveryDestination("Restaurant Central", -23.55, -46.63),
                order.destination());
        Instant time = order.confirmedAt().plusSeconds(1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var prepare = executor.submit(() -> attempt(start, () -> requests.prepare(request, time)));
            var cancel = executor.submit(() -> attempt(start, () -> orders.cancel(id, time)));
            start.countDown();
            assertThat(new boolean[]{prepare.get(10, TimeUnit.SECONDS), cancel.get(10, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
        }
        var stored = orders.findById(id).orElseThrow();
        if (stored.status() == OrderStatus.CANCELLED) {
            assertThat(requests.findByOrderId(id)).isEmpty();
            assertThat(stored.deliveryRequestedAt()).isNull();
        } else {
            assertThat(requests.findByOrderId(id)).contains(request);
            assertThat(stored.deliveryRequestedAt()).isEqualTo(time);
        }
    }

    private boolean attempt(CountDownLatch start, Callable<?> command) throws Exception {
        start.await();
        try {
            command.call();
            return true;
        } catch (OrderStateConflictException | OptimisticLockingFailureException expected) {
            return false;
        }
    }

    private String newOrder() throws Exception {
        String body = """
                {"restaurantId":"%s","items":[{"menuItemId":"%s","quantity":2}],
                 "destination":{"address":"Rua Central, 42","latitude":-23.56,"longitude":-46.64}}
                """.formatted(RESTAURANT, MENU_ITEM);
        var response = send("POST", "/api/orders", body);
        assertThat(response.statusCode()).isEqualTo(201);
        CATALOG_CALLS.set(0);
        return "/api/orders/" + JSON.readTree(response.body()).path("id").asString();
    }

    private String confirmedOrder() throws Exception {
        String path = newOrder();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "/payment"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + TestAccessTokens.issue(CUSTOMER))
                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"sim-card-approved\"}")).build();
        var payment = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(payment.statusCode()).as(payment.body()).isEqualTo(200);
        assertThat(JSON.readTree(send("GET", path, null).body()).path("status").asString()).isEqualTo("CONFIRMED");
        return path;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.header("Authorization", "Bearer " + TestAccessTokens.issue(CUSTOMER));
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.body()).doesNotContain("SQLException", "stackTrace", "internal-detail");
    }

    private static HttpServer startRemote() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/catalog/restaurants", OrderDeliveryIntegrationTests::catalog);
            server.createContext("/api/deliveries/by-order", OrderDeliveryIntegrationTests::delivery);
            server.createContext("/api/payments", OrderDeliveryIntegrationTests::payment);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void catalog(HttpExchange exchange) throws IOException {
        CATALOG_CALLS.incrementAndGet();
        if (exchange.getRequestURI().getPath().contains("/menu-items/")) {
            var item = JSON.createObjectNode().put("id", MENU_ITEM.toString()).put("restaurantId", RESTAURANT.toString())
                    .put("name", "Prato").put("price", new java.math.BigDecimal("25.90"))
                    .put("currency", "BRL").put("available", true);
            respond(exchange, 200, item.toString());
            return;
        }
        if (mode.equals("invalid-catalog")) {
            respond(exchange, 200, "{\"internal-detail\":true}");
            return;
        }
        ObjectNode restaurant = JSON.createObjectNode().put("id", RESTAURANT.toString()).put("name", "Restaurant Central")
                .put("active", !mode.equals("inactive"));
        restaurant.set("pickupLocation", mode.equals("no-location") ? JSON.nullNode()
                : JSON.createObjectNode().put("latitude", -23.55).put("longitude", -46.63));
        respond(exchange, catalogStatus, restaurant.toString());
    }

    private static void delivery(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("PUT")) {
            respond(exchange, 405, "{}");
            return;
        }
        if (mode.equals("delivery-down") || mode.equals("delivery-conflict")) {
            respond(exchange, mode.equals("delivery-down") ? 503 : 409, "{}");
            return;
        }
        ObjectNode request = (ObjectNode) JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (receipt == null) {
            receipt = request.deepCopy();
            receipt.put("id", UUID.randomUUID().toString()).put("status", "CREATED")
                    .put("orderId", exchange.getRequestURI().getPath().substring("/api/deliveries/by-order/".length()));
            CREATED_DELIVERIES.incrementAndGet();
        }
        if (mode.equals("invalid-delivery")) {
            respond(exchange, 200, "{}");
        } else {
            respond(exchange, mode.equals("lost-response") ? 503 : 200, receipt.toString());
        }
    }

    /** Approves every charge, echoing the request the way the Payment Service does. */
    private static void payment(HttpExchange exchange) throws IOException {
        ObjectNode request = (ObjectNode) JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        request.put("id", UUID.randomUUID().toString()).put("currency", "BRL").put("status", "APPROVED")
                .putNull("declineReason").put("simulated", true);
        respond(exchange, 201, request.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
