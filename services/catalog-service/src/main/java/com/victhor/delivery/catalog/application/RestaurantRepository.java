package com.victhor.delivery.catalog.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.catalog.domain.Restaurant;

public interface RestaurantRepository {

    Restaurant save(Restaurant restaurant);

    Optional<Restaurant> findById(UUID id);

    RestaurantPage findAll(int page, int size);
}
