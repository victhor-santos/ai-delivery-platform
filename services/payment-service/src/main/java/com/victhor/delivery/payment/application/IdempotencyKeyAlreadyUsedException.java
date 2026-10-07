package com.victhor.delivery.payment.application;

/** Raised by the repository when a concurrent request stored the same key first. */
public class IdempotencyKeyAlreadyUsedException extends RuntimeException {

    public IdempotencyKeyAlreadyUsedException() {
        super("Idempotency key already used");
    }
}
