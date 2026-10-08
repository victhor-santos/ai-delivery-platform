package com.victhor.delivery.order.infrastructure.persistence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPaymentStatus;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

@Entity
@Table(name = "order_payments")
@IdClass(OrderPaymentEntity.Key.class)
class OrderPaymentEntity {

    @Id
    private UUID orderId;
    @Id
    @Column(length = IdempotencyKey.MAX_LENGTH)
    private String idempotencyKey;
    @Column(nullable = false, length = 40)
    private String method;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderPaymentStatus status;
    private UUID paymentId;
    @Column(length = OrderPayment.MAX_DECLINE_REASON_LENGTH)
    private String declineReason;
    @Column(nullable = false)
    private Instant requestedAt;
    private Instant completedAt;

    protected OrderPaymentEntity() {
    }

    static OrderPaymentEntity from(OrderPayment payment) {
        var entity = new OrderPaymentEntity();
        entity.orderId = payment.orderId();
        entity.idempotencyKey = payment.idempotencyKey().value();
        entity.method = payment.method().code();
        entity.amount = payment.amount();
        entity.requestedAt = payment.requestedAt();
        entity.applyOutcome(payment);
        return entity;
    }

    OrderPayment toDomain() {
        return new OrderPayment(orderId, new IdempotencyKey(idempotencyKey), SimulatedPaymentMethod.fromCode(method),
                amount, status, paymentId, declineReason, requestedAt, completedAt);
    }

    void applyOutcome(OrderPayment payment) {
        status = payment.status();
        paymentId = payment.paymentId();
        declineReason = payment.declineReason();
        completedAt = payment.completedAt();
    }

    record Key(UUID orderId, String idempotencyKey) implements Serializable {
    }
}
