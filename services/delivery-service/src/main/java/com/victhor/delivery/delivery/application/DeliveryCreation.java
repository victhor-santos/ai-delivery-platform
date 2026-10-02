package com.victhor.delivery.delivery.application;

import com.victhor.delivery.delivery.domain.Delivery;

public record DeliveryCreation(Delivery delivery, boolean created) {
}
