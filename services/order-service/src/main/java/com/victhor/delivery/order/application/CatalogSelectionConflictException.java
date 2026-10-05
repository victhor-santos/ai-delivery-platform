package com.victhor.delivery.order.application;

public class CatalogSelectionConflictException extends RuntimeException {

    public CatalogSelectionConflictException() {
        super("The restaurant or selected menu items are not available for this order");
    }
}
