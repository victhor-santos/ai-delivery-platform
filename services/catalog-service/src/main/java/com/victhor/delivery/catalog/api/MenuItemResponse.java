package com.victhor.delivery.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.victhor.delivery.catalog.domain.MenuItem;

public record MenuItemResponse(UUID id, UUID restaurantId, String name, String description,
        BigDecimal price, String currency, boolean available) {

    static MenuItemResponse from(MenuItem item) {
        return new MenuItemResponse(item.id(), item.restaurantId(), item.name(), item.description(),
                item.price(), "BRL", item.available());
    }
}
