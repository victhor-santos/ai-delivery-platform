package com.victhor.delivery.delivery.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCourierRepository extends JpaRepository<CourierEntity, UUID> {
}
