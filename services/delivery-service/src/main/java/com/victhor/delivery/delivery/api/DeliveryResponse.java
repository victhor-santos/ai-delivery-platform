package com.victhor.delivery.delivery.api;

import java.time.Instant;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.Delivery;

public record DeliveryResponse(UUID id, UUID orderId, DeliveryLocationResponse origin,
        DeliveryLocationResponse destination, UUID courierId, String status, Instant createdAt, Instant updatedAt,
        Instant assignedAt, Instant pickedUpAt, Instant departedAt, Instant arrivedAt,
        Instant deliveredAt, Instant cancelledAt) {

    static DeliveryResponse from(Delivery delivery) {
        return new DeliveryResponse(delivery.id(), delivery.orderId(), DeliveryLocationResponse.from(delivery.origin()),
                DeliveryLocationResponse.from(delivery.destination()), delivery.courierId(), delivery.status().name(),
                delivery.createdAt(), delivery.updatedAt(), delivery.assignedAt(), delivery.pickedUpAt(),
                delivery.departedAt(), delivery.arrivedAt(), delivery.deliveredAt(), delivery.cancelledAt());
    }
}
