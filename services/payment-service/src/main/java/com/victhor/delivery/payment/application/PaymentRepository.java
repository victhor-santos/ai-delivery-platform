package com.victhor.delivery.payment.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.payment.domain.IdempotencyKey;
import com.victhor.delivery.payment.domain.PaymentAttempt;

public interface PaymentRepository {

    /**
     * Stores a new attempt atomically.
     *
     * @throws IdempotencyKeyAlreadyUsedException if the customer already used the key, even concurrently
     * @throws OrderAlreadyPaidException if the attempt is approved and the customer already paid the order
     */
    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(UUID id);

    Optional<PaymentAttempt> findByIdempotencyKey(UUID customerId, IdempotencyKey key);

    boolean hasApprovedPayment(UUID customerId, UUID orderId);

    PaymentAttemptPage findByOrder(UUID customerId, UUID orderId, int page, int size);
}
