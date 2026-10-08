package com.victhor.delivery.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
        Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt, Instant deliveryRequestedAt,
        OrderPricing pricing, UUID customerId, Instant paymentRequestedAt, UUID paymentId) {

    public Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
            Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt) {
        this(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt, null, null, null);
    }

    public Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
            Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt, Instant deliveryRequestedAt) {
        this(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt, deliveryRequestedAt,
                null, null);
    }

    /** Restores an order created before customers were recorded; it has no owner. */
    public Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
            Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt, Instant deliveryRequestedAt,
            OrderPricing pricing) {
        this(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt, deliveryRequestedAt,
                pricing, null);
    }

    /** Restores an order placed before payments were linked to it. */
    public Order(UUID id, UUID restaurantId, DeliveryDestination destination, OrderStatus status,
            Instant createdAt, Instant updatedAt, Instant confirmedAt, Instant cancelledAt, Instant deliveryRequestedAt,
            OrderPricing pricing, UUID customerId) {
        this(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt, deliveryRequestedAt,
                pricing, customerId, null, null);
    }

    public Order {
        Objects.requireNonNull(id, "Order id is required");
        Objects.requireNonNull(restaurantId, "Restaurant id is required");
        Objects.requireNonNull(destination, "Destination is required");
        Objects.requireNonNull(status, "Order status is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
        Objects.requireNonNull(updatedAt, "Update time is required");
        if (updatedAt.isBefore(createdAt)
                || (confirmedAt != null && confirmedAt.isBefore(createdAt))
                || (cancelledAt != null && cancelledAt.isBefore(confirmedAt == null ? createdAt : confirmedAt))
                || (deliveryRequestedAt != null && (status != OrderStatus.CONFIRMED || confirmedAt == null
                    || deliveryRequestedAt.isBefore(confirmedAt)))) {
            throw new IllegalArgumentException("Order timestamps must follow the lifecycle sequence");
        }
        boolean consistent = switch (status) {
            case CREATED -> confirmedAt == null && cancelledAt == null && updatedAt.equals(createdAt);
            case CONFIRMED -> confirmedAt != null && cancelledAt == null
                    && updatedAt.equals(deliveryRequestedAt == null ? confirmedAt : deliveryRequestedAt);
            case CANCELLED -> cancelledAt != null && updatedAt.equals(cancelledAt);
        };
        if (!consistent) {
            throw new IllegalArgumentException("Order timestamps must match its status");
        }
        if ((paymentRequestedAt != null && (status != OrderStatus.CREATED || pricing == null
                    || paymentRequestedAt.isBefore(createdAt)))
                || (paymentId != null && status != OrderStatus.CONFIRMED)) {
            throw new IllegalArgumentException("Order payment must match its status");
        }
    }

    public static Order create(UUID customerId, UUID restaurantId, DeliveryDestination destination,
            OrderPricing pricing, Instant now) {
        Objects.requireNonNull(customerId, "Customer id is required for a new order");
        Objects.requireNonNull(pricing, "Pricing is required for a new order");
        return new Order(UUID.randomUUID(), restaurantId, destination, OrderStatus.CREATED, now, now, null, null,
                null, pricing, customerId, null, null);
    }

    /** Orders created before customers were recorded belong to nobody. */
    public boolean isPlacedBy(UUID customer) {
        return customerId != null && customerId.equals(customer);
    }

    public Order confirm(Instant now) {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderStateConflictException();
        }
        if (status == OrderStatus.CONFIRMED) {
            return this;
        }
        return new Order(id, restaurantId, destination, OrderStatus.CONFIRMED, createdAt, now, now, null, null, pricing,
                customerId, null, paymentId);
    }

    /**
     * Marks the order as awaiting a simulated charge. While the payment is pending the order cannot be cancelled,
     * so an approval never lands on a cancelled order.
     */
    public Order requestPayment(Instant now) {
        if (status != OrderStatus.CREATED || pricing == null) {
            throw new OrderStateConflictException("Only a created order with a total can be paid");
        }
        if (paymentRequestedAt != null) {
            return this;
        }
        return new Order(id, restaurantId, destination, status, createdAt, updatedAt, null, null, null, pricing,
                customerId, now, null);
    }

    /** Confirms the order with the approved payment that settled its pending request. */
    public Order confirmPayment(UUID approvedPaymentId, Instant now) {
        Objects.requireNonNull(approvedPaymentId, "Payment id is required");
        if (status == OrderStatus.CONFIRMED && approvedPaymentId.equals(paymentId)) {
            return this;
        }
        if (status != OrderStatus.CREATED || paymentRequestedAt == null) {
            throw new OrderStateConflictException("Only an order awaiting payment can be confirmed by one");
        }
        return new Order(id, restaurantId, destination, OrderStatus.CONFIRMED, createdAt, now, now, null, null, pricing,
                customerId, null, approvedPaymentId);
    }

    /** Ends a pending payment that charged nothing, allowing another attempt or a cancellation. */
    public Order releasePayment() {
        if (paymentRequestedAt == null) {
            return this;
        }
        return new Order(id, restaurantId, destination, status, createdAt, updatedAt, confirmedAt, cancelledAt,
                deliveryRequestedAt, pricing, customerId, null, paymentId);
    }

    public Order cancel(Instant now) {
        if (deliveryRequestedAt != null) {
            throw new OrderStateConflictException("An order with a delivery request cannot be cancelled");
        }
        if (paymentRequestedAt != null || paymentId != null) {
            throw new OrderStateConflictException("A paid order or one awaiting payment cannot be cancelled");
        }
        if (status == OrderStatus.CANCELLED) {
            return this;
        }
        return new Order(id, restaurantId, destination, OrderStatus.CANCELLED, createdAt, now, confirmedAt, now,
                null, pricing, customerId, null, null);
    }

    public Order requestDelivery(Instant now) {
        if (status != OrderStatus.CONFIRMED) {
            throw new OrderStateConflictException("Only a confirmed order can request delivery");
        }
        if (deliveryRequestedAt != null) {
            return this;
        }
        return new Order(id, restaurantId, destination, status, createdAt, now, confirmedAt, null, now, pricing,
                customerId, null, paymentId);
    }
}
