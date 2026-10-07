package com.victhor.delivery.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.victhor.delivery.payment.domain.PaymentAttempt;

public record PaymentResponse(UUID id, UUID orderId, BigDecimal amount, String currency, String method, String status,
        String declineReason, boolean simulated, Instant createdAt) {

    static PaymentResponse from(PaymentAttempt attempt) {
        return new PaymentResponse(attempt.id(), attempt.orderId(), attempt.amount(), PaymentAttempt.CURRENCY,
                attempt.method().code(), attempt.status().name(),
                attempt.declineReason() == null ? null : attempt.declineReason().name(), true, attempt.createdAt());
    }
}
