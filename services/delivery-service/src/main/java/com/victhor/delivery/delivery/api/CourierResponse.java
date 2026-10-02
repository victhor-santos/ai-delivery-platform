package com.victhor.delivery.delivery.api;

import java.util.UUID;

import com.victhor.delivery.delivery.domain.Courier;

public record CourierResponse(UUID id, boolean active) {

    static CourierResponse from(Courier courier) {
        return new CourierResponse(courier.id(), courier.active());
    }
}
