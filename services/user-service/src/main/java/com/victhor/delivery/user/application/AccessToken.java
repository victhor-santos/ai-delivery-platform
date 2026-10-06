package com.victhor.delivery.user.application;

import java.time.Instant;

public record AccessToken(String value, Instant expiresAt, long expiresIn) {

    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + ", expiresIn=" + expiresIn + "]";
    }
}
