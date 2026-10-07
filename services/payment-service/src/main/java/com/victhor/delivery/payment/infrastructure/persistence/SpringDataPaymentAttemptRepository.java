package com.victhor.delivery.payment.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.victhor.delivery.payment.domain.PaymentStatus;

interface SpringDataPaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    Optional<PaymentAttemptEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    boolean existsByCustomerIdAndOrderIdAndStatus(UUID customerId, UUID orderId, PaymentStatus status);

    Page<PaymentAttemptEntity> findByCustomerIdAndOrderId(UUID customerId, UUID orderId, Pageable pageable);
}
