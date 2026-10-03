package com.victhor.delivery.delivery.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record SegmentTraversal(UUID id, UUID deliveryId, UUID routePlanId, int sequence, String dataOrigin,
        Instant enteredAt, Instant entryRecordedAt, Instant exitedAt, Instant labelAvailableAt) {

    public SegmentTraversal {
        Objects.requireNonNull(id);
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(routePlanId);
        enteredAt = timestamp(enteredAt);
        entryRecordedAt = timestamp(entryRecordedAt);
        if (sequence < 0 || sequence >= 199 || !"simulated".equals(dataOrigin)
                || enteredAt.isAfter(entryRecordedAt) || (exitedAt == null) != (labelAvailableAt == null)) {
            throw new IllegalArgumentException("Invalid traversal identity or entry");
        }
        if (exitedAt != null) {
            exitedAt = timestamp(exitedAt);
            labelAvailableAt = timestamp(labelAvailableAt);
            if (!exitedAt.isAfter(enteredAt) || exitedAt.isAfter(labelAvailableAt)
                    || labelAvailableAt.isBefore(entryRecordedAt)) {
                throw new IllegalArgumentException("Invalid traversal completion");
            }
        }
    }

    public static SegmentTraversal enter(UUID deliveryId, UUID planId, int sequence, String origin,
            Instant enteredAt, Instant recordedAt) {
        return new SegmentTraversal(UUID.randomUUID(), deliveryId, planId, sequence, origin,
                enteredAt, recordedAt, null, null);
    }

    public SegmentTraversal exit(Instant occurredAt, Instant recordedAt) {
        Instant exitTime = timestamp(occurredAt);
        if (exitedAt != null) {
            if (!exitedAt.equals(exitTime)) {
                throw new DeliveryStateConflictException("The traversal already has another exit event");
            }
            return this;
        }
        return new SegmentTraversal(id, deliveryId, routePlanId, sequence, dataOrigin,
                enteredAt, entryRecordedAt, exitTime, recordedAt);
    }

    public boolean matchesEntry(UUID planId, String origin, Instant occurredAt) {
        return routePlanId.equals(planId) && dataOrigin.equals(origin) && enteredAt.equals(timestamp(occurredAt));
    }

    public Double actualTravelTimeMinutes() {
        if (exitedAt == null) {
            return null;
        }
        var elapsed = java.time.Duration.between(enteredAt, exitedAt);
        return elapsed.getSeconds() / 60.0 + elapsed.getNano() / 60_000_000_000.0;
    }

    public static Instant timestamp(Instant value) {
        Objects.requireNonNull(value, "Event timestamp is required");
        int year = value.atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("Event timestamp exceeds the supported calendar");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
