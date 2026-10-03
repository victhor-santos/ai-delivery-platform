package com.victhor.delivery.delivery.api;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.delivery.application.DeliveryRouteService;

@RestController
@RequestMapping("/api/deliveries/{id}/route")
public class DeliveryRouteController {

    private final DeliveryRouteService routes;

    public DeliveryRouteController(DeliveryRouteService routes) {
        this.routes = routes;
    }

    @PostMapping
    public DeliveryRouteResponse plan(@PathVariable UUID id, @Valid @RequestBody PlanDeliveryRouteRequest request) {
        return DeliveryRouteResponse.from(routes.plan(id, request.toContext()));
    }

    @GetMapping
    public DeliveryRouteResponse find(@PathVariable UUID id) {
        return DeliveryRouteResponse.from(routes.findPlan(id));
    }
}
