package com.victhor.delivery.catalog.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.catalog.domain.MenuItem;

@Entity
@Table(name = "menu_items")
public class MenuItemEntity {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(nullable = false, length = MenuItem.MAX_NAME_LENGTH)
    private String name;

    @Column(length = MenuItem.MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean available;

    protected MenuItemEntity() {
    }

    private MenuItemEntity(MenuItem item) {
        id = item.id();
        restaurantId = item.restaurantId();
        updateDetails(item);
    }

    static MenuItemEntity fromDomain(MenuItem item) {
        return new MenuItemEntity(item);
    }

    MenuItem toDomain() {
        return new MenuItem(id, restaurantId, name, description, price, available);
    }

    void updateDetails(MenuItem item) {
        name = item.name();
        description = item.description();
        price = item.price();
        available = item.available();
    }
}
