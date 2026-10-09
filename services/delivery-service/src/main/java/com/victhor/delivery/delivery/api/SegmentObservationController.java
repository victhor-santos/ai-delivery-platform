package com.victhor.delivery.delivery.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.victhor.delivery.delivery.application.DeliveryService;
import com.victhor.delivery.delivery.application.SegmentObservationService;

@RestController
@RequestMapping("/api/deliveries/{id}/segments")
public class SegmentObservationController {

    private final SegmentObservationService observations;
    private final DeliveryService deliveries;

    public SegmentObservationController(SegmentObservationService observations, DeliveryService deliveries) {
        this.observations = observations;
        this.deliveries = deliveries;
    }

    @PutMapping("/{sequence}/entry")
    public SegmentObservationResponse enter(@PathVariable UUID id, @PathVariable int sequence,
            @Valid @RequestBody TraversalEventRequest request) {
        return SegmentObservationResponse.from(observations.enter(id, sequence, request.toEvent()));
    }

    @PutMapping("/{sequence}/exit")
    public SegmentObservationResponse exit(@PathVariable UUID id, @PathVariable int sequence,
            @Valid @RequestBody TraversalEventRequest request) {
        return SegmentObservationResponse.from(observations.exit(id, sequence, request.toEvent()));
    }

    @GetMapping
    public List<SegmentObservationResponse> find(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        deliveries.requireVisible(id, CurrentViewer.of(principal));
        return observations.find(id).stream().map(SegmentObservationResponse::from).toList();
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@PathVariable UUID id, @RequestParam String availableAtCutoff) {
        var cutoff = ApiTimestamp.parse(availableAtCutoff);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=delivery-" + id + "-segments.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(SegmentObservationCsv.write(observations.export(id, cutoff)));
    }
}
