package com.victhor.delivery.delivery.domain;

import java.util.Objects;
import java.util.UUID;

public record Courier(UUID id, boolean active) {

    public Courier {
        Objects.requireNonNull(id, "Courier id is required");
    }
}
