package com.victhor.delivery.user.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.domain.UserProfile;

public interface AuthAccountRepository {

    UserProfile register(UserProfile profile, String passwordHash, Role role);

    Optional<AuthAccount> findByEmail(EmailAddress email);

    Optional<AuthAccount> findByUserId(UUID userId);

    void changePasswordHash(UUID userId, String passwordHash);
}
