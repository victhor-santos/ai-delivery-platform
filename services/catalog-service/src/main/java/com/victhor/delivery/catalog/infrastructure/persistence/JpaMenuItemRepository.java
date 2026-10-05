package com.victhor.delivery.catalog.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.catalog.application.MenuItemPage;
import com.victhor.delivery.catalog.application.MenuItemRepository;
import com.victhor.delivery.catalog.domain.MenuItem;

@Repository
@Transactional(readOnly = true)
public class JpaMenuItemRepository implements MenuItemRepository {

    private final SpringDataMenuItemRepository repository;

    public JpaMenuItemRepository(SpringDataMenuItemRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public MenuItem save(MenuItem item) {
        return repository.save(MenuItemEntity.fromDomain(item)).toDomain();
    }

    @Override
    public Optional<MenuItem> findById(UUID restaurantId, UUID id) {
        return repository.findByIdAndRestaurantId(id, restaurantId).map(MenuItemEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<MenuItem> update(MenuItem item) {
        return repository.findByIdAndRestaurantId(item.id(), item.restaurantId()).map(entity -> {
            entity.updateDetails(item);
            return entity.toDomain();
        });
    }

    @Override
    public MenuItemPage findAll(UUID restaurantId, int page, int size) {
        var result = repository.findAllByRestaurantId(restaurantId,
                PageRequest.of(page, size, Sort.by("name", "id")));
        return new MenuItemPage(result.map(MenuItemEntity::toDomain).getContent(),
                page, size, result.getTotalElements());
    }
}
