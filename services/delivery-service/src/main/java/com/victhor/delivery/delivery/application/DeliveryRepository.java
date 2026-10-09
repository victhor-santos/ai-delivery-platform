package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryStatus;

public interface DeliveryRepository {

    Delivery create(Delivery delivery);

    DeliveryCreation createForOrder(Delivery delivery, UUID customerId);

    Optional<Delivery> findById(UUID id);

    Optional<Delivery> findByOrderId(UUID orderId);

    boolean isOwnedBy(UUID id, UUID customerId);

    /** Newest first; a null status lists every delivery. */
    DeliveryPage findPage(DeliveryStatus status, int page, int size);

    Optional<Delivery> assign(UUID id, UUID courierId, Instant now);

    Optional<Delivery> pickUp(UUID id, Instant now);

    Optional<Delivery> startTransit(UUID id, Instant now);

    Optional<Delivery> arrive(UUID id, Instant now);

    Optional<Delivery> complete(UUID id, Instant now);

    Optional<Delivery> cancel(UUID id, Instant now);
}
