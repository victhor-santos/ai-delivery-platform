package com.victhor.delivery.order.application;

/** The key was already used for this order with another payment method. */
public class IdempotencyKeyReusedException extends RuntimeException {
}
