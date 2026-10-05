package com.victhor.delivery.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserAddressRepository extends JpaRepository<UserAddressEntity, UUID> {

    Optional<UserAddressEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<UserAddressEntity> findAllByUserId(UUID userId, Pageable pageable);
}
