package com.victhor.delivery.catalog.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.catalog.application.RestaurantService;

@RestController
@RequestMapping("/api/catalog/restaurants")
public class RestaurantController {

    private final RestaurantService restaurants;

    public RestaurantController(RestaurantService restaurants) {
        this.restaurants = restaurants;
    }

    @PostMapping
    public ResponseEntity<RestaurantResponse> create(@Valid @RequestBody CreateRestaurantRequest request) {
        var response = RestaurantResponse.from(restaurants.create(request.name()));
        return ResponseEntity.created(URI.create("/api/catalog/restaurants/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public RestaurantResponse findById(@PathVariable UUID id) {
        return RestaurantResponse.from(restaurants.findById(id));
    }

    @GetMapping
    public RestaurantPageResponse findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(RestaurantService.MAX_PAGE_SIZE) int size) {
        return RestaurantPageResponse.from(restaurants.findAll(page, size));
    }
}
