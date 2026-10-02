package com.victhor.delivery.delivery.application;

public class CourierNotFoundException extends RuntimeException {

    public CourierNotFoundException() {
        super("Courier not found");
    }
}
