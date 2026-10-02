package com.victhor.delivery.order.domain;

import java.util.Objects;
import java.util.UUID;

public record DeliveryRequest(UUID orderId, DeliveryDestination origin, DeliveryDestination destination) {

    public DeliveryRequest {
        Objects.requireNonNull(orderId, "Order id is required");
        Objects.requireNonNull(origin, "Pickup location is required");
        Objects.requireNonNull(destination, "Destination is required");
    }
}
