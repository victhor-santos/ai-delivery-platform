package com.victhor.delivery.order.domain;

import java.util.Objects;
import java.util.UUID;

public record OrderItemSelection(UUID menuItemId, int quantity) {

    public OrderItemSelection {
        Objects.requireNonNull(menuItemId, "Menu item id is required");
        if (quantity < 1 || quantity > OrderItem.MAX_QUANTITY) {
            throw new IllegalArgumentException("Order item quantity must be between 1 and 99");
        }
    }
}
