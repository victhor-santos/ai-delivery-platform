package com.victhor.delivery.payment.application;

/** The order does not exist or belongs to another customer; both look the same to the caller. */
public class PaymentOrderNotFoundException extends RuntimeException {
}
