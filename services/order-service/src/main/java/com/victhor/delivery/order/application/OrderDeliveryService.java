package com.victhor.delivery.order.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.victhor.delivery.order.domain.DeliveryRequest;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderStatus;
import com.victhor.delivery.order.domain.OrderStateConflictException;

public class OrderDeliveryService {

    private final OrderRepository orders;
    private final DeliveryRequestRepository requests;
    private final RestaurantLookup restaurants;
    private final DeliveryGateway deliveries;
    private final Clock clock;

    public OrderDeliveryService(OrderRepository orders, DeliveryRequestRepository requests,
            RestaurantLookup restaurants, DeliveryGateway deliveries, Clock clock) {
        this.orders = orders;
        this.requests = requests;
        this.restaurants = restaurants;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public DeliveryGateway.DeliveryReceipt requestDelivery(UUID orderId, UUID customerId) {
        Order order = orders.findById(orderId).filter(candidate -> candidate.isPlacedBy(customerId))
                .orElseThrow(OrderNotFoundException::new);
        if (order.status() != OrderStatus.CONFIRMED) {
            throw new OrderStateConflictException("Only a confirmed order can request delivery");
        }
        DeliveryRequest request = requests.findByOrderId(orderId).orElseGet(() -> prepare(order));
        return deliveries.createForOrder(request);
    }

    private DeliveryRequest prepare(Order order) {
        var restaurant = restaurants.findById(order.restaurantId());
        if (!restaurant.active() || restaurant.location() == null) {
            throw new DeliveryIntegrationConflictException();
        }
        return requests.prepare(new DeliveryRequest(order.id(), restaurant.location(), order.destination()),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
