package com.victhor.delivery.delivery.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.victhor.delivery.delivery.application.TraversalEvent;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TraversalEventRequest(@NotNull UUID routePlanId,
        @NotBlank @Size(max = 64)
        @Pattern(regexp = ApiTimestamp.PATTERN)
        String occurredAt, @NotNull @Pattern(regexp = "simulated") String dataOrigin) {

    TraversalEvent toEvent() {
        return new TraversalEvent(routePlanId, ApiTimestamp.parse(occurredAt), dataOrigin);
    }
}
