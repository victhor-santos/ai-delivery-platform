package com.victhor.delivery.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.user.application.EmailAlreadyRegisteredException;
import com.victhor.delivery.user.application.UserRepository;
import com.victhor.delivery.user.domain.UserProfile;

@Repository
@Transactional(readOnly = true)
public class JpaUserRepository implements UserRepository {

    private static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_unique";

    private final SpringDataUserRepository repository;

    public JpaUserRepository(SpringDataUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public UserProfile save(UserProfile user) {
        try {
            return repository.saveAndFlush(UserEntity.fromDomain(user)).toDomain();
        } catch (DataIntegrityViolationException exception) {
            if (isEmailAlreadyRegistered(exception)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw exception;
        }
    }

    @Override
    public Optional<UserProfile> findById(UUID id) {
        return repository.findById(id).map(UserEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<UserProfile> updateName(UUID id, String name) {
        return repository.findById(id).map(entity -> {
            entity.updateName(name);
            return entity.toDomain();
        });
    }

    private boolean isEmailAlreadyRegistered(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
