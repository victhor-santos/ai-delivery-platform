package com.victhor.delivery.payment.api;

import java.util.List;

import com.victhor.delivery.payment.application.PaymentAttemptPage;

public record PaymentPageResponse(List<PaymentResponse> items, int page, int size, long totalElements,
        long totalPages) {

    static PaymentPageResponse from(PaymentAttemptPage page) {
        return new PaymentPageResponse(page.items().stream().map(PaymentResponse::from).toList(), page.page(),
                page.size(), page.totalElements(), page.totalPages());
    }
}
