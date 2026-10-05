package com.victhor.delivery.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.victhor.delivery.catalog.domain.MenuItem;

class MenuItemPageTests {

    @ParameterizedTest
    @CsvSource({ "0,20,0", "1,20,1", "20,20,1", "21,20,2", "40,20,2", "41,20,3",
            "9223372036854775807,1,9223372036854775807" })
    void countsEmptyCompleteAndPartialPages(long totalElements, int size, long expectedPages) {
        var page = new MenuItemPage(List.of(), 0, size, totalElements);

        assertThat(page.totalPages()).isEqualTo(expectedPages);
    }

    @Test
    void keepsAnImmutableSnapshotOfTheContent() {
        MenuItem menuItem = MenuItem.create(UUID.randomUUID(), "Pizza", null, new BigDecimal("25.50"));
        var content = new ArrayList<>(List.of(menuItem));
        var page = new MenuItemPage(content, 0, 20, 1);

        content.clear();

        assertThat(page.content()).containsExactly(menuItem);
        assertThatThrownBy(() -> page.content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
