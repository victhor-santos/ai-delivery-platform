package com.victhor.delivery.order.application;

public class DeliveryIntegrationConflictException extends RuntimeException {

    public DeliveryIntegrationConflictException() {
        super("The restaurant or delivery data does not allow this request");
    }
}
