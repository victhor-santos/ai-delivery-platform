package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.SegmentTraversal;

public record TraversalEvent(UUID routePlanId, Instant occurredAt, String dataOrigin) {

    public TraversalEvent {
        Objects.requireNonNull(routePlanId);
        occurredAt = SegmentTraversal.timestamp(occurredAt);
        if (!"simulated".equals(dataOrigin)) {
            throw new IllegalArgumentException("Only explicitly simulated observations are supported");
        }
    }
}
