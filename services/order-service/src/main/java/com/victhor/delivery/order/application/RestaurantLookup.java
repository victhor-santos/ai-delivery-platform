package com.victhor.delivery.order.application;

import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryDestination;

public interface RestaurantLookup {

    RestaurantPickup findById(UUID restaurantId);

    record RestaurantPickup(boolean active, DeliveryDestination location) {
    }
}
