package com.victhor.delivery.delivery.application;

import java.util.Optional;
import java.util.UUID;

import com.victhor.delivery.delivery.domain.Courier;

public interface CourierRepository {

    Courier create(Courier courier);

    Optional<Courier> findById(UUID id);
}
