package com.victhor.delivery.order.domain;

import java.util.Arrays;

/**
 * Codes accepted by the Payment Service. Order checks them before recording an intent so that a typo never leaves
 * a pending payment behind; the outcome of each method is decided only by Payment.
 */
public enum SimulatedPaymentMethod {

    APPROVED_CARD("sim-card-approved"),
    DECLINED_CARD("sim-card-declined"),
    INSUFFICIENT_FUNDS_CARD("sim-card-insufficient-funds");

    private final String code;

    SimulatedPaymentMethod(String code) {
        this.code = code;
    }

    public static SimulatedPaymentMethod fromCode(String code) {
        return Arrays.stream(values()).filter(method -> method.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulated payment method"));
    }

    public String code() {
        return code;
    }
}
