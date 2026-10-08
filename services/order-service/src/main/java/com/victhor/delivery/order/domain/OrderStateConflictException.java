package com.victhor.delivery.order.domain;

public class OrderStateConflictException extends RuntimeException {

    public OrderStateConflictException(String message) {
        super(message);
    }
}
