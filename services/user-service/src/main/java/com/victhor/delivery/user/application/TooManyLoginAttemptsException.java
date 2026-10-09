package com.victhor.delivery.user.application;

import java.time.Duration;

public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many login attempts");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, as the Retry-After header expects. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
