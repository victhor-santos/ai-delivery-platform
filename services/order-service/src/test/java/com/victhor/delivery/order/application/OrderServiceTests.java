package com.victhor.delivery.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderItemSelection;
import com.victhor.delivery.order.domain.OrderPricing;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private static final Instant DATABASE_TIME = Instant.parse("2026-09-30T12:00:00.123456Z");
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final UUID FIRST_ITEM = UUID.randomUUID();
    private static final UUID SECOND_ITEM = UUID.randomUUID();
    private static final DeliveryDestination DESTINATION = new DeliveryDestination("Rua Central, 10", 0, 0);

    @Mock
    private OrderRepository orders;

    @Mock
    private CatalogLookup catalog;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void snapshotsCatalogItemsBeforeSavingUsingTheClockAtPostgresPrecision() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, FIRST_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato executivo", new BigDecimal("25.50"), true));
        when(catalog.findMenuItem(RESTAURANT_ID, SECOND_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Suco", new BigDecimal("8.00"), true));
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Order created = service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 2), new OrderItemSelection(SECOND_ITEM, 1)));

        assertThat(created.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(created.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(created.destination()).isEqualTo(DESTINATION);
        assertThat(created.createdAt()).isEqualTo(DATABASE_TIME);
        assertThat(created.pricing().items()).containsExactly(
                new OrderItem(FIRST_ITEM, "Prato executivo", 2, new BigDecimal("25.50")),
                new OrderItem(SECOND_ITEM, "Suco", 1, new BigDecimal("8.00")));
        assertThat(created.pricing().total()).isEqualTo(new BigDecimal("59.00"));
        var calls = inOrder(catalog, orders);
        calls.verify(catalog).isRestaurantActive(RESTAURANT_ID);
        calls.verify(catalog).findMenuItem(RESTAURANT_ID, FIRST_ITEM);
        calls.verify(catalog).findMenuItem(RESTAURANT_ID, SECOND_ITEM);
        calls.verify(orders).save(created);
        calls.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @MethodSource("invalidSelections")
    void validatesTheWholeSelectionBeforeAnyRemoteCall(List<OrderItemSelection> selections) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION, selections));

        verifyNoInteractions(catalog, orders);
    }

    static Stream<Arguments> invalidSelections() {
        var first = new OrderItemSelection(FIRST_ITEM, 1);
        return Stream.of(Arguments.of((Object) null), Arguments.of(List.of()),
                Arguments.of(Arrays.asList(first, null)),
                Arguments.of(List.of(first, new OrderItemSelection(FIRST_ITEM, 2))),
                Arguments.of(IntStream.range(0, OrderPricing.MAX_ITEMS + 1)
                        .mapToObj(index -> new OrderItemSelection(UUID.randomUUID(), 1)).toList()));
    }

    @Test
    void requiresCustomerRestaurantAndDestinationBeforeRemoteCalls() {
        var selections = List.of(new OrderItemSelection(FIRST_ITEM, 1));

        assertThatNullPointerException().isThrownBy(() -> service.create(null, RESTAURANT_ID, DESTINATION, selections));

        assertThatNullPointerException().isThrownBy(() -> service.create(CUSTOMER_ID, null, DESTINATION, selections));
        assertThatNullPointerException().isThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, null, selections));
        verifyNoInteractions(catalog, orders);
    }

    @Test
    void rejectsAnInactiveRestaurantWithoutFetchingProductsOrSaving() {
        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1))))
                .isInstanceOf(CatalogSelectionConflictException.class);

        var calls = inOrder(catalog);
        calls.verify(catalog).isRestaurantActive(RESTAURANT_ID);
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(orders);
    }

    @Test
    void propagatesAMissingRestaurantWithoutPersisting() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenThrow(new CatalogSelectionConflictException());

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1))))
                .isInstanceOf(CatalogSelectionConflictException.class);

        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsUnavailableProductsWithoutSavingAPartialComposition(boolean firstUnavailable) {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, FIRST_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", BigDecimal.TEN, !firstUnavailable));
        if (!firstUnavailable) {
            when(catalog.findMenuItem(RESTAURANT_ID, SECOND_ITEM))
                    .thenReturn(new CatalogLookup.CatalogMenuItem("Suco", BigDecimal.ONE, false));
        }

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1), new OrderItemSelection(SECOND_ITEM, 1))))
                .isInstanceOf(CatalogSelectionConflictException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void propagatesAMissingProductWithoutSavingThePreviouslyFetchedItem() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, FIRST_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", BigDecimal.TEN, true));
        when(catalog.findMenuItem(RESTAURANT_ID, SECOND_ITEM)).thenThrow(new CatalogSelectionConflictException());

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1), new OrderItemSelection(SECOND_ITEM, 1))))
                .isInstanceOf(CatalogSelectionConflictException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void propagatesRestaurantLookupFailuresWithoutSaving() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenThrow(new RemoteServiceUnavailableException());

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1))))
                .isInstanceOf(RemoteServiceUnavailableException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void propagatesProductLookupFailuresWithoutSavingThePreviouslyFetchedItem() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, FIRST_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", BigDecimal.TEN, true));
        when(catalog.findMenuItem(RESTAURANT_ID, SECOND_ITEM)).thenThrow(new RemoteServiceUnavailableException());

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1), new OrderItemSelection(SECOND_ITEM, 1))))
                .isInstanceOf(RemoteServiceUnavailableException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void rejectsAnInvalidSnapshotWithoutPersisting() {
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(RESTAURANT_ID, FIRST_ITEM))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", new BigDecimal("12.345"), true));

        assertThatIllegalArgumentException().isThrownBy(() -> service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION,
                List.of(new OrderItemSelection(FIRST_ITEM, 1))));

        verifyNoInteractions(orders);
    }

    @Test
    void acceptsTheMaximumCompositionAndCalculatesItsTotal() {
        var selections = IntStream.range(0, OrderPricing.MAX_ITEMS)
                .mapToObj(index -> new OrderItemSelection(UUID.randomUUID(), OrderItem.MAX_QUANTITY)).toList();
        when(catalog.isRestaurantActive(RESTAURANT_ID)).thenReturn(true);
        when(catalog.findMenuItem(any(), any()))
                .thenReturn(new CatalogLookup.CatalogMenuItem("Prato", OrderItem.MAX_UNIT_PRICE, true));
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION, selections);

        assertThat(created.pricing().items()).hasSize(OrderPricing.MAX_ITEMS);
        assertThat(created.pricing().total()).isEqualTo(OrderPricing.MAX_TOTAL);
        verify(orders).save(created);
    }

    @Test
    void readsAndTransitionsPersistedSnapshotsWithoutFetchingCatalogAgain() {
        var pricing = new OrderPricing(List.of(new OrderItem(FIRST_ITEM, "Nome preservado", 2, BigDecimal.TEN)));
        var original = Order.create(CUSTOMER_ID, RESTAURANT_ID, DESTINATION, pricing, DATABASE_TIME.minusSeconds(60));
        var confirmed = original.confirm(DATABASE_TIME);
        var cancelled = confirmed.cancel(DATABASE_TIME);
        when(orders.findById(original.id())).thenReturn(Optional.of(original));
        when(orders.confirm(original.id(), DATABASE_TIME)).thenReturn(Optional.of(confirmed));
        when(orders.cancel(original.id(), DATABASE_TIME)).thenReturn(Optional.of(cancelled));

        assertThat(service.findById(original.id()).pricing()).isSameAs(pricing);
        assertThat(service.confirm(original.id()).pricing()).isSameAs(pricing);
        assertThat(service.cancel(original.id()).pricing()).isSameAs(pricing);
        verifyNoInteractions(catalog);
    }

    @Test
    void reportsMissingOrdersOnAllOperations() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(OrderNotFoundException.class);
        verify(orders).confirm(id, DATABASE_TIME);
        verify(orders).cancel(id, DATABASE_TIME);
        verifyNoInteractions(catalog);
    }
}
