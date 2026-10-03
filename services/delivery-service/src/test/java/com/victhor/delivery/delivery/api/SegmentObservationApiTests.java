package com.victhor.delivery.delivery.api;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.victhor.delivery.delivery.application.CourierRepository;
import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.application.DeliveryRoutePlan;
import com.victhor.delivery.delivery.application.DeliveryRouteRepository;
import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.RouteSegment;
import com.victhor.delivery.delivery.application.SegmentPredictionContext;
import com.victhor.delivery.delivery.domain.Courier;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DELIVERY_DB_PASSWORD=testcontainers-only")
@Testcontainers
class SegmentObservationApiTests {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final GeoPoint ORIGIN = new GeoPoint(-23.5505, -46.6333);
    private static final GeoPoint MIDDLE = new GeoPoint(-23.554, -46.64);
    private static final GeoPoint DESTINATION = new GeoPoint(-23.561, -46.656);
    private static final HttpClient client = HttpClient.newHttpClient();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private CourierRepository couriers;
    @Autowired private DeliveryRouteRepository routes;
    @MockitoBean private Clock clock;
    @LocalServerPort private int port;

    private final AtomicReference<Instant> receivedAt = new AtomicReference<>();
    private Delivery delivery;
    private DeliveryRoutePlan plan;
    private String path;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM deliveries");
        jdbc.update("DELETE FROM couriers");
        receivedAt.set(NOW.plusSeconds(600));
        when(clock.instant()).thenAnswer(invocation -> receivedAt.get());
        delivery = deliveries.create(Delivery.create(UUID.randomUUID(), new DeliveryLocation("Private pickup", ORIGIN),
                new DeliveryLocation("Private destination", DESTINATION), NOW));
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var route = new OptimizedRoute(List.of(ORIGIN, MIDDLE, DESTINATION),
                List.of(segment("A", "B"), segment("B", "C")), 2, 4, NOW, NOW, "model-v1", "graph-v1", "synthetic");
        plan = routes.save(snapshot, new DeliveryRoutePlan(UUID.randomUUID(), delivery.id(), NOW, NOW,
                snapshot.version() + 1, route));
        var courier = couriers.create(new Courier(UUID.randomUUID(), true));
        deliveries.assign(delivery.id(), courier.id(), NOW.plusSeconds(1));
        deliveries.pickUp(delivery.id(), NOW.plusSeconds(2));
        deliveries.startTransit(delivery.id(), NOW.plusSeconds(3));
        path = "/api/deliveries/" + delivery.id() + "/segments";
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    void persistsSnapshotsAndExportsOnlyCompletedObservationsAvailableAtTheCutoff() throws Exception {
        long initialVersion = version();
        var entry = send("PUT", path + "/0/entry", event(10));
        assertThat(entry.statusCode()).isEqualTo(200);
        var entered = mapper.readTree(entry.body());
        assertThat(entered.path("actualTravelTimeMinutes").isNull()).isTrue();
        assertThat(entered.path("prediction").path("segment").path("predictionContext").path("fromNode").asString())
                .isEqualTo("A");
        assertThat(export(receivedAt.get()).body().lines().count()).isEqualTo(1);
        assertThat(send("PUT", path + "/0/entry", event(10)).body()).isEqualTo(entry.body());
        assertThat(version()).isEqualTo(initialVersion + 1);
        var exit = send("PUT", path + "/0/exit", event(70));
        assertThat(exit.statusCode()).isEqualTo(200);
        var completed = mapper.readTree(exit.body());
        assertThat(completed.path("actualTravelTimeMinutes").asDouble()).isEqualTo(1);
        assertThat(completed.path("labelAvailableAt").asString()).isEqualTo(receivedAt.get().toString());
        assertThat(export(receivedAt.get().minusNanos(1000)).body().lines().count()).isEqualTo(1);
        var csv = export(receivedAt.get());
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElseThrow()).contains("text/csv");
        assertThat(csv.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(csv.body().lines().count()).isEqualTo(2);
        assertThat(csv.body()).contains("\"delivery-segment-observation-v1\"", "\"simulated\"", "\"synthetic\"", "\"1.0\"")
                .doesNotContain("Private pickup", "Private destination", "courier_id");
        assertThat(csv.body().lines().toList().get(0).split(",")).hasSize(34);
        assertThat(csv.body().lines().toList().get(1).split(",")).hasSize(34);
        receivedAt.set(receivedAt.get().plusSeconds(60));
        assertThat(send("PUT", path + "/0/exit", event(70)).body()).isEqualTo(exit.body());
        assertThat(version()).isEqualTo(initialVersion + 2);
        assertThat(send("PUT", path + "/1/entry", event(70)).statusCode()).isEqualTo(200);
        var rows = mapper.readTree(send("GET", path, null).body());
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0)).isEqualTo(completed);
        assertThat(rows.get(1).path("sequence").asInt()).isEqualTo(1);
        assertThat(export(receivedAt.get()).body().lines().count()).isEqualTo(2);
        assertThat(routes.findPlan(delivery.id())).contains(plan);
        assertThat(deliveries.findById(delivery.id()).orElseThrow().updatedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void rejectsConflictsWithoutChangingRowsOrVersion() throws Exception {
        assertStatus("/0/exit", event(70), 409);
        assertStatus("/1/entry", event(10), 409);
        assertStatus("/0/entry", event(2), 409);
        assertStatus("/0/entry", event(10).replace(plan.id().toString(), UUID.randomUUID().toString()), 409);
        assertStatus("/0/entry", event(10), 200);
        var previous = send("GET", path, null).body();
        long version = version();
        assertStatus("/0/entry", event(11), 409);
        assertStatus("/1/entry", event(20), 409);
        assertStatus("/0/exit", event(10), 400);
        assertStatus("/0/exit", event(70).replace(plan.id().toString(), UUID.randomUUID().toString()), 409);
        assertThat(send("GET", path, null).body()).isEqualTo(previous);
        assertThat(version()).isEqualTo(version);
        assertStatus("/0/exit", event(70), 200);
        assertStatus("/0/exit", event(71), 409);
        assertStatus("/1/entry", event(69), 409);
        assertStatus("/2/entry", event(80), 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-10-03", "2026-10-03T12:00:00", "2026-10-03T12:00Z",
            "2026-02-30T12:00:00Z", "+10000-01-01T00:00:00Z", "2026-10-03T13:00:00Z"})
    void rejectsMalformedOrFutureTimestamps(String timestamp) throws Exception {
        String body = "{\"routePlanId\":\"%s\",\"occurredAt\":\"%s\",\"dataOrigin\":\"simulated\"}"
                .formatted(plan.id(), timestamp);
        assertStatus("/0/entry", body, 400);
        assertThat(send("GET", path + "/export?availableAtCutoff=" + encode(timestamp), null).statusCode()).isEqualTo(400);
    }

    @Test
    void validatesIdentityOriginSequenceAndMissingDelivery() throws Exception {
        assertStatus("/-1/entry", event(10), 400);
        assertStatus("/199/entry", event(10), 400);
        assertStatus("/0/entry", "{}", 400);
        assertStatus("/0/entry", event(10).replace("simulated", "real"), 400);
        assertStatus("/0/entry", event(10).replace(plan.id().toString(), "bad-uuid"), 400);
        assertThat(send("GET", path + "/export", null).statusCode()).isEqualTo(400);
        String absent = "/api/deliveries/" + UUID.randomUUID() + "/segments";
        assertThat(send("GET", absent, null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", absent + "/0/entry", event(10)).statusCode()).isEqualTo(404);
    }

    @Test
    void acceptsLateEventsOnlyWithinTheCompletedJourney() throws Exception {
        deliveries.arrive(delivery.id(), NOW.plusSeconds(100));
        deliveries.complete(delivery.id(), NOW.plusSeconds(101));
        assertStatus("/0/entry", event(10), 200);
        assertStatus("/0/exit", event(102), 409);
        assertStatus("/0/exit", event(70), 200);
        assertStatus("/1/entry", event(102), 409);
    }

    @Test
    void arrivalCannotInvalidateAnAlreadyRecordedTraversal() throws Exception {
        assertStatus("/0/entry", event(10), 200);
        assertStatus("/0/exit", event(70), 200);
        receivedAt.set(NOW.plusSeconds(60));
        var arrival = send("POST", "/api/deliveries/" + delivery.id() + "/arrive", null);
        assertThat(arrival.statusCode()).isEqualTo(409);
        assertThat(deliveries.findById(delivery.id()).orElseThrow().arrivedAt()).isNull();
        receivedAt.set(NOW.plusSeconds(100));
        assertThat(send("POST", "/api/deliveries/" + delivery.id() + "/arrive", null).statusCode()).isEqualTo(200);
    }

    @Test
    void readsLegacyPlansButRefusesToInventTheirFeatures() throws Exception {
        jdbc.update("UPDATE delivery_route_plans SET optimized_route = jsonb_set(jsonb_set(optimized_route, "
                + "'{segments,0}', (optimized_route #> '{segments,0}') - 'predictionContext'), "
                + "'{segments,1}', (optimized_route #> '{segments,1}') - 'predictionContext') WHERE delivery_id = ?", delivery.id());
        assertThat(routes.findPlan(delivery.id()).orElseThrow().optimizedRoute().segments().getFirst().predictionContext()).isNull();
        assertStatus("/0/entry", event(10), 409);
        assertThat(send("GET", path, null).body()).isEqualTo("[]");
    }

    @Test
    void concurrentDuplicateEntriesCreateOneObservationAndOneVersionIncrement() throws Exception {
        long initialVersion = version();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return send("PUT", path + "/0/entry", event(10)); });
            var second = executor.submit(() -> { start.await(); return send("PUT", path + "/0/entry", event(10)); });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.statusCode()).isEqualTo(200);
            assertThat(b.statusCode()).isEqualTo(200);
            assertThat(a.body()).isEqualTo(b.body());
        }
        assertThat(version()).isEqualTo(initialVersion + 1);
        assertThat(mapper.readTree(send("GET", path, null).body()).size()).isEqualTo(1);
    }

    @Test
    void competingExitsPreserveTheFirstCommittedLabel() throws Exception {
        assertStatus("/0/entry", event(10), 200);
        long initialVersion = version();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return send("PUT", path + "/0/exit", event(70)); });
            var second = executor.submit(() -> { start.await(); return send("PUT", path + "/0/exit", event(80)); });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 409);
            var winner = mapper.readTree(a.statusCode() == 200 ? a.body() : b.body());
            assertThat(mapper.readTree(send("GET", path, null).body()).get(0)).isEqualTo(winner);
        }
        assertThat(version()).isEqualTo(initialVersion + 1);
    }

    @Test
    void rejectsObservationsBeforeDepartureAndForCancelledDeliveries() throws Exception {
        var other = deliveries.create(Delivery.create(UUID.randomUUID(), delivery.origin(), delivery.destination(), NOW));
        var snapshot = routes.findSnapshot(other.id()).orElseThrow();
        plan = routes.save(snapshot, new DeliveryRoutePlan(UUID.randomUUID(), other.id(), NOW, NOW,
                snapshot.version() + 1, plan.optimizedRoute()));
        path = "/api/deliveries/" + other.id() + "/segments";
        assertStatus("/0/entry", event(10), 409);
        deliveries.cancel(other.id(), NOW.plusSeconds(5));
        assertStatus("/0/entry", event(10), 409);
        assertThat(send("GET", path, null).body()).isEqualTo("[]");
    }

    @Test
    void recordsAvailabilityAfterWaitingForTheDeliveryLock() throws Exception {
        assertStatus("/0/entry", event(10), 200);
        var cutoff = receivedAt.get();
        try (var connection = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("SELECT id FROM deliveries WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, delivery.id());
                    statement.executeQuery().close();
                }
                var exit = executor.submit(() -> send("PUT", path + "/0/exit", event(70)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT id FROM deliveries%')", Boolean.class));
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                assertThat(waiting).as("exit must reach the row lock before the clock advances").isTrue();
                receivedAt.set(cutoff.plusSeconds(60));
                connection.commit();
                var result = exit.get(10, TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(result.body()).path("labelAvailableAt").asString()).isEqualTo(receivedAt.get().toString());
            } finally {
                connection.rollback();
            }
        }
        assertThat(export(cutoff).body().lines().count()).isEqualTo(1);
        assertThat(export(receivedAt.get()).body().lines().count()).isEqualTo(2);
    }

    @Test
    void rollsBackTheObservationWhenTheVersionWriteFails() throws Exception {
        jdbc.execute("ALTER TABLE deliveries ADD CONSTRAINT test_observation_version CHECK (version = 4) NOT VALID");
        try {
            assertStatus("/0/entry", event(10), 500);
            assertThat(version()).isEqualTo(4);
            assertThat(send("GET", path, null).body()).isEqualTo("[]");
        } finally {
            jdbc.execute("ALTER TABLE deliveries DROP CONSTRAINT test_observation_version");
        }
    }

    @Test
    void databaseRejectsIncompleteLabelsAndDuplicateSequence() throws Exception {
        assertStatus("/0/entry", event(10), 200);
        assertThatThrownBy(() -> jdbc.update("UPDATE delivery_segment_observations SET label_available_at = now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO delivery_segment_observations "
                + "SELECT gen_random_uuid(), delivery_id, route_plan_id, sequence, data_origin, entered_at, "
                + "entry_recorded_at, exited_at, label_available_at, prediction_snapshot FROM delivery_segment_observations"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_segment_observations", Integer.class)).isEqualTo(1);
    }

    private RouteSegment segment(String from, String to) {
        var local = NOW.atZone(ZoneId.of("America/Sao_Paulo"));
        return new RouteSegment(from + "-" + to, 1, 2, new SegmentPredictionContext("segment-features-v1", from, to,
                "primary", 30, "low", local.getHour(), local.getDayOfWeek().getValue() - 1, "America/Sao_Paulo",
                "synthetic-traffic-v1", NOW, NOW, NOW));
    }

    private long version() {
        return routes.findSnapshot(delivery.id()).orElseThrow().version();
    }

    private String event(int seconds) {
        return "{\"routePlanId\":\"%s\",\"occurredAt\":\"%s\",\"dataOrigin\":\"simulated\"}"
                .formatted(plan.id(), NOW.plusSeconds(seconds));
    }

    private void assertStatus(String suffix, String body, int expected) throws Exception {
        var response = send("PUT", path + suffix, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private HttpResponse<String> export(Instant cutoff) throws Exception {
        return send("GET", path + "/export?availableAtCutoff=" + encode(cutoff.toString()), null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
