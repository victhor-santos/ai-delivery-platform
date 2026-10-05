package com.victhor.delivery.order.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderItemSelection;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderItemRequest(@NotNull UUID menuItemId,
        @NotNull @Min(1) @Max(OrderItem.MAX_QUANTITY) Integer quantity) {

    OrderItemSelection toDomain() {
        return new OrderItemSelection(menuItemId, quantity);
    }
}
