package com.victhor.delivery.delivery.api;

import com.victhor.delivery.delivery.domain.DeliveryLocation;

public record DeliveryLocationResponse(String description, double latitude, double longitude) {

    static DeliveryLocationResponse from(DeliveryLocation location) {
        return new DeliveryLocationResponse(location.description(), location.point().latitude(), location.point().longitude());
    }
}
