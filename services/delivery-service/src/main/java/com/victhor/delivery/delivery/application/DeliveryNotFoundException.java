package com.victhor.delivery.delivery.application;

public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException() {
        super("Delivery not found");
    }
}
