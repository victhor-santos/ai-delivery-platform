package com.victhor.delivery.delivery.api;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.delivery.application.DeliveryService;
import com.victhor.delivery.delivery.domain.DeliveryStatus;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveries;

    public DeliveryController(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping
    public ResponseEntity<DeliveryResponse> create(@Valid @RequestBody CreateDeliveryRequest request) {
        var response = DeliveryResponse.from(deliveries.create(request.orderId(),
                request.origin().toDomain(), request.destination().toDomain()));
        return ResponseEntity.created(URI.create("/api/deliveries/" + response.id())).body(response);
    }

    /** Operators only, enforced by the security configuration. */
    @GetMapping
    public DeliveryPageResponse findPage(@RequestParam(required = false) DeliveryStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return DeliveryPageResponse.from(deliveries.findPage(status, page, size));
    }

    @GetMapping("/{id}")
    public DeliveryResponse findById(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.findById(id, CurrentViewer.of(principal)));
    }

    @GetMapping("/by-order/{orderId}")
    public DeliveryResponse findByOrderId(@AuthenticationPrincipal Jwt principal, @PathVariable UUID orderId) {
        return DeliveryResponse.from(deliveries.findByOrderId(orderId, CurrentViewer.of(principal)));
    }

    @PostMapping("/{id}/assign")
    public DeliveryResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignCourierRequest request) {
        return DeliveryResponse.from(deliveries.assign(id, request.courierId()));
    }

    @PostMapping("/{id}/pick-up")
    public DeliveryResponse pickUp(@PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.pickUp(id));
    }

    @PostMapping("/{id}/start-transit")
    public DeliveryResponse startTransit(@PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.startTransit(id));
    }

    @PostMapping("/{id}/arrive")
    public DeliveryResponse arrive(@PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.arrive(id));
    }

    @PostMapping("/{id}/complete")
    public DeliveryResponse complete(@PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public DeliveryResponse cancel(@PathVariable UUID id) {
        return DeliveryResponse.from(deliveries.cancel(id));
    }
}
