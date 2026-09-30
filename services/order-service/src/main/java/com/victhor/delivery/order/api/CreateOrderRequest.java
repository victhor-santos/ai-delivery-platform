package com.victhor.delivery.order.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderRequest(@NotNull UUID restaurantId, @Valid @NotNull DestinationRequest destination) {
}
