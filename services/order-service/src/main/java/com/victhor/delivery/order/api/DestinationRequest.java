package com.victhor.delivery.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.victhor.delivery.order.domain.DeliveryDestination;

public record DestinationRequest(
        @NotBlank @Size(max = DeliveryDestination.MAX_ADDRESS_LENGTH) String address,
        @NotNull Double latitude, @NotNull Double longitude) {

    public DestinationRequest {
        if (address != null) {
            address = address.strip();
        }
    }

    DeliveryDestination toDomain() {
        return new DeliveryDestination(address, latitude, longitude);
    }
}
