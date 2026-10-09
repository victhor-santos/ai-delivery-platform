package com.victhor.delivery.delivery.infrastructure.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.delivery.application.CourierNotFoundException;
import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.application.DeliveryCreation;
import com.victhor.delivery.delivery.application.DeliveryPage;
import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryStatus;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;

@Repository
@Transactional(readOnly = true)
public class JpaDeliveryRepository implements DeliveryRepository {

    private final SpringDataDeliveryRepository deliveries;
    private final SpringDataCourierRepository couriers;
    private final JdbcTemplate jdbc;

    public JpaDeliveryRepository(SpringDataDeliveryRepository deliveries, SpringDataCourierRepository couriers,
            JdbcTemplate jdbc) {
        this.deliveries = deliveries;
        this.couriers = couriers;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Delivery create(Delivery delivery) {
        return deliveries.save(DeliveryEntity.fromDomain(delivery)).toDomain();
    }

    @Override
    @Transactional
    public DeliveryCreation createForOrder(Delivery delivery, UUID customerId) {
        if (delivery.status() != DeliveryStatus.CREATED) {
            throw new IllegalArgumentException("Only a new delivery can be created for an order");
        }
        Timestamp createdAt = Timestamp.from(delivery.createdAt().truncatedTo(ChronoUnit.MICROS));
        int inserted = jdbc.update("""
                INSERT INTO deliveries (id, order_id, customer_id, origin_description, origin_latitude, origin_longitude,
                    destination_description, destination_latitude, destination_longitude,
                    status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, 0)
                ON CONFLICT (order_id) DO NOTHING
                """, delivery.id(), delivery.orderId(), customerId, delivery.origin().description(),
                delivery.origin().point().latitude(), delivery.origin().point().longitude(),
                delivery.destination().description(), delivery.destination().point().latitude(),
                delivery.destination().point().longitude(), createdAt, createdAt);
        if (inserted == 0 && customerId != null) {
            // Deliveries created before the customer was sent adopt it; a different customer is a conflict below.
            jdbc.update("UPDATE deliveries SET customer_id = ? WHERE order_id = ? AND customer_id IS NULL",
                    customerId, delivery.orderId());
        }
        var entity = deliveries.findByOrderId(delivery.orderId()).orElseThrow();
        Delivery stored = entity.toDomain();
        if (!stored.origin().equals(delivery.origin()) || !stored.destination().equals(delivery.destination())) {
            throw new DeliveryStateConflictException("The order already has a delivery with different locations");
        }
        if (customerId != null && !customerId.equals(entity.customerId())) {
            throw new DeliveryStateConflictException("The order already has a delivery for another customer");
        }
        return new DeliveryCreation(stored, inserted == 1);
    }

    @Override
    public Optional<Delivery> findById(UUID id) {
        return deliveries.findById(id).map(DeliveryEntity::toDomain);
    }

    @Override
    public Optional<Delivery> findByOrderId(UUID orderId) {
        return deliveries.findByOrderId(orderId).map(DeliveryEntity::toDomain);
    }

    @Override
    public boolean isOwnedBy(UUID id, UUID customerId) {
        return deliveries.existsByIdAndCustomerId(id, customerId);
    }

    @Override
    public DeliveryPage findPage(DeliveryStatus status, int page, int size) {
        var request = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        var result = status == null ? deliveries.findAll(request) : deliveries.findByStatus(status, request);
        return new DeliveryPage(result.map(DeliveryEntity::toDomain).getContent(), page, size, result.getTotalElements());
    }

    @Override
    @Transactional
    public Optional<Delivery> assign(UUID id, UUID courierId, Instant now) {
        return update(id, delivery -> {
            var courier = couriers.findById(courierId)
                    .orElseThrow(CourierNotFoundException::new).toDomain();
            delivery.assign(courier, now);
        });
    }

    @Override
    @Transactional
    public Optional<Delivery> pickUp(UUID id, Instant now) {
        return update(id, delivery -> delivery.pickUp(now));
    }

    @Override
    @Transactional
    public Optional<Delivery> startTransit(UUID id, Instant now) {
        return update(id, delivery -> delivery.startTransit(now));
    }

    @Override
    @Transactional
    public Optional<Delivery> arrive(UUID id, Instant now) {
        return update(id, delivery -> {
            Timestamp latestTraversal = jdbc.queryForObject("""
                    SELECT max(COALESCE(exited_at, entered_at)) FROM delivery_segment_observations WHERE delivery_id = ?
                    """, Timestamp.class, id);
            if (latestTraversal != null && latestTraversal.toInstant().isAfter(now)) {
                throw new DeliveryStateConflictException("Arrival cannot precede an observed traversal");
            }
            delivery.arrive(now);
        });
    }

    @Override
    @Transactional
    public Optional<Delivery> complete(UUID id, Instant now) {
        return update(id, delivery -> delivery.complete(now));
    }

    @Override
    @Transactional
    public Optional<Delivery> cancel(UUID id, Instant now) {
        return update(id, delivery -> delivery.cancel(now));
    }

    private Optional<Delivery> update(UUID id, Consumer<Delivery> transition) {
        return deliveries.findById(id).map(entity -> {
            Delivery delivery = entity.toDomain();
            transition.accept(delivery);
            entity.applyState(delivery);
            return entity.toDomain();
        });
    }
}
