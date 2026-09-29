package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRestaurantRepository extends JpaRepository<RestaurantEntity, UUID> {
}
