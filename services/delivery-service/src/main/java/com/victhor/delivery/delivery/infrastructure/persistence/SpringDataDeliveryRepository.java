package com.victhor.delivery.delivery.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {

    Optional<DeliveryEntity> findByOrderId(UUID orderId);
}
