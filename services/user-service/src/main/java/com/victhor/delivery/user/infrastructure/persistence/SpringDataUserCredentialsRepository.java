package com.victhor.delivery.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataUserCredentialsRepository extends JpaRepository<UserCredentialsEntity, UUID> {

    @Query("select c from UserCredentialsEntity c join UserEntity u on c.userId = u.id where u.email = :email")
    Optional<UserCredentialsEntity> findByEmail(@Param("email") String email);
}
