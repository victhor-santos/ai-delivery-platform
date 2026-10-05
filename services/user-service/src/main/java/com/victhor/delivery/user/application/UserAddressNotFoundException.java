package com.victhor.delivery.user.application;

public class UserAddressNotFoundException extends RuntimeException {

    public UserAddressNotFoundException() {
        super("User address not found");
    }
}
