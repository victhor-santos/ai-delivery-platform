package com.victhor.delivery.delivery.application;

public class RoutePlanNotFoundException extends RuntimeException {

    public RoutePlanNotFoundException() {
        super("Delivery has no saved route plan");
    }
}
