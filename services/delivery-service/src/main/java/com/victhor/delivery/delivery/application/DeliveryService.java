package com.victhor.delivery.delivery.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.Delivery;
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

    public Delivery findById(UUID id) {
        return deliveries.findById(id).orElseThrow(DeliveryNotFoundException::new);
    }

    public Delivery findByOrderId(UUID orderId) {
        return deliveries.findByOrderId(orderId).orElseThrow(DeliveryNotFoundException::new);
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
