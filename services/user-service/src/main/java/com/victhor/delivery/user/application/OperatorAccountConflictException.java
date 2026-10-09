package com.victhor.delivery.user.application;

/** The configured operator e-mail already belongs to a customer; promoting it would hand over that account. */
public class OperatorAccountConflictException extends IllegalStateException {

    public OperatorAccountConflictException() {
        super("The configured operator e-mail belongs to a customer account; choose another USER_OPERATOR_EMAIL");
    }
}
