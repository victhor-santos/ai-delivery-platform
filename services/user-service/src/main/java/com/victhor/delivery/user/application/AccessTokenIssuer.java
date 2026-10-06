package com.victhor.delivery.user.application;

import java.util.UUID;

public interface AccessTokenIssuer {

    AccessToken issue(UUID userId);
}
