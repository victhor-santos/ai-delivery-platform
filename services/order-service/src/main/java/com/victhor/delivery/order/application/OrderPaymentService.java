package com.victhor.delivery.order.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderPayment;
import com.victhor.delivery.order.domain.OrderPaymentStatus;
import com.victhor.delivery.order.domain.SimulatedPaymentMethod;

/**
 * Pays an order through the Payment Service. The intent is persisted before the remote call and settled after it,
 * without a distributed transaction: when the outcome is unknown the intent stays pending, and repeating the request
 * with the same key resumes it. Payment is idempotent per key, so the order is charged at most once.
 */
public class OrderPaymentService {

    private final OrderRepository orders;
    private final OrderPaymentRepository payments;
    private final PaymentGateway gateway;
    private final Clock clock;

    public OrderPaymentService(OrderRepository orders, OrderPaymentRepository payments, PaymentGateway gateway,
            Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.gateway = gateway;
        this.clock = clock;
    }

    public OrderPayment pay(UUID orderId, UUID customerId, IdempotencyKey key, SimulatedPaymentMethod method,
            String accessToken) {
        Objects.requireNonNull(key, "Idempotency key is required");
        Objects.requireNonNull(method, "Payment method is required");
        Objects.requireNonNull(accessToken, "Access token is required");
        Order order = orders.findById(orderId).filter(candidate -> candidate.isPlacedBy(customerId))
                .orElseThrow(OrderNotFoundException::new);
        OrderPayment intent = payments.find(orderId, key)
                .orElseGet(() -> payments.start(OrderPayment.request(order, key, method, now())));
        if (!intent.sameIntentAs(method)) {
            throw new IdempotencyKeyReusedException();
        }
        OrderPayment settled = intent.isPending() ? settle(intent, accessToken) : intent;
        if (settled.status() == OrderPaymentStatus.REJECTED) {
            throw new PaymentRejectedException(false);
        }
        return settled;
    }

    private OrderPayment settle(OrderPayment pending, String accessToken) {
        PaymentGateway.PaymentOutcome outcome;
        try {
            outcome = gateway.charge(pending, accessToken);
        } catch (PaymentRejectedException rejection) {
            // A charge made under another key must still confirm the order instead of leaving it paid but open.
            var approved = rejection.alreadyCharged() ? gateway.findApproved(pending, accessToken)
                    : Optional.<PaymentGateway.PaymentOutcome>empty();
            if (approved.isEmpty()) {
                payments.complete(pending.reject(now()));
                throw rejection;
            }
            outcome = approved.orElseThrow();
        }
        return payments.complete(outcome.approved() ? pending.approve(outcome.paymentId(), now())
                : pending.decline(outcome.paymentId(), outcome.declineReason(), now()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
