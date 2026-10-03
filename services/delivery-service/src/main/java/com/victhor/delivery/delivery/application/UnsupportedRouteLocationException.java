package com.victhor.delivery.delivery.application;

public class UnsupportedRouteLocationException extends RuntimeException {

    public UnsupportedRouteLocationException() {
        super("Delivery locations are outside route coverage");
    }
}
