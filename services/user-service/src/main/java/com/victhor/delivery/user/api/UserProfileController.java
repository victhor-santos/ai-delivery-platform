package com.victhor.delivery.user.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.user.application.UserProfileService;

@RestController
@RequestMapping("/api/users")
public class UserProfileController {

    private final UserProfileService users;

    public UserProfileController(UserProfileService users) {
        this.users = users;
    }

    @GetMapping("/{id}")
    public UserProfileResponse findById(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        return UserProfileResponse.from(users.findById(CurrentUser.requireSelf(principal, id)));
    }

    @PutMapping("/{id}/profile")
    public UserProfileResponse update(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return UserProfileResponse.from(users.updateName(CurrentUser.requireSelf(principal, id), request.name()));
    }
}
