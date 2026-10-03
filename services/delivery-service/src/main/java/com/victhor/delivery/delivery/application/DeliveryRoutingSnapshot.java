package com.victhor.delivery.delivery.application;

import java.util.Objects;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.DeliveryStatus;

public record DeliveryRoutingSnapshot(Delivery delivery, long version) {

    public DeliveryRoutingSnapshot {
        Objects.requireNonNull(delivery, "Delivery is required");
        if (version < 0) {
            throw new IllegalArgumentException("Invalid delivery version");
        }
    }

    public void requirePlannable() {
        if (delivery.status() != DeliveryStatus.CREATED && delivery.status() != DeliveryStatus.ASSIGNED
                && delivery.status() != DeliveryStatus.PICKED_UP) {
            throw new DeliveryStateConflictException("Routes can only be planned before departure");
        }
    }
}
