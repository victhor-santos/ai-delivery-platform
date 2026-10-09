package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.Delivery;
import com.victhor.delivery.delivery.domain.DeliveryStatus;
import com.victhor.delivery.delivery.domain.DeliveryLocation;

public class DeliveryService {

    private final DeliveryRepository deliveries;
    private final Clock clock;

    public DeliveryService(DeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public Delivery create(UUID orderId, DeliveryLocation origin, DeliveryLocation destination) {
        return deliveries.create(Delivery.create(orderId, origin, destination, now()));
    }

    /** Idempotent by order: repeating the request returns the stored delivery, even after later transitions. */
    public DeliveryCreation createForOrder(UUID orderId, UUID customerId, DeliveryLocation origin,
            DeliveryLocation destination) {
        return deliveries.createForOrder(Delivery.create(orderId, origin, destination, now()), customerId);
    }

    public static final int MAX_PAGE_SIZE = 100;

    public Delivery findById(UUID id) {
        return deliveries.findById(id).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery findById(UUID id, DeliveryViewer viewer) {
        requireVisible(id, viewer);
        return findById(id);
    }

    public Delivery findByOrderId(UUID orderId, DeliveryViewer viewer) {
        var delivery = deliveries.findByOrderId(orderId).orElseThrow(DeliveryNotFoundException::new);
        requireVisible(delivery.id(), viewer);
        return delivery;
    }

    /** Someone else's delivery is reported as absent, exactly like one that does not exist. */
    public void requireVisible(UUID id, DeliveryViewer viewer) {
        if (!viewer.operator() && !deliveries.isOwnedBy(id, viewer.userId())) {
            throw new DeliveryNotFoundException();
        }
    }

    public DeliveryPage findPage(DeliveryStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid page request");
        }
        return deliveries.findPage(status, page, size);
    }

    public Delivery assign(UUID id, UUID courierId) {
        return deliveries.assign(id, courierId, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery pickUp(UUID id) {
        return deliveries.pickUp(id, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery startTransit(UUID id) {
        return deliveries.startTransit(id, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery arrive(UUID id) {
        return deliveries.arrive(id, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery complete(UUID id) {
        return deliveries.complete(id, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery cancel(UUID id) {
        return deliveries.cancel(id, now()).orElseThrow(DeliveryNotFoundException::new);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
