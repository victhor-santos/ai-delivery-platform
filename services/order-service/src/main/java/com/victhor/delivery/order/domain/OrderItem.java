package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record OrderItem(UUID menuItemId, String name, int quantity, BigDecimal unitPrice) {

    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_QUANTITY = 99;
    public static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("99999999.99");

    public OrderItem {
        Objects.requireNonNull(menuItemId, "Menu item id is required");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Order item name is required");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Order item name must have at most 120 characters");
        }
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Order item quantity must be between 1 and 99");
        }
        if (unitPrice == null || unitPrice.signum() <= 0 || unitPrice.compareTo(MAX_UNIT_PRICE) > 0
                || unitPrice.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Order item price must be positive, at most 99999999.99 and use cents");
        }
        unitPrice = unitPrice.setScale(2, RoundingMode.UNNECESSARY);
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
