package com.victhor.delivery.user.api;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import com.victhor.delivery.user.application.UserNotFoundException;

/** Resolves the caller from the validated token; other users' resources are reported as absent. */
final class CurrentUser {

    private CurrentUser() {
    }

    static UUID id(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }

    static UUID requireSelf(Jwt principal, UUID userId) {
        if (!id(principal).equals(userId)) {
            throw new UserNotFoundException();
        }
        return userId;
    }
}
