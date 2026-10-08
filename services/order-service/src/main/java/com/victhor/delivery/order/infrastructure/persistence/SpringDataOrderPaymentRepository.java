package com.victhor.delivery.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrderPaymentRepository extends JpaRepository<OrderPaymentEntity, OrderPaymentEntity.Key> {
}
