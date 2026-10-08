package com.victhor.delivery.order.application;

/** Payment refused the intent without charging, or a stored intent was refused before. */
public class PaymentRejectedException extends RuntimeException {

    private final boolean alreadyCharged;

    public PaymentRejectedException(boolean alreadyCharged) {
        this.alreadyCharged = alreadyCharged;
    }

    /** Payment reported that the order was already charged; Order checks for that approval before giving up. */
    public boolean alreadyCharged() {
        return alreadyCharged;
    }
}
