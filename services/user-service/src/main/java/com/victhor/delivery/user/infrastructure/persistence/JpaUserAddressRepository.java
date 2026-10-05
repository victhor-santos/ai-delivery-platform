package com.victhor.delivery.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.user.application.UserAddressPage;
import com.victhor.delivery.user.application.UserAddressRepository;
import com.victhor.delivery.user.domain.UserAddress;

@Repository
@Transactional(readOnly = true)
public class JpaUserAddressRepository implements UserAddressRepository {

    private final SpringDataUserAddressRepository repository;

    public JpaUserAddressRepository(SpringDataUserAddressRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UserAddress save(UserAddress address) {
        return repository.save(UserAddressEntity.fromDomain(address)).toDomain();
    }

    @Override
    public Optional<UserAddress> findById(UUID userId, UUID addressId) {
        return repository.findByIdAndUserId(addressId, userId).map(UserAddressEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<UserAddress> update(UUID userId, UUID addressId, String label, String address,
            double latitude, double longitude) {
        return repository.findByIdAndUserId(addressId, userId).map(entity -> {
            entity.updateDetails(entity.toDomain().replace(label, address, latitude, longitude));
            return entity.toDomain();
        });
    }

    @Override
    public UserAddressPage findAll(UUID userId, int page, int size) {
        var result = repository.findAllByUserId(userId,
                PageRequest.of(page, size, Sort.by("label", "id")));
        return new UserAddressPage(result.map(UserAddressEntity::toDomain).getContent(),
                page, size, result.getTotalElements());
    }
}
