package com.victhor.delivery.payment.api;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Resolves the customer from the validated token subject, never from the request body. */
final class CurrentCustomer {

    private CurrentCustomer() {
    }

    static UUID id(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
