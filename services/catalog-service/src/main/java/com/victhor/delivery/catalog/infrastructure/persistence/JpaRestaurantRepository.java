package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.catalog.application.RestaurantPage;
import com.victhor.delivery.catalog.application.RestaurantRepository;
import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

@Repository
@Transactional(readOnly = true)
public class JpaRestaurantRepository implements RestaurantRepository {

    private final SpringDataRestaurantRepository repository;

    public JpaRestaurantRepository(SpringDataRestaurantRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Restaurant save(Restaurant restaurant) {
        return repository.save(RestaurantEntity.fromDomain(restaurant)).toDomain();
    }

    @Override
    public Optional<Restaurant> findById(UUID id) {
        return repository.findById(id).map(RestaurantEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Restaurant> updatePickupLocation(UUID id, PickupLocation pickupLocation) {
        return repository.findById(id).map(entity -> {
            entity.updatePickupLocation(pickupLocation);
            return entity.toDomain();
        });
    }

    @Override
    public RestaurantPage findAll(int page, int size) {
        var result = repository.findAll(PageRequest.of(page, size, Sort.by("name", "id")));
        return new RestaurantPage(result.map(RestaurantEntity::toDomain).getContent(),
                page, size, result.getTotalElements());
    }
}
