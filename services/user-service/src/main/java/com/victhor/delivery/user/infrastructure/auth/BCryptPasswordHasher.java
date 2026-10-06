package com.victhor.delivery.user.infrastructure.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.victhor.delivery.user.application.PasswordHasher;
import com.victhor.delivery.user.domain.PasswordPolicy;

public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(String password) {
        PasswordPolicy.validateRegistration(password);
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return PasswordPolicy.canMatch(password) && encoder.matches(password, hash);
    }
}
