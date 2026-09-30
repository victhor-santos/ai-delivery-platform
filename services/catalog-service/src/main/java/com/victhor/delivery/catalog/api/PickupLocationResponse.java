package com.victhor.delivery.catalog.api;

import com.victhor.delivery.catalog.domain.PickupLocation;

public record PickupLocationResponse(double latitude, double longitude) {

    static PickupLocationResponse from(PickupLocation location) {
        return new PickupLocationResponse(location.latitude(), location.longitude());
    }
}
