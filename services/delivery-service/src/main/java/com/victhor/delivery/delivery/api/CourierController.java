package com.victhor.delivery.delivery.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.delivery.application.CourierService;

@RestController
@RequestMapping("/api/deliveries/couriers")
public class CourierController {

    private final CourierService couriers;

    public CourierController(CourierService couriers) {
        this.couriers = couriers;
    }

    @PostMapping
    public ResponseEntity<CourierResponse> create() {
        var response = CourierResponse.from(couriers.create());
        return ResponseEntity.created(URI.create("/api/deliveries/couriers/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public CourierResponse findById(@PathVariable UUID id) {
        return CourierResponse.from(couriers.findById(id));
    }
}
