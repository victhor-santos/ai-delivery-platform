package com.victhor.delivery.user.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.user.domain.UserProfile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateUserProfileRequest(@NotBlank @Size(max = UserProfile.MAX_NAME_LENGTH) String name) {

    public UpdateUserProfileRequest {
        if (name != null) {
            name = name.strip();
        }
    }
}
