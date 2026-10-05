package com.victhor.delivery.order.application;

import java.math.BigDecimal;
import java.util.UUID;

public interface CatalogLookup {

    boolean isRestaurantActive(UUID restaurantId);

    CatalogMenuItem findMenuItem(UUID restaurantId, UUID menuItemId);

    record CatalogMenuItem(String name, BigDecimal price, boolean available) {
    }
}
