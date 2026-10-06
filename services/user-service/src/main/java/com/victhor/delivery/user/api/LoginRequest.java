package com.victhor.delivery.user.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(@NotBlank @Size(max = EmailAddress.MAX_LENGTH) String email,
        @NotBlank @Size(max = PasswordPolicy.MAX_BYTES) String password) {

    public LoginRequest {
        if (email != null) {
            email = email.strip();
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
