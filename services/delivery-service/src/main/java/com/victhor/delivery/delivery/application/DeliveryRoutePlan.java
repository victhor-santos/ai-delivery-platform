package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record DeliveryRoutePlan(UUID id, UUID deliveryId, Instant departureAt, Instant plannedAt,
        long deliveryVersion, OptimizedRoute optimizedRoute) {

    public DeliveryRoutePlan {
        Objects.requireNonNull(id, "Plan id is required");
        Objects.requireNonNull(deliveryId, "Delivery id is required");
        Objects.requireNonNull(plannedAt, "Planning time is required");
        Objects.requireNonNull(optimizedRoute, "Optimized route is required");
        departureAt = new RouteContext(departureAt).departureAt();
        plannedAt = plannedAt.truncatedTo(ChronoUnit.MICROS);
        if (deliveryVersion <= 0) {
            throw new IllegalArgumentException("Plan must reference the committed delivery version");
        }
    }
}
