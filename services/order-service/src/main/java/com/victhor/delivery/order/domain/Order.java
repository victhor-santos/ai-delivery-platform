package com.victhor.delivery.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
        Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt) {

    public Order {
        Objects.requireNonNull(id, "Order id is required");
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        Objects.requireNonNull(destination, "Destination is required");
        Objects.requireNonNull(status, "Order status is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
        Objects.requireNonNull(updatedAt, "Update time is required");
        if (updatedAt.isBefore(createdAt)
                || (confirmedAt != null && confirmedAt.isBefore(createdAt))
                || (cancelledAt != null && cancelledAt.isBefore(confirmedAt == null ? createdAt : confirmedAt))) {
            throw new IllegalArgumentException("Order timestamps must follow the lifecycle sequence");
        }
        boolean consistent = switch (status) {
            case CREATED -> confirmedAt == null && cancelledAt == null && updatedAt.equals(createdAt);
            case CONFIRMED -> confirmedAt != null && cancelledAt == null && updatedAt.equals(confirmedAt);
            case CANCELLED -> cancelledAt != null && updatedAt.equals(cancelledAt);
        };
        if (!consistent) {
            throw new IllegalArgumentException("Order timestamps must match its status");
        }
    }

    public static Order create(UUID restaurantId, DeliveryDestination destination, Instant now) {
        return new Order(UUID.randomUUID(), restaurantId, destination, OrderStatus.CREATED, now, now, null, null);
    }

    public Order confirm(Instant now) {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderStateConflictException();
        }
        if (status == OrderStatus.CONFIRMED) {
            return this;
        }
        return new Order(id, restaurantId, destination, OrderStatus.CONFIRMED, createdAt, now, now, null);
    }

    public Order cancel(Instant now) {
        if (status == OrderStatus.CANCELLED) {
            return this;
        }
        return new Order(id, restaurantId, destination, OrderStatus.CANCELLED, createdAt, now, confirmedAt, now);
    }
}
