package com.victhor.delivery.delivery.application;

public class StaleRoutePlanException extends RuntimeException {

    public StaleRoutePlanException() {
        super("Delivery changed while its route was calculated");
    }
}
