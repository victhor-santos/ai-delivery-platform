package com.victhor.delivery.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.payment.domain.DeclineReason;
import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;
import com.victhor.delivery.payment.domain.PaymentStatus;
import com.victhor.delivery.payment.domain.SimulatedPaymentMethod;

/** Attempts are immutable once decided, so the entity has no setters or version column. */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false, length = IdempotencyKey.MAX_LENGTH)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private SimulatedPaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 40)
    private DeclineReason declineReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttemptEntity() {
    }

    private PaymentAttemptEntity(PaymentAttempt attempt) {
        id = attempt.id();
        customerId = attempt.customerId();
        orderId = attempt.orderId();
        idempotencyKey = attempt.idempotencyKey().value();
        amount = attempt.amount();
        method = attempt.method();
        status = attempt.status();
        declineReason = attempt.declineReason();
        createdAt = attempt.createdAt();
    }

    static PaymentAttemptEntity fromDomain(PaymentAttempt attempt) {
        return new PaymentAttemptEntity(attempt);
    }

    PaymentAttempt toDomain() {
        return new PaymentAttempt(id, customerId, orderId, new IdempotencyKey(idempotencyKey), amount, method, status,
                declineReason, createdAt);
    }
}
