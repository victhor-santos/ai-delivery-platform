package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Order's record of one payment intent. It is persisted as {@code PENDING} before Payment is called, so a lost
 * response can be recovered by repeating the intent under the same key without charging twice.
 */
public record OrderPayment(UUID orderId, IdempotencyKey idempotencyKey, SimulatedPaymentMethod method,
        BigDecimal amount, OrderPaymentStatus status, UUID paymentId, String declineReason, Instant requestedAt,
        Instant completedAt) {

    public static final int MAX_DECLINE_REASON_LENGTH = 40;

    public OrderPayment {
        Objects.requireNonNull(orderId, "Order id is required");
        Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
        Objects.requireNonNull(method, "Payment method is required");
        Objects.requireNonNull(amount, "Amount is required");
        Objects.requireNonNull(status, "Payment status is required");
        Objects.requireNonNull(requestedAt, "Request time is required");
        if (amount.signum() <= 0 || amount.compareTo(OrderPricing.MAX_TOTAL) > 0) {
            throw new IllegalArgumentException("Payment amount must be a valid order total");
        }
        if (declineReason != null && (declineReason.isBlank() || declineReason.length() > MAX_DECLINE_REASON_LENGTH)) {
            throw new IllegalArgumentException("Decline reason must be a short code");
        }
        if (completedAt != null && completedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("A payment cannot complete before it was requested");
        }
        boolean consistent = switch (status) {
            case PENDING, REJECTED -> paymentId == null && declineReason == null
                    && (status == OrderPaymentStatus.PENDING) == (completedAt == null);
            case APPROVED -> paymentId != null && declineReason == null && completedAt != null;
            case DECLINED -> paymentId != null && declineReason != null && completedAt != null;
        };
        if (!consistent) {
            throw new IllegalArgumentException("Payment data must match its status");
        }
    }

    public static OrderPayment request(Order order, IdempotencyKey key, SimulatedPaymentMethod method, Instant now) {
        if (order.pricing() == null) {
            throw new OrderStateConflictException("Only an order with a total can be paid");
        }
        return new OrderPayment(order.id(), key, method, order.pricing().total(), OrderPaymentStatus.PENDING, null,
                null, now, null);
    }

    public boolean isPending() {
        return status == OrderPaymentStatus.PENDING;
    }

    /** Whether a retry with the same key describes this same intent; the amount always comes from the order. */
    public boolean sameIntentAs(SimulatedPaymentMethod otherMethod) {
        return method == otherMethod;
    }

    public OrderPayment approve(UUID approvedPaymentId, Instant now) {
        return complete(OrderPaymentStatus.APPROVED, Objects.requireNonNull(approvedPaymentId), null, now);
    }

    public OrderPayment decline(UUID declinedPaymentId, String reason, Instant now) {
        return complete(OrderPaymentStatus.DECLINED, Objects.requireNonNull(declinedPaymentId),
                Objects.requireNonNull(reason), now);
    }

    public OrderPayment reject(Instant now) {
        return complete(OrderPaymentStatus.REJECTED, null, null, now);
    }

    private OrderPayment complete(OrderPaymentStatus outcome, UUID completedPaymentId, String reason, Instant now) {
        if (!isPending()) {
            throw new IllegalStateException("Only a pending payment can be completed");
        }
        return new OrderPayment(orderId, idempotencyKey, method, amount, outcome, completedPaymentId, reason,
                requestedAt, now);
    }
}
