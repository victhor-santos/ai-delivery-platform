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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.catalog.application.MenuItemService;

@RestController
@RequestMapping("/api/catalog/restaurants/{restaurantId}/menu-items")
public class MenuItemController {

    private final MenuItemService menuItems;

    public MenuItemController(MenuItemService menuItems) {
        this.menuItems = menuItems;
    }

    @PostMapping
    public ResponseEntity<MenuItemResponse> create(@PathVariable UUID restaurantId,
            @Valid @RequestBody CreateMenuItemRequest request) {
        var response = MenuItemResponse.from(menuItems.create(restaurantId, request.name(),
                request.description(), request.price()));
        var location = URI.create("/api/catalog/restaurants/" + restaurantId + "/menu-items/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public MenuItemResponse findById(@PathVariable UUID restaurantId, @PathVariable UUID id) {
        return MenuItemResponse.from(menuItems.findById(restaurantId, id));
    }

    @PutMapping("/{id}")
    public MenuItemResponse update(@PathVariable UUID restaurantId, @PathVariable UUID id,
            @Valid @RequestBody UpdateMenuItemRequest request) {
        return MenuItemResponse.from(menuItems.update(restaurantId, id, request.name(), request.description(),
                request.price(), request.available()));
    }

    @GetMapping
    public MenuItemPageResponse findAll(@PathVariable UUID restaurantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MenuItemService.MAX_PAGE_SIZE) int size) {
        return MenuItemPageResponse.from(menuItems.findAll(restaurantId, page, size));
    }
}
