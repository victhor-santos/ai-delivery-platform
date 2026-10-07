package com.victhor.delivery.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One simulated charge attempt; it is decided when created and never changes afterwards. */
public record PaymentAttempt(UUID id, UUID customerId, UUID orderId, IdempotencyKey idempotencyKey, BigDecimal amount,
        SimulatedPaymentMethod method, PaymentStatus status, DeclineReason declineReason, Instant createdAt) {

    public static final String CURRENCY = "BRL";
    /** Same ceiling as an order total, so any valid order can be paid in a single attempt. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("494999999950.50");

    public PaymentAttempt {
        Objects.requireNonNull(id, "Payment id is required");
        Objects.requireNonNull(customerId, "Customer id is required");
        Objects.requireNonNull(orderId, "Order id is required");
        Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
        Objects.requireNonNull(method, "Payment method is required");
        Objects.requireNonNull(status, "Payment status is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
        amount = validateAmount(amount);
        if (status != method.outcome() || declineReason != method.declineReason()) {
            throw new IllegalArgumentException("Payment outcome must match its simulated method");
        }
    }

    public static PaymentAttempt process(UUID customerId, UUID orderId, IdempotencyKey idempotencyKey,
            BigDecimal amount, SimulatedPaymentMethod method, Instant now) {
        Objects.requireNonNull(method, "Payment method is required");
        return new PaymentAttempt(UUID.randomUUID(), customerId, orderId, idempotencyKey, amount, method,
                method.outcome(), method.declineReason(), now);
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    public boolean isOwnedBy(UUID customer) {
        return customerId.equals(customer);
    }

    /** Whether a retry with the same idempotency key describes this same payment intent. */
    public boolean sameIntentAs(UUID otherOrderId, BigDecimal otherAmount, SimulatedPaymentMethod otherMethod) {
        return orderId.equals(otherOrderId) && amount.compareTo(otherAmount) == 0 && method == otherMethod;
    }

    public static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Payment amount must be positive, at most 494999999950.50 and use cents");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
