package com.victhor.delivery.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Delivery {

    private final UUID id;
    private final UUID orderId;
    private final DeliveryLocation origin;
    private final DeliveryLocation destination;
    private final Instant createdAt;
    private UUID courierId;
    private DeliveryStatus status;
    private Instant updatedAt;
    private Instant assignedAt;
    private Instant pickedUpAt;
    private Instant departedAt;
    private Instant arrivedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    private Delivery(UUID id, UUID orderId, DeliveryLocation origin, DeliveryLocation destination, Instant now) {
        this.id = Objects.requireNonNull(id, "Delivery id is required");
        this.orderId = Objects.requireNonNull(orderId, "Order id is required");
        this.origin = Objects.requireNonNull(origin, "Origin is required");
        this.destination = Objects.requireNonNull(destination, "Destination is required");
        this.createdAt = Objects.requireNonNull(now, "Creation time is required");
        this.updatedAt = now;
        this.status = DeliveryStatus.CREATED;
    }

    public static Delivery create(UUID orderId, DeliveryLocation origin, DeliveryLocation destination, Instant now) {
        return new Delivery(UUID.randomUUID(), orderId, origin, destination, now);
    }

    public static Delivery restore(UUID id, UUID orderId, DeliveryLocation origin, DeliveryLocation destination,
            UUID courierId, DeliveryStatus status, Instant createdAt, Instant updatedAt, Instant assignedAt,
            Instant pickedUpAt, Instant departedAt, Instant arrivedAt, Instant deliveredAt, Instant cancelledAt) {
        Objects.requireNonNull(status, "Delivery status is required");
        Objects.requireNonNull(updatedAt, "Update time is required");
        if ((courierId == null) != (assignedAt == null)) {
            throw new IllegalArgumentException("Courier and assignment time must be present together");
        }
        Delivery delivery = new Delivery(id, orderId, origin, destination, createdAt);
        if (assignedAt != null) {
            delivery.assign(new Courier(courierId, true), assignedAt);
        }
        if (pickedUpAt != null) {
            delivery.pickUp(pickedUpAt);
        }
        if (departedAt != null) {
            delivery.startTransit(departedAt);
        }
        if (arrivedAt != null) {
            delivery.arrive(arrivedAt);
        }
        if (deliveredAt != null) {
            delivery.complete(deliveredAt);
        }
        if (cancelledAt != null) {
            delivery.cancel(cancelledAt);
        }
        if (delivery.status != status || !delivery.updatedAt.equals(updatedAt)) {
            throw new IllegalArgumentException("Delivery state does not match its event history");
        }
        return delivery;
    }

    public void assign(Courier courier, Instant now) {
        requireStatus(DeliveryStatus.CREATED);
        Objects.requireNonNull(courier, "Courier is required");
        if (!courier.active()) {
            throw new DeliveryStateConflictException("An inactive courier cannot receive a delivery");
        }
        requireTime(now);
        courierId = courier.id();
        assignedAt = now;
        updatedAt = now;
        status = DeliveryStatus.ASSIGNED;
    }

    public void pickUp(Instant now) {
        requireStatus(DeliveryStatus.ASSIGNED);
        requireTime(now);
        pickedUpAt = now;
        updatedAt = now;
        status = DeliveryStatus.PICKED_UP;
    }

    public void startTransit(Instant now) {
        requireStatus(DeliveryStatus.PICKED_UP);
        requireTime(now);
        departedAt = now;
        updatedAt = now;
        status = DeliveryStatus.IN_TRANSIT;
    }

    public void arrive(Instant now) {
        requireStatus(DeliveryStatus.IN_TRANSIT);
        if (arrivedAt != null) {
            throw new DeliveryStateConflictException("Arrival has already been recorded");
        }
        requireTime(now);
        arrivedAt = now;
        updatedAt = now;
    }

    public void complete(Instant now) {
        requireStatus(DeliveryStatus.IN_TRANSIT);
        if (arrivedAt == null) {
            throw new DeliveryStateConflictException("Arrival must be recorded before completing the delivery");
        }
        requireTime(now);
        deliveredAt = now;
        updatedAt = now;
        status = DeliveryStatus.DELIVERED;
    }

    public void cancel(Instant now) {
        if (status != DeliveryStatus.CREATED && status != DeliveryStatus.ASSIGNED) {
            throw new DeliveryStateConflictException("A delivery can only be cancelled before pickup");
        }
        requireTime(now);
        cancelledAt = now;
        updatedAt = now;
        status = DeliveryStatus.CANCELLED;
    }

    private void requireStatus(DeliveryStatus expected) {
        if (status != expected) {
            throw new DeliveryStateConflictException("Expected delivery status " + expected + " but was " + status);
        }
    }

    private void requireTime(Instant now) {
        Objects.requireNonNull(now, "Event time is required");
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Event time cannot precede the latest delivery event");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public DeliveryLocation origin() {
        return origin;
    }

    public DeliveryLocation destination() {
        return destination;
    }

    public UUID courierId() {
        return courierId;
    }

    public DeliveryStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public Instant pickedUpAt() {
        return pickedUpAt;
    }

    public Instant departedAt() {
        return departedAt;
    }

    public Instant arrivedAt() {
        return arrivedAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }
}
