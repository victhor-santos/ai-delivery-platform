package com.victhor.delivery.catalog.api;

import java.util.List;

import com.victhor.delivery.catalog.application.MenuItemPage;

public record MenuItemPageResponse(List<MenuItemResponse> content, int page, int size,
        long totalElements, long totalPages) {

    static MenuItemPageResponse from(MenuItemPage result) {
        return new MenuItemPageResponse(result.content().stream().map(MenuItemResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
