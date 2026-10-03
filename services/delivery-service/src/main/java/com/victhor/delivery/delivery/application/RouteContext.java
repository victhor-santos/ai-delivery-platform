package com.victhor.delivery.delivery.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record RouteContext(Instant departureAt) {

    public RouteContext {
        Objects.requireNonNull(departureAt, "Departure time is required");
        int utcYear = departureAt.atOffset(ZoneOffset.UTC).getYear();
        int localYear = departureAt.atZone(ZoneId.of("America/Sao_Paulo")).getYear();
        if (utcYear < 1 || utcYear > 9999 || localYear < 1 || localYear > 9999) {
            throw new IllegalArgumentException("Departure time is outside the supported calendar");
        }
        departureAt = departureAt.truncatedTo(ChronoUnit.MICROS);
    }
}
