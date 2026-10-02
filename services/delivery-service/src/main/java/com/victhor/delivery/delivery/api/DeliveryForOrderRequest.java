package com.victhor.delivery.delivery.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DeliveryForOrderRequest(@Valid @NotNull DeliveryLocationRequest origin,
        @Valid @NotNull DeliveryLocationRequest destination) {
}
