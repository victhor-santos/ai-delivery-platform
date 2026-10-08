package com.victhor.delivery.order.domain;

import java.util.regex.Pattern;

/**
 * Client-chosen key that identifies one payment intent for an order. The same rules as the Payment Service apply,
 * because the key is forwarded to it unchanged.
 */
public record IdempotencyKey(String value) {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 100;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]+");

    public IdempotencyKey {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH
                || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency key must have 8 to 100 letters, digits or the characters . _ : -");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
