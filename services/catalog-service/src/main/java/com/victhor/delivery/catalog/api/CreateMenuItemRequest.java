package com.victhor.delivery.catalog.api;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.catalog.domain.MenuItem;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMenuItemRequest(
        @NotBlank @Size(max = MenuItem.MAX_NAME_LENGTH) String name,
        @Size(max = MenuItem.MAX_DESCRIPTION_LENGTH) String description,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999.99") BigDecimal price) {

    public CreateMenuItemRequest {
        if (name != null) {
            name = name.strip();
        }
        if (description != null) {
            description = description.strip();
        }
    }
}
