package com.victhor.delivery.delivery.api;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;

final class ApiTimestamp {

    static final String PATTERN = "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})";

    private ApiTimestamp() {
    }

    static Instant parse(String value) {
        if (value == null || value.length() > 64 || !value.matches(PATTERN)) {
            throw new IllegalArgumentException("A timestamp with seconds and UTC offset is required");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid timestamp", exception);
        }
    }
}
