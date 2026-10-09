package com.victhor.delivery.delivery.application;

import java.util.Objects;
import java.util.UUID;

/** Who is asking: operators see every delivery, customers only the ones requested for their orders. */
public record DeliveryViewer(UUID userId, boolean operator) {

    public DeliveryViewer {
        Objects.requireNonNull(userId, "Viewer id is required");
    }
}
