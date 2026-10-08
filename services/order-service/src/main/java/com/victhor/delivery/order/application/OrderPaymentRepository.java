package com.victhor.delivery.order.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.order.domain.IdempotencyKey;
import com.victhor.delivery.order.domain.OrderPayment;

public interface OrderPaymentRepository {

    Optional<OrderPayment> find(UUID orderId, IdempotencyKey key);

    /**
     * Records a pending intent and marks the order as awaiting payment in one transaction. When the key was already
     * used for this order, the stored intent is returned instead.
     *
     * @throws PaymentInProgressException if another intent for the order is still pending
     */
    OrderPayment start(OrderPayment payment);

    /** Stores the outcome and applies it to the order; an intent that was already completed is returned as is. */
    OrderPayment complete(OrderPayment completed);
}
