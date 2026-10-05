package com.victhor.delivery.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.catalog.domain.MenuItem;
import com.victhor.delivery.catalog.domain.Restaurant;

@ExtendWith(MockitoExtension.class)
class MenuItemServiceTests {

    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final BigDecimal PRICE = new BigDecimal("25.50");

    @Mock
    private MenuItemRepository menuItems;

    @Mock
    private RestaurantRepository restaurants;

    private MenuItemService service;

    @BeforeEach
    void setUp() {
        service = new MenuItemService(menuItems, restaurants);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void createsAndSavesAnAvailableItemForAnExistingRestaurantRegardlessOfItsActiveState(boolean active) {
        existingRestaurant(active);
        when(menuItems.save(any(MenuItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(RESTAURANT_ID, "  Pizza  ", "  Queijo  ", new BigDecimal("25.500"));

        assertThat(created.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(created.name()).isEqualTo("Pizza");
        assertThat(created.description()).isEqualTo("Queijo");
        assertThat(created.price()).isEqualTo(PRICE);
        assertThat(created.available()).isTrue();
        verify(menuItems).save(created);
    }

    @Test
    void rejectsCreationForAnUnknownRestaurantWithoutWritingAnItem() {
        when(restaurants.findById(RESTAURANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(RESTAURANT_ID, "Pizza", null, PRICE))
                .isInstanceOf(RestaurantNotFoundException.class);

        verifyNoInteractions(menuItems);
    }

    @Test
    void updatesDetailsAndAvailabilityWithoutChangingIdentityOrRestaurantOwnership() {
        existingRestaurant(false);
        UUID id = UUID.randomUUID();
        when(menuItems.update(any(MenuItem.class)))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        var updated = service.update(RESTAURANT_ID, id, "  Nova pizza  ", "  \t  ",
                new BigDecimal("30"), false);

        assertThat(updated).isEqualTo(new MenuItem(id, RESTAURANT_ID, "Nova pizza", null,
                new BigDecimal("30.00"), false));
        verify(menuItems).update(updated);
    }

    @Test
    void reportsAMissingOrForeignItemWhenUpdatingInsteadOfCreatingIt() {
        existingRestaurant(true);
        UUID id = UUID.randomUUID();
        var requested = new MenuItem(id, RESTAURANT_ID, "Pizza", null, PRICE, false);
        when(menuItems.update(requested)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(RESTAURANT_ID, id, "Pizza", null, PRICE, false))
                .isInstanceOf(MenuItemNotFoundException.class);

        verify(menuItems).update(requested);
    }

    @Test
    void rejectsUpdatesForAnUnknownRestaurantWithoutWritingAnItem() {
        when(restaurants.findById(RESTAURANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(RESTAURANT_ID, UUID.randomUUID(), "Pizza", null, PRICE, false))
                .isInstanceOf(RestaurantNotFoundException.class);

        verifyNoInteractions(menuItems);
    }

    @Test
    void findsAnUnavailableItemUsingBothRestaurantAndItemIdentity() {
        existingRestaurant(true);
        var menuItem = new MenuItem(UUID.randomUUID(), RESTAURANT_ID, "Pizza", null, PRICE, false);
        when(menuItems.findById(RESTAURANT_ID, menuItem.id())).thenReturn(Optional.of(menuItem));

        assertThat(service.findById(RESTAURANT_ID, menuItem.id())).isEqualTo(menuItem);

        verify(menuItems).findById(RESTAURANT_ID, menuItem.id());
    }

    @Test
    void reportsAMissingOrForeignItemUsingTheRestaurantScopedLookup() {
        existingRestaurant(true);
        UUID id = UUID.randomUUID();
        when(menuItems.findById(RESTAURANT_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(RESTAURANT_ID, id))
                .isInstanceOf(MenuItemNotFoundException.class);

        verify(menuItems).findById(RESTAURANT_ID, id);
    }

    @Test
    void rejectsLookupForAnUnknownRestaurantWithoutQueryingItems() {
        when(restaurants.findById(RESTAURANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(RESTAURANT_ID, UUID.randomUUID()))
                .isInstanceOf(RestaurantNotFoundException.class);

        verifyNoInteractions(menuItems);
    }

    @Test
    void returnsThePageRequestedForTheRestaurant() {
        existingRestaurant(true);
        var page = new MenuItemPage(List.of(MenuItem.create(RESTAURANT_ID, "Pizza", null, PRICE)), 2, 20, 41);
        when(menuItems.findAll(RESTAURANT_ID, 2, 20)).thenReturn(page);

        assertThat(service.findAll(RESTAURANT_ID, 2, 20)).isEqualTo(page);

        verify(menuItems).findAll(RESTAURANT_ID, 2, 20);
    }

    @ParameterizedTest
    @CsvSource({ "0,1", "0,100", "2147483647,1" })
    void acceptsPageSizeAndOffsetBoundaries(int page, int size) {
        existingRestaurant(true);
        var result = new MenuItemPage(List.of(), page, size, 0);
        when(menuItems.findAll(RESTAURANT_ID, page, size)).thenReturn(result);

        assertThat(service.findAll(RESTAURANT_ID, page, size)).isEqualTo(result);
    }

    @Test
    void rejectsListingForAnUnknownRestaurantInsteadOfReturningAnEmptyMenu() {
        when(restaurants.findById(RESTAURANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAll(RESTAURANT_ID, 0, 20))
                .isInstanceOf(RestaurantNotFoundException.class);

        verifyNoInteractions(menuItems);
    }

    @ParameterizedTest
    @CsvSource({ "-1,20", "0,0", "0,-1", "0,101", "2147483647,2" })
    void rejectsInvalidPaginationBeforeQueryingEitherRepository(int page, int size) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAll(RESTAURANT_ID, page, size));

        verifyNoInteractions(restaurants, menuItems);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsInvalidNamesBeforeLookingUpTheRestaurantOrWriting(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(RESTAURANT_ID, name, null, PRICE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(RESTAURANT_ID, UUID.randomUUID(), name, null, PRICE, true));

        verifyNoInteractions(restaurants, menuItems);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "0", "-0.01", "25.501", "100000000" })
    void rejectsInvalidPricesBeforeLookingUpTheRestaurantOrWriting(String supplied) {
        BigDecimal price = supplied == null ? null : new BigDecimal(supplied);

        assertThatIllegalArgumentException().isThrownBy(() -> service.create(RESTAURANT_ID, "Pizza", null, price));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(RESTAURANT_ID, UUID.randomUUID(), "Pizza", null, price, true));

        verifyNoInteractions(restaurants, menuItems);
    }

    @Test
    void rejectsOverlongDetailsBeforeLookingUpTheRestaurantOrWriting() {
        String name = "a".repeat(MenuItem.MAX_NAME_LENGTH + 1);
        String description = "a".repeat(MenuItem.MAX_DESCRIPTION_LENGTH + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> service.create(RESTAURANT_ID, name, null, PRICE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.create(RESTAURANT_ID, "Pizza", description, PRICE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(RESTAURANT_ID, UUID.randomUUID(), name, null, PRICE, true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(RESTAURANT_ID, UUID.randomUUID(), "Pizza", description, PRICE, true));

        verifyNoInteractions(restaurants, menuItems);
    }

    @Test
    void rejectsMissingRestaurantOrItemIdentityBeforeAccessingRepositories() {
        UUID id = UUID.randomUUID();

        assertThatNullPointerException().isThrownBy(() -> service.create(null, "Pizza", null, PRICE));
        assertThatNullPointerException()
                .isThrownBy(() -> service.update(null, id, "Pizza", null, PRICE, true));
        assertThatNullPointerException()
                .isThrownBy(() -> service.update(RESTAURANT_ID, null, "Pizza", null, PRICE, true));
        assertThatNullPointerException().isThrownBy(() -> service.findById(null, id));
        assertThatNullPointerException().isThrownBy(() -> service.findById(RESTAURANT_ID, null));
        assertThatNullPointerException().isThrownBy(() -> service.findAll(null, 0, 20));

        verifyNoInteractions(restaurants, menuItems);
    }

    private void existingRestaurant(boolean active) {
        when(restaurants.findById(RESTAURANT_ID))
                .thenReturn(Optional.of(new Restaurant(RESTAURANT_ID, "Cantina", active)));
    }
}
