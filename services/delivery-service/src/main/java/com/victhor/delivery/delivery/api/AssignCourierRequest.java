package com.victhor.delivery.delivery.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AssignCourierRequest(@NotNull UUID courierId) {
}
