package com.victhor.delivery.catalog.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.victhor.delivery.catalog.domain.Restaurant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateRestaurantRequest(
        @NotBlank @Size(max = Restaurant.MAX_NAME_LENGTH) String name) {

    public CreateRestaurantRequest {
        if (name != null) {
            name = name.strip();
        }
    }
}
