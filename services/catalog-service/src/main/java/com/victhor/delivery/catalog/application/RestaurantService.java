package com.victhor.delivery.catalog.application;

import java.util.UUID;

import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

public class RestaurantService {

    public static final int MAX_PAGE_SIZE = 100;

    private final RestaurantRepository restaurants;

    public RestaurantService(RestaurantRepository restaurants) {
        this.restaurants = restaurants;
    }

    public Restaurant create(String name) {
        return create(name, null);
    }

    public Restaurant create(String name, PickupLocation pickupLocation) {
        return restaurants.save(Restaurant.create(name, pickupLocation));
    }

    public Restaurant updatePickupLocation(UUID id, PickupLocation pickupLocation) {
        if (pickupLocation == null) {
            throw new IllegalArgumentException("Pickup location is required");
        }
        return restaurants.updatePickupLocation(id, pickupLocation)
                .orElseThrow(RestaurantNotFoundException::new);
    }

    public Restaurant findById(UUID id) {
        return restaurants.findById(id).orElseThrow(RestaurantNotFoundException::new);
    }

    public RestaurantPage findAll(int page, int size) {
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        return restaurants.findAll(page, size);
    }
}
