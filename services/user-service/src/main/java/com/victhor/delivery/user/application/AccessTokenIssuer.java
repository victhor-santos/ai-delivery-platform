package com.victhor.delivery.user.application;

import java.util.UUID;

import com.victhor.delivery.user.domain.Role;

public interface AccessTokenIssuer {

    AccessToken issue(UUID userId, Role role);
}
