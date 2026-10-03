package com.victhor.delivery.delivery.api;

import java.time.DateTimeException;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.victhor.delivery.delivery.application.RouteContext;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDeliveryRouteRequest(@NotBlank @Size(max = 64)
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})")
        String departureAt) {

    RouteContext toContext() {
        try {
            return new RouteContext(OffsetDateTime.parse(departureAt).toInstant());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid departure time", exception);
        }
    }
}
