package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.util.List;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.DeliveryStatus;
import com.victhor.delivery.delivery.domain.SegmentTraversal;

public final class SegmentObservationPolicy {

    private SegmentObservationPolicy() {
    }

    public static SegmentObservation enter(Delivery delivery, DeliveryRoutePlan plan,
            List<SegmentObservation> existing, int sequence, TraversalEvent event, Instant recordedAt) {
        requireSequence(sequence);
        var duplicate = existing.stream().filter(row -> row.traversal().sequence() == sequence).findFirst();
        if (duplicate.isPresent()) {
            if (!duplicate.get().traversal().matchesEntry(event.routePlanId(), event.dataOrigin(), event.occurredAt())) {
                throw conflict("The segment already has another entry event");
            }
            return duplicate.get();
        }
        requireJourneyTime(delivery, event.occurredAt(), recordedAt);
        if (!plan.id().equals(event.routePlanId()) || sequence >= plan.optimizedRoute().segments().size()) {
            throw conflict("The event does not reference the saved route segment");
        }
        if (sequence != existing.size() || (sequence > 0 && (existing.getLast().traversal().exitedAt() == null
                || event.occurredAt().isBefore(existing.getLast().traversal().exitedAt())))) {
            throw conflict("Traversal entry does not follow the previous segment exit");
        }
        if (event.occurredAt().isBefore(plan.plannedAt()) || event.occurredAt().isBefore(plan.optimizedRoute().predictedAt())
                || plan.optimizedRoute().segments().get(sequence).predictionContext() == null) {
            throw conflict("A complete prediction snapshot must precede the traversal");
        }
        return new SegmentObservation(SegmentTraversal.enter(delivery.id(), plan.id(), sequence, event.dataOrigin(),
                event.occurredAt(), recordedAt), TraversalPrediction.from(plan, sequence));
    }

    public static SegmentObservation exit(Delivery delivery, List<SegmentObservation> existing,
            int sequence, TraversalEvent event, Instant recordedAt) {
        requireSequence(sequence);
        var row = existing.stream().filter(item -> item.traversal().sequence() == sequence)
                .findFirst().orElseThrow(() -> conflict("Entry must be recorded before exit"));
        var traversal = row.traversal();
        if (!traversal.routePlanId().equals(event.routePlanId()) || !traversal.dataOrigin().equals(event.dataOrigin())) {
            throw conflict("Exit does not match the entry provenance");
        }
        if (traversal.exitedAt() == null) {
            requireJourneyTime(delivery, event.occurredAt(), recordedAt);
        }
        var completed = traversal.exit(event.occurredAt(), recordedAt);
        return completed == traversal ? row : new SegmentObservation(completed, row.prediction());
    }

    private static void requireSequence(int sequence) {
        if (sequence < 0 || sequence >= 199) {
            throw new IllegalArgumentException("Segment sequence must be between 0 and 198");
        }
    }

    private static void requireJourneyTime(Delivery delivery, Instant occurredAt, Instant recordedAt) {
        if (occurredAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("Future observations are not supported");
        }
        if ((delivery.status() != DeliveryStatus.IN_TRANSIT && delivery.status() != DeliveryStatus.DELIVERED)
                || delivery.departedAt() == null || occurredAt.isBefore(delivery.departedAt())
                || (delivery.arrivedAt() != null && occurredAt.isAfter(delivery.arrivedAt()))) {
            throw conflict("Observation does not fall within the delivery journey");
        }
    }

    private static DeliveryStateConflictException conflict(String message) {
        return new DeliveryStateConflictException(message);
    }
}
