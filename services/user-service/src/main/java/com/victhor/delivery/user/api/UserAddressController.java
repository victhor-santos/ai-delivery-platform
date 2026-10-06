package com.victhor.delivery.user.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.user.application.UserAddressService;

@RestController
@RequestMapping("/api/users/{userId}/addresses")
public class UserAddressController {

    private final UserAddressService addresses;

    public UserAddressController(UserAddressService addresses) {
        this.addresses = addresses;
    }

    @PostMapping
    public ResponseEntity<UserAddressResponse> create(@PathVariable UUID userId,
            @Valid @RequestBody UserAddressRequest request) {
        var response = UserAddressResponse.from(addresses.create(userId, request.label(), request.address(),
                request.latitude(), request.longitude()));
        var location = URI.create("/api/users/" + userId + "/addresses/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{addressId}")
    public UserAddressResponse findById(@PathVariable UUID userId, @PathVariable UUID addressId) {
        return UserAddressResponse.from(addresses.findById(userId, addressId));
    }

    @PutMapping("/{addressId}")
    public UserAddressResponse update(@PathVariable UUID userId, @PathVariable UUID addressId,
            @Valid @RequestBody UserAddressRequest request) {
        return UserAddressResponse.from(addresses.update(userId, addressId, request.label(), request.address(),
                request.latitude(), request.longitude()));
    }

    @GetMapping
    public UserAddressPageResponse findAll(@PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(UserAddressService.MAX_PAGE_SIZE) int size) {
        return UserAddressPageResponse.from(addresses.findAll(userId, page, size));
    }
}
