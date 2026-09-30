package com.victhor.delivery.catalog.api;

import jakarta.validation.constraints.NotNull;

import com.victhor.delivery.catalog.domain.PickupLocation;

public record PickupLocationRequest(@NotNull Double latitude, @NotNull Double longitude) {

    PickupLocation toDomain() {
        return new PickupLocation(latitude, longitude);
    }
}
