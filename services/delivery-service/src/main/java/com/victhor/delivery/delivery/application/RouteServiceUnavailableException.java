package com.victhor.delivery.delivery.application;

public class RouteServiceUnavailableException extends RuntimeException {

    public RouteServiceUnavailableException() {
        super("Route service unavailable");
    }

    public RouteServiceUnavailableException(Throwable cause) {
        super("Route service unavailable", cause);
    }
}
