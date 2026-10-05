package com.victhor.delivery.user.api;

import java.util.UUID;

import com.victhor.delivery.user.domain.UserProfile;

public record UserProfileResponse(UUID id, String name, String email) {

    static UserProfileResponse from(UserProfile user) {
        return new UserProfileResponse(user.id(), user.name(), user.email().value());
    }
}
