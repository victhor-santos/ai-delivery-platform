package com.victhor.delivery.catalog.application;

public class MenuItemNotFoundException extends RuntimeException {

    public MenuItemNotFoundException() {
        super("Menu item not found");
    }
}
