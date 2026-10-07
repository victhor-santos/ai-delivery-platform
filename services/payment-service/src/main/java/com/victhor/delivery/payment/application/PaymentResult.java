package com.victhor.delivery.payment.application;

import com.victhor.delivery.payment.domain.PaymentAttempt;

/** The attempt for an idempotency key and whether this call replayed an earlier one instead of creating it. */
public record PaymentResult(PaymentAttempt attempt, boolean replayed) {
}
