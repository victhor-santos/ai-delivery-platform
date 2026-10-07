package com.victhor.delivery.payment.domain;

import java.util.Arrays;

/**
 * Fictitious payment methods whose outcome is fixed in advance. No card data is accepted and no money moves;
 * each code makes the simulated result explicit to whoever calls the API.
 */
public enum SimulatedPaymentMethod {

    APPROVED_CARD("sim-card-approved", PaymentStatus.APPROVED, null),
    DECLINED_CARD("sim-card-declined", PaymentStatus.DECLINED, DeclineReason.CARD_DECLINED),
    INSUFFICIENT_FUNDS_CARD("sim-card-insufficient-funds", PaymentStatus.DECLINED, DeclineReason.INSUFFICIENT_FUNDS);

    private final String code;
    private final PaymentStatus outcome;
    private final DeclineReason declineReason;

    SimulatedPaymentMethod(String code, PaymentStatus outcome, DeclineReason declineReason) {
        this.code = code;
        this.outcome = outcome;
        this.declineReason = declineReason;
    }

    public static SimulatedPaymentMethod fromCode(String code) {
        return Arrays.stream(values()).filter(method -> method.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulated payment method"));
    }

    public String code() {
        return code;
    }

    public PaymentStatus outcome() {
        return outcome;
    }

    public DeclineReason declineReason() {
        return declineReason;
    }
}
