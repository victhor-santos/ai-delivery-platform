package com.victhor.delivery.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record MenuItem(UUID id, UUID restaurantId, String name, String description, BigDecimal price,
        boolean available) {

    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    public MenuItem {
        Objects.requireNonNull(id, "Menu item id is required");
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Menu item name is required");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Menu item name must have at most 120 characters");
        }
        if (description != null) {
            description = description.strip();
            if (description.isEmpty()) {
                description = null;
            } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("Menu item description must have at most 1000 characters");
            }
        }
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Menu item price must be positive, at most 99999999.99 and use cents");
        }
        price = price.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static MenuItem create(UUID restaurantId, String name, String description, BigDecimal price) {
        return new MenuItem(UUID.randomUUID(), restaurantId, name, description, price, true);
    }
}
