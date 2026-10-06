package com.victhor.delivery.order.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.order.application.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody CreateOrderRequest request) {
        var items = request.items().stream().map(OrderItemRequest::toDomain).toList();
        var response = OrderResponse.from(orders.create(CurrentCustomer.id(principal), request.restaurantId(),
                request.destination().toDomain(), items));
        return ResponseEntity.created(URI.create("/api/orders/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public OrderResponse findById(@PathVariable UUID id) {
        return OrderResponse.from(orders.findById(id));
    }

    @PostMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable UUID id) {
        return OrderResponse.from(orders.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id) {
        return OrderResponse.from(orders.cancel(id));
    }
}
