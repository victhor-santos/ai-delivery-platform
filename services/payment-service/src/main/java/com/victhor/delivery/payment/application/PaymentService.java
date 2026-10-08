package com.victhor.delivery.payment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;
import com.victhor.delivery.payment.domain.SimulatedPaymentMethod;

public class PaymentService {

    public static final int MAX_PAGE_SIZE = 100;

    private final PaymentRepository payments;
    private final OrderLookup orders;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, OrderLookup orders, Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Processes a simulated charge once per customer and idempotency key. Repeating the same intent returns the
     * stored attempt, including after a concurrent request won the race; another intent under the key is rejected.
     * A new attempt is charged only after Order confirms that the customer's order awaits exactly this amount.
     */
    public PaymentResult attempt(UUID customerId, IdempotencyKey key, UUID orderId, BigDecimal amount,
            SimulatedPaymentMethod method, String accessToken) {
        Objects.requireNonNull(customerId, "Customer id is required");
        Objects.requireNonNull(key, "Idempotency key is required");
        Objects.requireNonNull(orderId, "Order id is required");
        Objects.requireNonNull(method, "Payment method is required");
        Objects.requireNonNull(accessToken, "Access token is required");
        BigDecimal validAmount = PaymentAttempt.validateAmount(amount);

        var existing = payments.findByIdempotencyKey(customerId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), orderId, validAmount, method);
        }
        try {
            if (payments.hasApprovedPayment(customerId, orderId)) {
                throw new OrderAlreadyPaidException();
            }
            if (!orders.findById(orderId, accessToken).accepts(validAmount)) {
                throw new OrderNotPayableException();
            }
            var attempt = PaymentAttempt.process(customerId, orderId, key, validAmount, method, now());
            return new PaymentResult(payments.save(attempt), false);
        } catch (IdempotencyKeyAlreadyUsedException | OrderAlreadyPaidException conflict) {
            // A concurrent request with this same key may have been stored, and even approved, after the lookup.
            var winner = payments.findByIdempotencyKey(customerId, key).orElseThrow(() -> conflict);
            return replay(winner, orderId, validAmount, method);
        }
    }

    /** Another customer's attempt is reported as absent so its existence is not revealed. */
    public PaymentAttempt findById(UUID id, UUID customerId) {
        return payments.findById(id).filter(attempt -> attempt.isOwnedBy(customerId))
                .orElseThrow(PaymentNotFoundException::new);
    }

    public PaymentAttemptPage findByOrder(UUID customerId, UUID orderId, int page, int size) {
        Objects.requireNonNull(customerId, "Customer id is required");
        Objects.requireNonNull(orderId, "Order id is required");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid payment page");
        }
        return payments.findByOrder(customerId, orderId, page, size);
    }

    private static PaymentResult replay(PaymentAttempt existing, UUID orderId, BigDecimal amount,
            SimulatedPaymentMethod method) {
        if (!existing.sameIntentAs(orderId, amount, method)) {
            throw new IdempotencyKeyReusedException();
        }
        return new PaymentResult(existing, true);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
