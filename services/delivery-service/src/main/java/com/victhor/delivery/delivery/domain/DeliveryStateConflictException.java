package com.victhor.delivery.delivery.domain;

public class DeliveryStateConflictException extends RuntimeException {

    public DeliveryStateConflictException(String message) {
        super(message);
    }
}
