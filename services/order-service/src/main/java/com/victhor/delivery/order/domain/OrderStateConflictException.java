package com.victhor.delivery.order.domain;

public class OrderStateConflictException extends RuntimeException {

    public OrderStateConflictException() {
        this("A cancelled order cannot be confirmed");
    }

    public OrderStateConflictException(String message) {
        super(message);
    }
}
