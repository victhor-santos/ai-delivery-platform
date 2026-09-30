package com.victhor.delivery.order.api;

import java.time.Instant;
import java.util.UUID;

import com.victhor.delivery.order.domain.Order;

public record OrderResponse(UUID id, UUID restaurantId, DestinationResponse destination, String status,
        Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt) {

    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.restaurantId(), DestinationResponse.from(order.destination()),
                order.status().name(), order.createdAt(), order.updatedAt(), order.confirmedAt(), order.cancelledAt());
    }
}
