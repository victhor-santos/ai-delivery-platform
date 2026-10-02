package com.victhor.delivery.delivery.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.GeoPoint;

public record DeliveryLocationRequest(
        @NotBlank @Size(max = DeliveryLocation.MAX_DESCRIPTION_LENGTH) String description,
        @NotNull Double latitude, @NotNull Double longitude) {

    public DeliveryLocationRequest {
        if (description != null) {
            description = description.strip();
        }
    }

    DeliveryLocation toDomain() {
        return new DeliveryLocation(description, new GeoPoint(latitude, longitude));
    }
}
