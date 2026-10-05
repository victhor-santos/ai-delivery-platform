package com.victhor.delivery.user.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping
    public ResponseEntity<UserProfileResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var response = UserProfileResponse.from(users.create(request.name(), request.email()));
        return ResponseEntity.created(URI.create("/api/users/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public UserProfileResponse findById(@PathVariable UUID id) {
        return UserProfileResponse.from(users.findById(id));
    }

    @PutMapping("/{id}/profile")
    public UserProfileResponse update(@PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return UserProfileResponse.from(users.updateName(id, request.name()));
    }
}
