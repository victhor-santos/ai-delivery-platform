package com.victhor.delivery.payment.application;

public class OrderAlreadyPaidException extends RuntimeException {

    public OrderAlreadyPaidException() {
        super("The order already has an approved payment");
    }
}
