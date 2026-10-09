package com.victhor.delivery.user.application;

import java.util.Objects;
import java.util.UUID;

import com.victhor.delivery.user.domain.Role;

public record AuthAccount(UUID userId, String passwordHash, Role role) {

    public AuthAccount {
        Objects.requireNonNull(role, "Account role is required");
    }

    @Override
    public String toString() {
        return "AuthAccount[userId=" + userId + ", role=" + role + "]";
    }
}
