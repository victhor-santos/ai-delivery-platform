package com.victhor.delivery.payment.application;

public class RemoteServiceUnavailableException extends RuntimeException {

    public RemoteServiceUnavailableException() {
        super("The remote service did not provide a valid response");
    }

    public RemoteServiceUnavailableException(Throwable cause) {
        super("The remote service did not provide a valid response", cause);
    }
}
