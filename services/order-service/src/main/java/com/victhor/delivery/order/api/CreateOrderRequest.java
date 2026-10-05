package com.victhor.delivery.order.api;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.order.domain.OrderPricing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderRequest(@NotNull UUID restaurantId, @Valid @NotNull DestinationRequest destination,
        @NotEmpty @Size(max = OrderPricing.MAX_ITEMS) List<@NotNull @Valid OrderItemRequest> items) {
}
