package com.victhor.delivery.payment.application;

/** The key belongs to an attempt with another order, amount or method. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency key was used for a different payment");
    }
}
