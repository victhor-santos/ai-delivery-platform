package com.victhor.delivery.delivery.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DELIVERY_DB_PASSWORD=testcontainers-only", "delivery.routing.timeout-ms=1500"})
@Testcontainers
class DeliveryRouteApiTests {

    private static final String REQUEST = "{\"departureAt\":\"2026-10-03T19:00:00-03:00\"}";
    private static final String VALID_ROUTE = """
            {"route":[{"lat":-23.5505,"lon":-46.6333},{"lat":-23.554,"lon":-46.640},{"lat":-23.561,"lon":-46.656}],
             "segments":[{"segment_id":"A-B","distance_km":0.9,"predicted_travel_time_minutes":4.9},
                         {"segment_id":"B-C","distance_km":2.0,"predicted_travel_time_minutes":11.1}],
             "distance_km":2.9,"predicted_travel_time_minutes":16.0,
             "predicted_at":"2026-10-03T12:00:00Z","context_as_of":"2026-10-03T11:00:00Z",
             "model_version":"segment-model-v1-0123456789abcdef","graph_version":"synthetic-city-v1","data_origin":"synthetic"}
            """;
    private static final AtomicReference<Reply> reply = new AtomicReference<>();
    private static final AtomicReference<String> captured = new AtomicReference<>();
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicReference<CountDownLatch> received = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService remoteWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer remote = startRemote();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void routeServiceUrl(DynamicPropertyRegistry properties) {
        properties.add("delivery.routing.url", () -> "http://127.0.0.1:" + remote.getAddress().getPort());
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private HikariDataSource dataSource;

    private static final HttpClient client = HttpClient.newHttpClient();
    private UUID deliveryId;
    private String routePath;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM deliveries");
        jdbc.update("DELETE FROM couriers");
        reply.set(new Reply(200, "application/json", VALID_ROUTE));
        calls.set(0);
        captured.set(null);
        received.set(null);
        release.set(null);
        var created = send("POST", "/api/deliveries", """
                {"orderId":"%s","origin":{"description":"Restaurant","latitude":-23.5505,"longitude":-46.6333},
                 "destination":{"description":"Destination","latitude":-23.561,"longitude":-46.656}}
                """.formatted(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        deliveryId = UUID.fromString(mapper.readTree(created.body()).path("id").asString());
        routePath = "/api/deliveries/" + deliveryId + "/route";
    }

    @AfterAll
    static void closeServers() {
        remote.stop(0);
        remoteWorkers.shutdownNow();
        remoteWorkers.close();
        client.close();
    }

    @Test
    void plansPersistsQueriesAndReplacesWithoutChangingDeliveryLifecycle() throws Exception {
        assertProblem(send("GET", routePath, null), 404, "ROUTE_PLAN_NOT_FOUND");
        var original = send("GET", "/api/deliveries/" + deliveryId, null).body();
        var response = send("POST", routePath, REQUEST);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode plan = mapper.readTree(response.body());
        assertThat(plan.path("deliveryId").asString()).isEqualTo(deliveryId.toString());
        assertThat(plan.path("departureAt").asString()).isEqualTo("2026-10-03T22:00:00Z");
        assertThat(plan.path("plannedAt").isString()).isTrue();
        assertThat(plan.path("modelVersion").asString()).isEqualTo("segment-model-v1-0123456789abcdef");
        assertThat(plan.path("route").size()).isEqualTo(3);
        assertThat(plan.path("route").get(0).path("latitude").asDouble()).isEqualTo(-23.5505);
        assertThat(plan.path("segments").get(1).path("segmentId").asString()).isEqualTo("B-C");
        assertThat(plan.path("distanceKm").asDouble()).isEqualTo(2.9);
        assertThat(plan.path("predictedTravelTimeMinutes").asDouble()).isEqualTo(16);
        assertThat(plan.has("deliveryVersion")).isFalse();
        assertThat(mapper.readTree(send("GET", routePath, null).body())).isEqualTo(plan);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(send("GET", "/api/deliveries/" + deliveryId, null).body()).isEqualTo(original);
        JsonNode pythonRequest = mapper.readTree(captured.get());
        assertThat(pythonRequest.path("origin").path("lat").asDouble()).isEqualTo(-23.5505);
        assertThat(pythonRequest.path("departure_at").asString()).isEqualTo("2026-10-03T22:00:00Z");
        JsonNode second = mapper.readTree(send("POST", routePath, REQUEST).body());
        assertThat(second.path("id")).isNotEqualTo(plan.path("id"));
        assertThat(mapper.readTree(send("GET", routePath, null).body())).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_route_plans", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("badRequests")
    void rejectsInvalidDepartureWithoutCallingPython(String request) throws Exception {
        assertProblem(send("POST", routePath, request), 400, null);
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM deliveries WHERE id = ?", Long.class, deliveryId)).isZero();
    }

    static Stream<String> badRequests() {
        return Stream.of("{}", "null", "not json", "{\"departureAt\":null}", "{\"departureAt\":1}",
                "{\"departureAt\":true}", "{\"departureAt\":\"2026-10-03T19:00:00\"}",
                "{\"departureAt\":\"2026-02-30T19:00:00Z\"}", "{\"departureAt\":\"2026-13-03T19:00:00Z\"}",
                "{\"departureAt\":\"0000-10-03T19:00:00Z\"}");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void preservesSavedPlanAndDeliveryAfterRemoteFailure(int remoteStatus, String code, int status, String publicCode)
            throws Exception {
        var saved = send("POST", routePath, REQUEST).body();
        var delivery = send("GET", "/api/deliveries/" + deliveryId, null).body();
        Long version = jdbc.queryForObject("SELECT version FROM deliveries WHERE id = ?", Long.class, deliveryId);
        reply.set(new Reply(remoteStatus, "application/problem+json", """
                {"type":"about:blank","title":"Error","status":%d,"detail":"private remote data","code":"%s"}
                """.formatted(remoteStatus, code)));
        assertProblem(send("POST", routePath, REQUEST), status, publicCode);
        assertThat(send("GET", routePath, null).body()).isEqualTo(saved);
        assertThat(send("GET", "/api/deliveries/" + deliveryId, null).body()).isEqualTo(delivery);
        assertThat(jdbc.queryForObject("SELECT version FROM deliveries WHERE id = ?", Long.class, deliveryId)).isEqualTo(version);
    }

    static Stream<Arguments> failures() {
        return Stream.of(Arguments.of(422, "OUTSIDE_GRAPH_COVERAGE", 422, "OUTSIDE_ROUTE_COVERAGE"),
                Arguments.of(404, "ROUTE_NOT_FOUND", 422, "ROUTE_NOT_FOUND"),
                Arguments.of(503, "MODEL_UNAVAILABLE", 503, "ROUTE_SERVICE_UNAVAILABLE"),
                Arguments.of(500, "INTERNAL_ERROR", 503, "ROUTE_SERVICE_UNAVAILABLE"),
                Arguments.of(422, "INVALID_REQUEST", 503, "ROUTE_SERVICE_UNAVAILABLE"));
    }

    @Test
    void rejectsMalformedPredictionAndRecoversOnANewRequest() throws Exception {
        reply.set(new Reply(200, "application/json", VALID_ROUTE.replace("16.0", "-16.0")));
        assertProblem(send("POST", routePath, REQUEST), 503, "ROUTE_SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_route_plans", Integer.class)).isZero();
        reply.set(new Reply(200, "application/json", VALID_ROUTE));
        assertThat(send("POST", routePath, REQUEST).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMissingDeliveriesAndInvalidLifecycleBeforeRemoteCall() throws Exception {
        assertProblem(send("POST", "/api/deliveries/" + UUID.randomUUID() + "/route", REQUEST), 404, null);
        assertProblem(send("GET", "/api/deliveries/" + UUID.randomUUID() + "/route", null), 404, null);
        assertThat(send("POST", "/api/deliveries/" + deliveryId + "/cancel", null).statusCode()).isEqualTo(200);
        assertProblem(send("POST", routePath, REQUEST), 409, null);
        assertThat(calls.get()).isZero();
    }

    @Test
    void releasesDatabaseConnectionDuringRemoteCallAndRejectsResponseAfterCancellation() throws Exception {
        var previous = send("POST", routePath, REQUEST).body();
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        received.set(entered);
        release.set(unblock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> send("POST", routePath, REQUEST));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(send("POST", "/api/deliveries/" + deliveryId + "/cancel", null).statusCode()).isEqualTo(200);
            } finally {
                unblock.countDown();
            }
            assertProblem(pending.get(5, TimeUnit.SECONDS), 409, "STALE_ROUTE_PLAN");
        }
        assertThat(send("GET", routePath, null).body()).isEqualTo(previous);
    }

    @Test
    void allowsOnlyOneOfTwoConcurrentResponsesAndKeepsTheWinningPlan() throws Exception {
        var entered = new CountDownLatch(2);
        var unblock = new CountDownLatch(1);
        received.set(entered);
        release.set(unblock);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> send("POST", routePath, REQUEST));
            var second = executor.submit(() -> send("POST", routePath, REQUEST));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                unblock.countDown();
            }
            var responses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            var success = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
            assertThat(send("GET", routePath, null).body()).isEqualTo(success.body());
        }
    }

    @Test
    void timesOutWithoutSavingAndKeepsPreviousPlanAvailable() throws Exception {
        var previous = send("POST", routePath, REQUEST).body();
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        received.set(entered);
        release.set(unblock);
        try {
            assertProblem(send("POST", routePath, REQUEST), 503, "ROUTE_SERVICE_UNAVAILABLE");
            assertThat(entered.getCount()).isZero();
            assertThat(send("GET", routePath, null).body()).isEqualTo(previous);
        } finally {
            unblock.countDown();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("status").asInt()).isEqualTo(status);
        if (code != null) {
            assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        }
        assertThat(response.body()).doesNotContain("private remote data", "SQLException", "stackTrace", "127.0.0.1");
    }

    private static HttpServer startRemote() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(remoteWorkers);
            server.createContext("/api/routes/fastest", exchange -> {
                calls.incrementAndGet();
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var signal = received.get();
                var wait = release.get();
                var response = reply.get();
                if (signal != null) {
                    signal.countDown();
                }
                try {
                    if (wait != null && !wait.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Remote test response was not released");
                    }
                    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", response.contentType());
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Reply(int status, String contentType, String body) {
    }
}
