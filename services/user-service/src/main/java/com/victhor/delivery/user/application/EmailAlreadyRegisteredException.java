package com.victhor.delivery.user.application;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email address is already registered");
    }
}
