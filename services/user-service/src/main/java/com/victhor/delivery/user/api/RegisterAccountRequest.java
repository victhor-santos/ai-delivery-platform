package com.victhor.delivery.user.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.PasswordPolicy;
import com.victhor.delivery.user.domain.UserProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterAccountRequest(@NotBlank @Size(max = UserProfile.MAX_NAME_LENGTH) String name,
        @NotBlank @Size(max = EmailAddress.MAX_LENGTH) String email,
        @NotBlank @Size(max = PasswordPolicy.MAX_BYTES) String password) {

    public RegisterAccountRequest {
        if (name != null) {
            name = name.strip();
        }
        if (email != null) {
            email = email.strip();
        }
    }

    @Override
    public String toString() {
        return "RegisterAccountRequest[redacted]";
    }
}
