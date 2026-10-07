package com.victhor.delivery.user.api;

import java.net.URI;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.user.application.AuthenticationService;
import com.victhor.delivery.user.application.UserProfileService;

@RestController
@RequestMapping("/api/users/auth")
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final UserProfileService users;

    public AuthenticationController(AuthenticationService authentication, UserProfileService users) {
        this.authentication = authentication;
        this.users = users;
    }

    @PostMapping("/register")
    public ResponseEntity<UserProfileResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        var response = UserProfileResponse.from(authentication.register(request.name(), request.email(), request.password()));
        return ResponseEntity.created(URI.create("/api/users/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        var token = authentication.login(request.email(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(new TokenResponse(token.value(), "Bearer", token.expiresIn()));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal Jwt principal) {
        var user = users.findById(CurrentUser.id(principal));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(UserProfileResponse.from(user));
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }
}
