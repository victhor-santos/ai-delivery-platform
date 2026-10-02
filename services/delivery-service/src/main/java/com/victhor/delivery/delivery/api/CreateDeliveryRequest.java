package com.victhor.delivery.delivery.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateDeliveryRequest(@NotNull UUID orderId,
        @Valid @NotNull DeliveryLocationRequest origin,
        @Valid @NotNull DeliveryLocationRequest destination) {
}
