package com.victhor.delivery.user.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.user.domain.UserProfile;

public interface UserRepository {

    UserProfile save(UserProfile user);

    Optional<UserProfile> findById(UUID id);

    Optional<UserProfile> updateName(UUID id, String name);
}
