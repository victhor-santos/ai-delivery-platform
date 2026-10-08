package com.victhor.delivery.order.application;

/** Another payment intent for the order is still pending; it must be repeated with its own key. */
public class PaymentInProgressException extends RuntimeException {
}
