package com.victhor.delivery.user.application;

import java.util.Optional;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

public interface AuthAccountRepository {

    UserProfile register(UserProfile profile, String passwordHash);

    Optional<AuthAccount> findByEmail(EmailAddress email);
}
