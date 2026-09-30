package com.victhor.delivery.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTests {

    @Mock
    private RestaurantRepository restaurants;

    private RestaurantService service;

    @BeforeEach
    void setUp() {
        service = new RestaurantService(restaurants);
    }

    @Test
    void createsAndSavesAnActiveRestaurant() {
        when(restaurants.save(any(Restaurant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Restaurant created = service.create("  Cantina Central  ");

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Cantina Central");
        assertThat(created.active()).isTrue();
        verify(restaurants).save(created);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n" })
    void rejectsInvalidNamesBeforeSaving(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(name));

        verifyNoInteractions(restaurants);
    }

    @Test
    void createsARestaurantWithPickupLocation() {
        var location = new PickupLocation(-23.5505, -46.6333);
        when(restaurants.save(any(Restaurant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Restaurant created = service.create("Cantina", location);

        assertThat(created.pickupLocation()).isEqualTo(location);
        verify(restaurants).save(created);
    }

    @Test
    void returnsTheRestaurantAfterUpdatingItsPickupLocation() {
        UUID id = UUID.randomUUID();
        var location = new PickupLocation(-23.5505, -46.6333);
        var updated = new Restaurant(id, "Cantina", false, location);
        when(restaurants.updatePickupLocation(id, location)).thenReturn(Optional.of(updated));

        assertThat(service.updatePickupLocation(id, location)).isEqualTo(updated);
    }

    @Test
    void reportsAMissingRestaurantWhenUpdatingItsLocation() {
        UUID id = UUID.randomUUID();
        var location = new PickupLocation(-23.5505, -46.6333);
        when(restaurants.updatePickupLocation(id, location)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePickupLocation(id, location))
                .isInstanceOf(RestaurantNotFoundException.class);
    }

    @Test
    void rejectsRemovingThePickupLocation() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.updatePickupLocation(UUID.randomUUID(), null));

        verifyNoInteractions(restaurants);
    }

    @Test
    void rejectsAnOverlongNameBeforeSaving() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("a".repeat(121)));

        verifyNoInteractions(restaurants);
    }

    @Test
    void findsAnExistingRestaurant() {
        Restaurant restaurant = Restaurant.create("Cantina Central");
        when(restaurants.findById(restaurant.id())).thenReturn(Optional.of(restaurant));

        assertThat(service.findById(restaurant.id())).isEqualTo(restaurant);
    }

    @Test
    void reportsAnUnknownRestaurant() {
        UUID id = UUID.randomUUID();
        when(restaurants.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(RestaurantNotFoundException.class);
    }

    @Test
    void returnsTheRequestedPage() {
        var page = new RestaurantPage(List.of(Restaurant.create("Cantina Central")), 2, 20, 41);
        when(restaurants.findAll(2, 20)).thenReturn(page);

        assertThat(service.findAll(2, 20)).isEqualTo(page);
    }

    @Test
    void acceptsTheMaximumPageSize() {
        var page = new RestaurantPage(List.of(), 0, 100, 0);
        when(restaurants.findAll(0, 100)).thenReturn(page);

        assertThat(service.findAll(0, 100)).isEqualTo(page);
    }

    @ParameterizedTest
    @CsvSource({ "-1,20", "0,0", "0,-1", "0,101", "2147483647,2" })
    void rejectsInvalidPaginationBeforeQuerying(int page, int size) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAll(page, size));

        verifyNoInteractions(restaurants);
    }
}
