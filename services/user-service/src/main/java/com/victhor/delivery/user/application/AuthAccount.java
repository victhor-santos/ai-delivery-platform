package com.victhor.delivery.user.application;

import java.util.UUID;

public record AuthAccount(UUID userId, String passwordHash) {

    @Override
    public String toString() {
        return "AuthAccount[userId=" + userId + "]";
    }
}
