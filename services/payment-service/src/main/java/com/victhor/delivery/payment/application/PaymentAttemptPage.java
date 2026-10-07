package com.victhor.delivery.payment.application;

import java.util.List;

import com.victhor.delivery.payment.domain.PaymentAttempt;

public record PaymentAttemptPage(List<PaymentAttempt> items, int page, int size, long totalElements) {

    public PaymentAttemptPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
