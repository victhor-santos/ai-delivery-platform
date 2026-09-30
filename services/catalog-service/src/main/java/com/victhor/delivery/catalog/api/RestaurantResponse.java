package com.victhor.delivery.catalog.api;

import java.util.UUID;

import com.victhor.delivery.catalog.domain.Restaurant;

public record RestaurantResponse(UUID id, String name, boolean active, PickupLocationResponse pickupLocation) {

    static RestaurantResponse from(Restaurant restaurant) {
        var location = restaurant.pickupLocation() == null
                ? null : PickupLocationResponse.from(restaurant.pickupLocation());
        return new RestaurantResponse(restaurant.id(), restaurant.name(), restaurant.active(), location);
    }
}
