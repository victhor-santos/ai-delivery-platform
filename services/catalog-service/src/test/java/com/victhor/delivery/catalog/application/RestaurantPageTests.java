package com.victhor.delivery.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.victhor.delivery.catalog.domain.Restaurant;

class RestaurantPageTests {

    @ParameterizedTest
    @CsvSource({ "0,20,0", "1,20,1", "20,20,1", "21,20,2", "40,20,2", "41,20,3" })
    void countsEmptyCompleteAndPartialPages(long totalElements, int size, long expectedPages) {
        var page = new RestaurantPage(List.of(), 0, size, totalElements);

        assertThat(page.totalPages()).isEqualTo(expectedPages);
    }

    @Test
    void keepsAnImmutableSnapshotOfTheContent() {
        Restaurant restaurant = Restaurant.create("Cantina Central");
        var content = new ArrayList<>(List.of(restaurant));
        var page = new RestaurantPage(content, 0, 20, 1);

        content.clear();

        assertThat(page.content()).containsExactly(restaurant);
        assertThatThrownBy(() -> page.content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
