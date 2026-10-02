package com.victhor.delivery.delivery.application;

import java.util.UUID;

import com.victhor.delivery.delivery.domain.Courier;

public class CourierService {

    private final CourierRepository couriers;

    public CourierService(CourierRepository couriers) {
        this.couriers = couriers;
    }

    public Courier create() {
        return couriers.create(new Courier(UUID.randomUUID(), true));
    }

    public Courier findById(UUID id) {
        return couriers.findById(id).orElseThrow(CourierNotFoundException::new);
    }
}
