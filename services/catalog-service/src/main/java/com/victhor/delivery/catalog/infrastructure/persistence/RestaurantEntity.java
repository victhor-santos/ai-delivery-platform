package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.catalog.domain.Restaurant;

@Entity
@Table(name = "restaurants")
public class RestaurantEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = Restaurant.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false)
    private boolean active;

    protected RestaurantEntity() {
    }

    private RestaurantEntity(Restaurant restaurant) {
        id = restaurant.id();
        name = restaurant.name();
        active = restaurant.active();
    }

    static RestaurantEntity fromDomain(Restaurant restaurant) {
        return new RestaurantEntity(restaurant);
    }

    Restaurant toDomain() {
        return new Restaurant(id, name, active);
    }
}
