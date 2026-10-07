package com.victhor.delivery.order.api;

import java.util.UUID;

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

    @PostMapping("/api/orders/{id}/delivery")
    public OrderDeliveryResponse requestDelivery(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        var receipt = deliveries.requestDelivery(id, CurrentCustomer.id(principal));
        return new OrderDeliveryResponse(receipt.orderId(), receipt.deliveryId(), receipt.status());
    }

    public record OrderDeliveryResponse(UUID orderId, UUID deliveryId, String status) {
    }
}
