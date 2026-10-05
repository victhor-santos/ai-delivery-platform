package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMenuItemRepository extends JpaRepository<MenuItemEntity, UUID> {

    Optional<MenuItemEntity> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    Page<MenuItemEntity> findAllByRestaurantId(UUID restaurantId, Pageable pageable);
}
