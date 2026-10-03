package com.victhor.delivery.delivery.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.victhor.delivery.delivery.application.RouteContext;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDeliveryRouteRequest(@NotBlank @Size(max = 64)
        @Pattern(regexp = ApiTimestamp.PATTERN)
        String departureAt) {

    RouteContext toContext() {
        return new RouteContext(ApiTimestamp.parse(departureAt));
    }
}
