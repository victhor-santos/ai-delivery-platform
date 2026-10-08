package com.victhor.delivery.order.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderItemSelection;
import com.victhor.delivery.order.domain.OrderPricing;

public class OrderService {

    private final OrderRepository orders;
    private final CatalogLookup catalog;
    private final Clock clock;

    public OrderService(OrderRepository orders, CatalogLookup catalog, Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Order create(UUID customerId, UUID restaurantId, DeliveryDestination destination,
            List<OrderItemSelection> selections) {
        Objects.requireNonNull(customerId, "Customer id is required");
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        Objects.requireNonNull(destination, "Destination is required");
        List<OrderItemSelection> requested = validateSelections(selections);
        if (!catalog.isRestaurantActive(restaurantId)) {
            throw new CatalogSelectionConflictException();
        }
        var items = new ArrayList<OrderItem>(requested.size());
        for (OrderItemSelection selection : requested) {
            var menuItem = catalog.findMenuItem(restaurantId, selection.menuItemId());
            if (!menuItem.available()) {
                throw new CatalogSelectionConflictException();
            }
            items.add(new OrderItem(selection.menuItemId(), menuItem.name(), selection.quantity(), menuItem.price()));
        }
        return orders.save(Order.create(customerId, restaurantId, destination, new OrderPricing(items), now()));
    }

    /** Another customer's order is reported as absent so its existence is not revealed. */
    public Order findById(UUID id, UUID customerId) {
        return orders.findById(id).filter(order -> order.isPlacedBy(customerId))
                .orElseThrow(OrderNotFoundException::new);
    }

    public Order cancel(UUID id, UUID customerId) {
        findById(id, customerId);
        return orders.cancel(id, now()).orElseThrow(OrderNotFoundException::new);
    }

    private List<OrderItemSelection> validateSelections(List<OrderItemSelection> selections) {
        if (selections == null || selections.isEmpty() || selections.size() > OrderPricing.MAX_ITEMS) {
            throw new IllegalArgumentException("An order must contain between 1 and 50 distinct items");
        }
        var identifiers = new HashSet<UUID>();
        for (OrderItemSelection selection : selections) {
            if (selection == null || !identifiers.add(selection.menuItemId())) {
                throw new IllegalArgumentException("Order selections must be non-null and distinct");
            }
        }
        return List.copyOf(selections);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
