package com.victhor.delivery.delivery.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.delivery.application.CourierNotFoundException;
import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.domain.Delivery;

@Repository
@Transactional(readOnly = true)
public class JpaDeliveryRepository implements DeliveryRepository {

    private final SpringDataDeliveryRepository deliveries;
    private final SpringDataCourierRepository couriers;

    public JpaDeliveryRepository(SpringDataDeliveryRepository deliveries, SpringDataCourierRepository couriers) {
        this.deliveries = deliveries;
        this.couriers = couriers;
    }

    @Override
    @Transactional
    public Delivery create(Delivery delivery) {
        return deliveries.save(DeliveryEntity.fromDomain(delivery)).toDomain();
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
        return update(id, delivery -> delivery.arrive(now));
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
