package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

@Entity
@Table(name = "restaurants")
public class RestaurantEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = Restaurant.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "pickup_latitude")
    private Double pickupLatitude;

    @Column(name = "pickup_longitude")
    private Double pickupLongitude;

    protected RestaurantEntity() {
    }

    private RestaurantEntity(Restaurant restaurant) {
        id = restaurant.id();
        name = restaurant.name();
        active = restaurant.active();
        if (restaurant.pickupLocation() != null) {
            updatePickupLocation(restaurant.pickupLocation());
        }
    }

    static RestaurantEntity fromDomain(Restaurant restaurant) {
        return new RestaurantEntity(restaurant);
    }

    Restaurant toDomain() {
        var location = pickupLatitude == null ? null : new PickupLocation(pickupLatitude, pickupLongitude);
        return new Restaurant(id, name, active, location);
    }

    void updatePickupLocation(PickupLocation location) {
        pickupLatitude = location.latitude();
        pickupLongitude = location.longitude();
    }
}
