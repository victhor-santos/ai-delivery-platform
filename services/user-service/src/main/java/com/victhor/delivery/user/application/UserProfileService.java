package com.victhor.delivery.user.application;

import java.util.UUID;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

public class UserProfileService {

    private final UserRepository users;

    public UserProfileService(UserRepository users) {
        this.users = users;
    }

    public UserProfile create(String name, String email) {
        return users.save(UserProfile.create(name, new EmailAddress(email)));
    }

    public UserProfile findById(UUID id) {
        return users.findById(id).orElseThrow(UserNotFoundException::new);
    }

    public UserProfile updateName(UUID id, String name) {
        return users.updateName(id, UserProfile.normalizeName(name))
                .orElseThrow(UserNotFoundException::new);
    }
}
