package com.victhor.delivery.delivery.api;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.victhor.delivery.delivery.application.DeliveryViewer;

/** Resolves the caller from the validated token; the subject is the User service id. */
final class CurrentViewer {

    private CurrentViewer() {
    }

    static DeliveryViewer of(Jwt principal) {
        return new DeliveryViewer(UUID.fromString(principal.getSubject()),
                principal.getClaimAsStringList("roles").contains("OPERATOR"));
    }
}
