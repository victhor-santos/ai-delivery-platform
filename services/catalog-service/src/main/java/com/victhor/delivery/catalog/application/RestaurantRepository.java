package com.victhor.delivery.catalog.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

public interface RestaurantRepository {

    Restaurant save(Restaurant restaurant);

    Optional<Restaurant> findById(UUID id);

    Optional<Restaurant> updatePickupLocation(UUID id, PickupLocation pickupLocation);

    RestaurantPage findAll(int page, int size);
}
