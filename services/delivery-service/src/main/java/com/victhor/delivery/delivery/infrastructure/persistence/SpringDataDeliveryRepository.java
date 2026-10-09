package com.victhor.delivery.delivery.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.victhor.delivery.delivery.domain.DeliveryStatus;

interface SpringDataDeliveryRepository extends JpaRepository<DeliveryEntity, UUID> {

    Optional<DeliveryEntity> findByOrderId(UUID orderId);

    boolean existsByIdAndCustomerId(UUID id, UUID customerId);

    Page<DeliveryEntity> findByStatus(DeliveryStatus status, Pageable pageable);
}
