package com.victhor.delivery.order.domain;

public enum OrderPaymentStatus {
    /** Recorded before calling Payment; the charge outcome is still unknown. */
    PENDING,
    APPROVED,
    DECLINED,
    /** Payment refused to process the request, so nothing was charged. */
    REJECTED
}
