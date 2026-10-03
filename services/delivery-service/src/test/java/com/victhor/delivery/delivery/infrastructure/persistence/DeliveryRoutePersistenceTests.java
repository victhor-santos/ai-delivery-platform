package com.victhor.delivery.delivery.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.application.DeliveryRoutePlan;
import com.victhor.delivery.delivery.application.DeliveryRouteRepository;
import com.victhor.delivery.delivery.application.DeliveryRoutingSnapshot;
import com.victhor.delivery.delivery.application.OptimizedRoute;
import com.victhor.delivery.delivery.application.RouteSegment;
import com.victhor.delivery.delivery.application.StaleRoutePlanException;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "DELIVERY_DB_PASSWORD=testcontainers-only")
@Testcontainers
class DeliveryRoutePersistenceTests {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final GeoPoint ORIGIN = new GeoPoint(-23.5505, -46.6333);
    private static final GeoPoint DESTINATION = new GeoPoint(-23.561, -46.656);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DeliveryRouteRepository routes;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private JdbcTemplate jdbc;

    private Delivery delivery;

    @BeforeEach
    void prepareDelivery() {
        jdbc.update("DELETE FROM deliveries");
        delivery = deliveries.create(Delivery.create(UUID.randomUUID(), new DeliveryLocation("Restaurant", ORIGIN),
                new DeliveryLocation("Destination", DESTINATION), NOW));
    }

    @Test
    void persistsCompletePlanAndReplacesItWithoutChangingLifecycleTimestamps() {
        assertThat(routes.findPlan(delivery.id())).isEmpty();
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var first = plan(snapshot);
        assertThat(routes.save(snapshot, first)).isEqualTo(first);
        assertThat(routes.findPlan(delivery.id())).contains(first);
        var nextSnapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        assertThat(nextSnapshot.version()).isEqualTo(snapshot.version() + 1);
        var next = plan(nextSnapshot);
        routes.save(nextSnapshot, next);
        assertThat(routes.findPlan(delivery.id())).contains(next);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_route_plans", Integer.class)).isEqualTo(1);
        assertThat(deliveries.findById(delivery.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(delivery);
    }

    @Test
    void rejectsStaleResponseAfterStateTransitionAndPreservesPreviousPlan() {
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var previous = routes.save(snapshot, plan(snapshot));
        var stale = routes.findSnapshot(delivery.id()).orElseThrow();
        deliveries.cancel(delivery.id(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> routes.save(stale, plan(stale))).isInstanceOf(StaleRoutePlanException.class);
        assertThat(routes.findPlan(delivery.id())).contains(previous);
        assertThatThrownBy(() -> routes.save(routes.findSnapshot(delivery.id()).orElseThrow(),
                plan(routes.findSnapshot(delivery.id()).orElseThrow()))).isInstanceOf(DeliveryStateConflictException.class);
    }

    @Test
    void onlyOneConcurrentPlanCanCommitFromTheSameSnapshot() throws Exception {
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var first = plan(snapshot);
        var second = plan(snapshot);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(executor.submit(saveAfter(start, snapshot, first)),
                    executor.submit(saveAfter(start, snapshot, second)));
            start.countDown();
            int saved = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    saved++;
                }
            }
            assertThat(saved).isEqualTo(1);
        }
        assertThat(routes.findPlan(delivery.id()).orElseThrow()).isIn(first, second);
        assertThat(routes.findSnapshot(delivery.id()).orElseThrow().version()).isEqualTo(snapshot.version() + 1);
    }

    @Test
    void rollsBackVersionIncrementIfPlanWriteFails() {
        var firstSnapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var previous = routes.save(firstSnapshot, plan(firstSnapshot));
        var second = deliveries.create(Delivery.create(UUID.randomUUID(), delivery.origin(), delivery.destination(), NOW));
        var secondSnapshot = routes.findSnapshot(second.id()).orElseThrow();
        var duplicateId = new DeliveryRoutePlan(previous.id(), second.id(), NOW, NOW, 1, previous.optimizedRoute());
        assertThatThrownBy(() -> routes.save(secondSnapshot, duplicateId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(routes.findSnapshot(second.id()).orElseThrow().version()).isZero();
        assertThat(routes.findPlan(second.id())).isEmpty();
        assertThat(routes.findPlan(delivery.id())).contains(previous);
    }

    @Test
    void preservesNanosecondPredictionAndMicrosecondPlanningTimestampsAcrossJsonStorage() {
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var route = new OptimizedRoute(List.of(ORIGIN, DESTINATION), List.of(new RouteSegment("A-C", 2.9, 16)),
                2.9, 16, NOW.plusNanos(123456789), NOW, "model-v1", "graph-v1", "synthetic");
        var plan = new DeliveryRoutePlan(UUID.randomUUID(), delivery.id(), NOW.plusNanos(123456789),
                NOW.plusNanos(987654321), 1, route);
        routes.save(snapshot, plan);
        assertThat(routes.findPlan(delivery.id())).contains(plan);
        assertThat(plan.departureAt().getNano()).isEqualTo(123456000);
        assertThat(plan.plannedAt().getNano()).isEqualTo(987654000);
        assertThat(plan.optimizedRoute().predictedAt().getNano()).isEqualTo(123456789);
    }

    @Test
    void rejectsMismatchedSnapshotAndEndpointBeforeWriting() {
        var snapshot = routes.findSnapshot(delivery.id()).orElseThrow();
        var wrongDelivery = new DeliveryRoutePlan(UUID.randomUUID(), UUID.randomUUID(), NOW, NOW, 1, plan(snapshot).optimizedRoute());
        assertThatThrownBy(() -> routes.save(snapshot, wrongDelivery)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        var unrelated = new OptimizedRoute(List.of(new GeoPoint(0, 0), DESTINATION),
                List.of(new RouteSegment("A-C", 2.9, 16)), 2.9, 16, NOW, NOW, "model-v1", "graph-v1", "synthetic");
        var wrongEndpoint = new DeliveryRoutePlan(UUID.randomUUID(), delivery.id(), NOW, NOW, 1, unrelated);
        assertThatThrownBy(() -> routes.save(snapshot, wrongEndpoint)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(routes.findPlan(delivery.id())).isEmpty();
        assertThat(routes.findSnapshot(delivery.id()).orElseThrow().version()).isZero();
        assertThat(routes.findSnapshot(UUID.randomUUID())).isEmpty();
    }

    private Callable<Boolean> saveAfter(CountDownLatch start, DeliveryRoutingSnapshot snapshot, DeliveryRoutePlan plan) {
        return () -> {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start");
            }
            try {
                routes.save(snapshot, plan);
                return true;
            } catch (StaleRoutePlanException exception) {
                return false;
            }
        };
    }

    private DeliveryRoutePlan plan(DeliveryRoutingSnapshot snapshot) {
        var route = new OptimizedRoute(List.of(ORIGIN, DESTINATION), List.of(new RouteSegment("A-C", 2.9, 16)),
                2.9, 16, NOW, NOW, "model-v1", "graph-v1", "synthetic");
        return new DeliveryRoutePlan(UUID.randomUUID(), snapshot.delivery().id(), NOW, NOW,
                snapshot.version() + 1, route);
    }
}
