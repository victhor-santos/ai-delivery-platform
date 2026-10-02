package com.victhor.delivery.delivery.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

final class DeliveryFixtures {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), ZoneOffset.UTC);
    static final Instant CREATED = CLOCK.instant();
    static final DeliveryLocation ORIGIN = new DeliveryLocation("Restaurante Central", new GeoPoint(-23.55, -46.63));
    static final DeliveryLocation DESTINATION = new DeliveryLocation("Rua das Flores, 42", new GeoPoint(-23.56, -46.64));
    static final Courier COURIER = new Courier(UUID.randomUUID(), true);

    private DeliveryFixtures() {
    }

    static Delivery newDelivery() {
        return Delivery.create(UUID.randomUUID(), ORIGIN, DESTINATION, CREATED);
    }

    static Delivery at(Stage stage) {
        Delivery delivery = newDelivery();
        if (stage == Stage.CREATED) {
            return delivery;
        }
        if (stage == Stage.CANCELLED_UNASSIGNED) {
            delivery.cancel(CREATED.plusSeconds(1));
            return delivery;
        }
        delivery.assign(COURIER, CREATED.plusSeconds(1));
        if (stage == Stage.ASSIGNED) {
            return delivery;
        }
        if (stage == Stage.CANCELLED_ASSIGNED) {
            delivery.cancel(CREATED.plusSeconds(2));
            return delivery;
        }
        delivery.pickUp(CREATED.plusSeconds(2));
        if (stage == Stage.PICKED_UP) {
            return delivery;
        }
        delivery.startTransit(CREATED.plusSeconds(3));
        if (stage == Stage.IN_TRANSIT) {
            return delivery;
        }
        delivery.arrive(CREATED.plusSeconds(4));
        if (stage == Stage.ARRIVED) {
            return delivery;
        }
        delivery.complete(CREATED.plusSeconds(5));
        return delivery;
    }

    enum Stage {
        CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, ARRIVED, DELIVERED, CANCELLED_UNASSIGNED, CANCELLED_ASSIGNED
    }
}
