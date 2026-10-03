package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.SegmentTraversal;

public class SegmentObservationService {

    private final SegmentObservationRepository observations;
    private final Clock clock;

    public SegmentObservationService(SegmentObservationRepository observations, Clock clock) {
        this.observations = observations;
        this.clock = clock;
    }

    public SegmentObservation enter(UUID deliveryId, int sequence, TraversalEvent event) {
        validateEvent(event);
        return observations.enter(deliveryId, sequence, event);
    }

    public SegmentObservation exit(UUID deliveryId, int sequence, TraversalEvent event) {
        validateEvent(event);
        return observations.exit(deliveryId, sequence, event);
    }

    public List<SegmentObservation> find(UUID deliveryId) {
        return observations.findByDelivery(deliveryId);
    }

    public List<SegmentObservation> export(UUID deliveryId, Instant cutoff) {
        Instant normalized = SegmentTraversal.timestamp(cutoff);
        if (normalized.isAfter(now())) {
            throw new IllegalArgumentException("Availability cutoff cannot be in the future");
        }
        return observations.findByDelivery(deliveryId).stream()
                .filter(row -> row.traversal().labelAvailableAt() != null
                        && !row.traversal().labelAvailableAt().isAfter(normalized)
                        && !row.prediction().predictionAt().isAfter(normalized)
                        && !row.prediction().segment().predictionContext().featuresAvailableAt().isAfter(normalized))
                .toList();
    }

    private void validateEvent(TraversalEvent event) {
        if (event.occurredAt().isAfter(now())) {
            throw new IllegalArgumentException("Future observations are not supported");
        }
    }

    private Instant now() {
        return SegmentTraversal.timestamp(clock.instant());
    }
}
