package com.victhor.delivery.catalog.api;

import java.util.UUID;

import com.victhor.delivery.catalog.domain.Restaurant;

public record RestaurantResponse(UUID id, String name, boolean active) {

    static RestaurantResponse from(Restaurant restaurant) {
        return new RestaurantResponse(restaurant.id(), restaurant.name(), restaurant.active());
    }
}
