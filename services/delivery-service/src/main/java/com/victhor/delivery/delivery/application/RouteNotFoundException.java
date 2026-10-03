package com.victhor.delivery.delivery.application;

public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException() {
        super("No route connects the delivery locations");
    }
}
