package com.victhor.delivery.delivery.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.delivery.application.CourierRepository;
import com.victhor.delivery.delivery.domain.Courier;

@Repository
@Transactional(readOnly = true)
public class JpaCourierRepository implements CourierRepository {

    private final SpringDataCourierRepository repository;

    public JpaCourierRepository(SpringDataCourierRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Courier create(Courier courier) {
        return repository.save(CourierEntity.fromDomain(courier)).toDomain();
    }

    @Override
    public Optional<Courier> findById(UUID id) {
        return repository.findById(id).map(CourierEntity::toDomain);
    }
}
