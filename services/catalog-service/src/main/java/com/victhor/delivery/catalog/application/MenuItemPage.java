package com.victhor.delivery.catalog.application;

import java.util.List;

import com.victhor.delivery.catalog.domain.MenuItem;

public record MenuItemPage(List<MenuItem> content, int page, int size, long totalElements) {

    public MenuItemPage {
        content = List.copyOf(content);
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
