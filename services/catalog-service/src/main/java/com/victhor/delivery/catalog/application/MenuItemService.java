package com.victhor.delivery.catalog.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.victhor.delivery.catalog.domain.MenuItem;

public class MenuItemService {

    public static final int MAX_PAGE_SIZE = 100;

    private final MenuItemRepository menuItems;
    private final RestaurantRepository restaurants;

    public MenuItemService(MenuItemRepository menuItems, RestaurantRepository restaurants) {
        this.menuItems = menuItems;
        this.restaurants = restaurants;
    }

    public MenuItem create(UUID restaurantId, String name, String description, BigDecimal price) {
        var menuItem = MenuItem.create(restaurantId, name, description, price);
        requireRestaurant(restaurantId);
        return menuItems.save(menuItem);
    }

    public MenuItem update(UUID restaurantId, UUID id, String name, String description, BigDecimal price,
            boolean available) {
        var menuItem = new MenuItem(id, restaurantId, name, description, price, available);
        requireRestaurant(restaurantId);
        return menuItems.update(menuItem).orElseThrow(MenuItemNotFoundException::new);
    }

    public MenuItem findById(UUID restaurantId, UUID id) {
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        Objects.requireNonNull(id, "Menu item id is required");
        requireRestaurant(restaurantId);
        return menuItems.findById(restaurantId, id).orElseThrow(MenuItemNotFoundException::new);
    }

    public MenuItemPage findAll(UUID restaurantId, int page, int size) {
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        long offset = (long) page * size;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        requireRestaurant(restaurantId);
        return menuItems.findAll(restaurantId, page, size);
    }

    private void requireRestaurant(UUID restaurantId) {
        restaurants.findById(restaurantId).orElseThrow(RestaurantNotFoundException::new);
    }
}
