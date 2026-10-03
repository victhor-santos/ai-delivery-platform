package com.victhor.delivery.delivery.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.delivery.application.CourierNotFoundException;
import com.victhor.delivery.delivery.application.CourierRepository;
import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.domain.Courier;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.DeliveryStatus;
import com.victhor.delivery.delivery.domain.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "DELIVERY_DB_PASSWORD=testcontainers-only")
@Testcontainers
class DeliveryPersistenceTests {

    private static final Instant TIME = Instant.parse("2026-10-01T10:00:00Z");
    private static final DeliveryLocation ORIGIN = new DeliveryLocation("Restaurante Central", new GeoPoint(-23.55, -46.63));
    private static final DeliveryLocation DESTINATION = new DeliveryLocation("Rua Central, 42", new GeoPoint(-23.56, -46.64));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DeliveryRepository deliveries;

    @Autowired
    private CourierRepository couriers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagers;

    @Autowired
    private Environment environment;

    private Courier courier;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM deliveries");
        jdbc.update("DELETE FROM couriers");
        courier = couriers.create(new Courier(UUID.randomUUID(), true));
    }

    @Test
    void createsAndFindsWithIndependentSnapshotsAndMigratedSchema() {
        Delivery original = Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, TIME);
        Delivery saved = deliveries.create(original);
        assertThat(saved).usingRecursiveComparison().isEqualTo(original);
        assertThat(deliveries.findById(saved.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        assertThat(deliveries.findByOrderId(saved.orderId()).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        assertThat(couriers.findById(courier.id())).contains(courier);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject("SELECT destination_description FROM deliveries WHERE id = ?", String.class, saved.id()))
                .isEqualTo(DESTINATION.description());
    }

    @Test
    void persistsEveryTransitionAndPreservesEventHistory() {
        Delivery original = createDelivery();
        UUID id = original.id();
        assertThat(deliveries.assign(id, courier.id(), TIME.plusSeconds(1)).orElseThrow().status()).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(deliveries.pickUp(id, TIME.plusSeconds(2)).orElseThrow().status()).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(deliveries.startTransit(id, TIME.plusSeconds(3)).orElseThrow().status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        assertThat(deliveries.arrive(id, TIME.plusSeconds(4)).orElseThrow().arrivedAt()).isEqualTo(TIME.plusSeconds(4));
        Delivery completed = deliveries.complete(id, TIME.plusSeconds(5)).orElseThrow();
        Delivery found = deliveries.findById(id).orElseThrow();

        assertThat(found).usingRecursiveComparison().isEqualTo(completed);
        assertThat(found.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(found.createdAt()).isEqualTo(TIME);
        assertThat(found.assignedAt()).isEqualTo(TIME.plusSeconds(1));
        assertThat(found.pickedUpAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(found.departedAt()).isEqualTo(TIME.plusSeconds(3));
        assertThat(found.arrivedAt()).isEqualTo(TIME.plusSeconds(4));
        assertThat(found.deliveredAt()).isEqualTo(TIME.plusSeconds(5));
        assertThat(found.updatedAt()).isEqualTo(found.deliveredAt());
        assertThat(found.origin()).isEqualTo(ORIGIN);
        assertThat(found.destination()).isEqualTo(DESTINATION);
        assertThat(found.courierId()).isEqualTo(courier.id());
        assertThat(found.cancelledAt()).isNull();

        assertThat(deliveries.assign(createDelivery().id(), courier.id(), TIME.plusSeconds(6))).isPresent();
        assertThatThrownBy(() -> deliveries.cancel(id, TIME.plusSeconds(6))).isInstanceOf(DeliveryStateConflictException.class);
        assertThat(deliveries.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(found);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistsCancellationAndReleasesCourier(boolean assigned) {
        UUID id = createDelivery().id();
        if (assigned) {
            deliveries.assign(id, courier.id(), TIME.plusSeconds(1));
        }
        Delivery cancelled = deliveries.cancel(id, TIME.plusSeconds(2)).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isEqualTo(TIME.plusSeconds(2));
        assertThat(cancelled.courierId()).isEqualTo(assigned ? courier.id() : null);
        assertThat(cancelled.assignedAt()).isEqualTo(assigned ? TIME.plusSeconds(1) : null);
        assertThat(deliveries.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(cancelled);
        assertThat(deliveries.assign(createDelivery().id(), courier.id(), TIME.plusSeconds(3))).isPresent();
    }

    @Test
    void normalizesTimestampPrecisionBeforeReturningPersistedState() {
        Delivery saved = deliveries.create(Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, TIME.plusNanos(123456789)));
        assertThat(saved.createdAt()).isEqualTo(TIME.plusNanos(123456000));
        Delivery assigned = deliveries.assign(saved.id(), courier.id(), TIME.plusSeconds(1).plusNanos(987654321)).orElseThrow();
        assertThat(assigned.assignedAt()).isEqualTo(TIME.plusSeconds(1).plusNanos(987654000));
        assertThat(deliveries.findById(saved.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(assigned);
    }

    @Test
    void rejectsMissingInactiveAndDuplicateCouriersWithoutChangingDelivery() {
        Delivery delivery = createDelivery();
        Courier inactive = couriers.create(new Courier(UUID.randomUUID(), false));
        assertThatThrownBy(() -> deliveries.assign(delivery.id(), UUID.randomUUID(), TIME)).isInstanceOf(CourierNotFoundException.class);
        assertThatThrownBy(() -> deliveries.assign(delivery.id(), inactive.id(), TIME)).isInstanceOf(DeliveryStateConflictException.class);
        assertThatThrownBy(() -> couriers.create(new Courier(courier.id(), false))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(couriers.findById(courier.id())).contains(courier);
        assertThat(deliveries.findById(delivery.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(delivery);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"ASSIGNED", "PICKED_UP", "IN_TRANSIT"})
    void forbidsAssigningCourierWithAnOngoingDelivery(DeliveryStatus status) {
        UUID first = createDelivery().id();
        deliveries.assign(first, courier.id(), TIME);
        if (status != DeliveryStatus.ASSIGNED) {
            deliveries.pickUp(first, TIME);
        }
        if (status == DeliveryStatus.IN_TRANSIT) {
            deliveries.startTransit(first, TIME);
        }
        Delivery second = createDelivery();
        assertThatThrownBy(() -> deliveries.assign(second.id(), courier.id(), TIME)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries.findById(second.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(second);
    }

    @Test
    void concurrentAssignmentsCannotReserveTheSameCourier() throws Exception {
        UUID first = createDelivery().id();
        UUID second = createDelivery().id();
        List<Boolean> results = race(() -> deliveries.assign(first, courier.id(), TIME),
                () -> deliveries.assign(second, courier.id(), TIME));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE courier_id = ?", Integer.class, courier.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE status = 'CREATED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCreationCannotDuplicateAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        List<Boolean> results = race(() -> deliveries.create(Delivery.create(orderId, ORIGIN, DESTINATION, TIME)),
                () -> deliveries.create(Delivery.create(orderId, ORIGIN, DESTINATION, TIME)));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void cancelledDeliveryStillReservesItsOrder() {
        Delivery original = createDelivery();
        Delivery cancelled = deliveries.cancel(original.id(), TIME.plusSeconds(1)).orElseThrow();

        assertThatThrownBy(() -> deliveries.create(Delivery.create(original.orderId(), ORIGIN, DESTINATION, TIME)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries.findByOrderId(original.orderId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(cancelled);
    }

    @Test
    void rejectedTransitionsPreservePersistedStateAndVersion() {
        UUID id = createDelivery().id();
        Delivery assigned = deliveries.assign(id, courier.id(), TIME.plusSeconds(1)).orElseThrow();
        Long version = jdbc.queryForObject("SELECT version FROM deliveries WHERE id = ?", Long.class, id);

        assertThatThrownBy(() -> deliveries.pickUp(id, TIME))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveries.complete(id, TIME.plusSeconds(2)))
                .isInstanceOf(DeliveryStateConflictException.class);
        assertThat(deliveries.findById(id).orElseThrow()).usingRecursiveComparison().isEqualTo(assigned);
        assertThat(jdbc.queryForObject("SELECT version FROM deliveries WHERE id = ?", Long.class, id)).isEqualTo(version);
    }

    @Test
    void rejectsStaleUpdatesInsteadOfOverwritingCommittedState() {
        UUID id = createDelivery().id();
        try (var first = entityManagers.createEntityManager(); var second = entityManagers.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            try {
                var firstEntity = first.find(DeliveryEntity.class, id);
                var secondEntity = second.find(DeliveryEntity.class, id);
                Delivery assigned = firstEntity.toDomain();
                assigned.assign(courier, TIME);
                firstEntity.applyState(assigned);
                first.getTransaction().commit();
                Delivery cancelled = secondEntity.toDomain();
                cancelled.cancel(TIME);
                secondEntity.applyState(cancelled);
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            } finally {
                if (first.getTransaction().isActive()) {
                    first.getTransaction().rollback();
                }
                if (second.getTransaction().isActive()) {
                    second.getTransaction().rollback();
                }
            }
        }
        assertThat(deliveries.findById(id).orElseThrow().status()).isEqualTo(DeliveryStatus.ASSIGNED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "order_id = NULL", "origin_description = ' '", "destination_description = ' '",
            "origin_latitude = 91", "origin_longitude = -181", "destination_latitude = 'NaN'", "destination_longitude = 'Infinity'",
            "status = 'UNKNOWN'", "status = 'ASSIGNED'", "status = 'PICKED_UP'", "status = 'IN_TRANSIT'",
            "status = 'DELIVERED'", "status = 'CANCELLED'", "assigned_at = created_at", "picked_up_at = created_at",
            "departed_at = created_at", "arrived_at = created_at", "delivered_at = created_at", "cancelled_at = created_at",
            "updated_at = created_at - INTERVAL '1 second'",
            "status = 'ASSIGNED', assigned_at = created_at, courier_id = '00000000-0000-0000-0000-000000000000'"
    })
    void databaseRejectsInvalidStateWrittenOutsideTheAdapter(String assignment) {
        Delivery original = createDelivery();
        assertThatThrownBy(() -> jdbc.update("UPDATE deliveries SET " + assignment + " WHERE id = ?", original.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deliveries.findById(original.id()).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void absentDeliveryRemainsAbsentForEveryOperation() {
        UUID id = UUID.randomUUID();
        assertThat(deliveries.findById(id)).isEmpty();
        assertThat(deliveries.findByOrderId(id)).isEmpty();
        assertThat(couriers.findById(id)).isEmpty();
        assertThat(deliveries.assign(id, courier.id(), TIME)).isEmpty();
        assertThat(deliveries.pickUp(id, TIME)).isEmpty();
        assertThat(deliveries.startTransit(id, TIME)).isEmpty();
        assertThat(deliveries.arrive(id, TIME)).isEmpty();
        assertThat(deliveries.complete(id, TIME)).isEmpty();
        assertThat(deliveries.cancel(id, TIME)).isEmpty();
    }

    private Delivery createDelivery() {
        return deliveries.create(Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, TIME));
    }

    private List<Boolean> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> attempt(first, ready, start));
            var secondResult = executor.submit(() -> attempt(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean attempt(Callable<?> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }
        try {
            operation.call();
            return true;
        } catch (DataIntegrityViolationException expected) {
            return false;
        }
    }
}
