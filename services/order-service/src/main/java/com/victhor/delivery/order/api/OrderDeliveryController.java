package com.victhor.delivery.order.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.order.application.OrderDeliveryService;

@RestController
public class OrderDeliveryController {

    private final OrderDeliveryService deliveries;

    public OrderDeliveryController(OrderDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    /** Accepted, not created: the delivery appears in the Delivery Service once it consumes the request. */
    @PostMapping("/api/orders/{id}/delivery")
    public ResponseEntity<OrderDeliveryResponse> requestDelivery(@AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id) {
        var request = deliveries.requestDelivery(id, CurrentCustomer.id(principal));
        return ResponseEntity.accepted().body(new OrderDeliveryResponse(request.orderId(), "REQUESTED"));
    }

    public record OrderDeliveryResponse(UUID orderId, String status) {
    }
}
