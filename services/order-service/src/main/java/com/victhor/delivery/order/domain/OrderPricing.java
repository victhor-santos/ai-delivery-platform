package com.victhor.delivery.order.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrderPricing(List<OrderItem> items, BigDecimal total) {

    public static final int MAX_ITEMS = 50;
    public static final String CURRENCY = "BRL";
    public static final BigDecimal MAX_TOTAL = new BigDecimal("494999999950.50");

    public OrderPricing(List<OrderItem> items) {
        this(items, calculateTotal(validateItems(items)));
    }

    public OrderPricing {
        items = validateItems(items);
        BigDecimal calculated = calculateTotal(items);
        if (total == null || total.compareTo(calculated) != 0) {
            throw new IllegalArgumentException("Order total must match its item snapshots");
        }
        total = calculated;
    }

    private static List<OrderItem> validateItems(List<OrderItem> items) {
        Objects.requireNonNull(items, "Order items are required");
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("An order must contain between 1 and 50 distinct items");
        }
        List<OrderItem> copied = List.copyOf(items);
        var identifiers = new HashSet<UUID>();
        for (OrderItem item : copied) {
            if (!identifiers.add(item.menuItemId())) {
                throw new IllegalArgumentException("An order cannot contain duplicate menu items");
            }
        }
        return copied;
    }

    private static BigDecimal calculateTotal(List<OrderItem> items) {
        BigDecimal total = new BigDecimal("0.00");
        for (OrderItem item : items) {
            total = total.add(item.lineTotal());
        }
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new IllegalArgumentException("Order total exceeds its supported maximum");
        }
        return total;
    }
}
