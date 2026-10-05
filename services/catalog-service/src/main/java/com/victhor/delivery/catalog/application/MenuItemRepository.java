package com.victhor.delivery.catalog.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.catalog.domain.MenuItem;

public interface MenuItemRepository {

    MenuItem save(MenuItem menuItem);

    Optional<MenuItem> findById(UUID restaurantId, UUID id);

    Optional<MenuItem> update(MenuItem menuItem);

    MenuItemPage findAll(UUID restaurantId, int page, int size);
}
