package com.victhor.delivery.delivery.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.delivery.application.DeliveryRouteService;
import com.victhor.delivery.delivery.application.DeliveryService;

@RestController
@RequestMapping("/api/deliveries/{id}/route")
public class DeliveryRouteController {

    private final DeliveryRouteService routes;
    private final DeliveryService deliveries;

    public DeliveryRouteController(DeliveryRouteService routes, DeliveryService deliveries) {
        this.routes = routes;
        this.deliveries = deliveries;
    }

    @PostMapping
    public DeliveryRouteResponse plan(@PathVariable UUID id, @Valid @RequestBody PlanDeliveryRouteRequest request) {
        return DeliveryRouteResponse.from(routes.plan(id, request.toContext()));
    }

    @GetMapping
    public DeliveryRouteResponse find(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        deliveries.requireVisible(id, CurrentViewer.of(principal));
        return DeliveryRouteResponse.from(routes.findPlan(id));
    }
}
