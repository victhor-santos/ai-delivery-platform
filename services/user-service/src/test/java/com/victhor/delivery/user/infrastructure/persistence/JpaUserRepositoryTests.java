package com.victhor.delivery.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.victhor.delivery.user.application.EmailAlreadyRegisteredException;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

class JpaUserRepositoryTests {

    private final SpringDataUserRepository delegate = mock(SpringDataUserRepository.class);
    private final JpaUserRepository repository = new JpaUserRepository(delegate);

    @Test
    void recognizesTheEmailConstraintInsideTheCauseChain() {
        var violation = new ConstraintViolationException("Duplicate email", new SQLException("Duplicate", "23505"),
                "users_email_unique");
        var failure = new DataIntegrityViolationException("Could not persist", new RuntimeException(violation));
        when(delegate.saveAndFlush(any(UserEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(profile())).isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "users_pkey", "users_name_not_blank", "other_users_email_unique" })
    void propagatesOtherOrUnknownConstraintViolations(String constraint) {
        var violation = new ConstraintViolationException("users_email_unique appears only in text",
                new SQLException("users_email_unique", "23505"), constraint);
        var failure = new DataIntegrityViolationException("Could not persist", violation);
        when(delegate.saveAndFlush(any(UserEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(profile())).isSameAs(failure);
    }

    @Test
    void propagatesIntegrityFailuresWithoutAnIdentifiedConstraint() {
        var failure = new DataIntegrityViolationException("users_email_unique appears only in text");
        when(delegate.saveAndFlush(any(UserEntity.class))).thenThrow(failure);

        assertThatThrownBy(() -> repository.save(profile())).isSameAs(failure);
    }

    private UserProfile profile() {
        return new UserProfile(UUID.randomUUID(), "Maria", new EmailAddress("maria@example.com"));
    }
}
