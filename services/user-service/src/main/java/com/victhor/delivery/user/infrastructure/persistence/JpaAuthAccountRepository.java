package com.victhor.delivery.user.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.victhor.delivery.user.application.AuthAccount;
import com.victhor.delivery.user.application.AuthAccountRepository;
import com.victhor.delivery.user.application.UserRepository;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.domain.UserProfile;

@Repository
@Transactional(readOnly = true)
public class JpaAuthAccountRepository implements AuthAccountRepository {

    private final UserRepository users;
    private final SpringDataUserCredentialsRepository credentials;

    public JpaAuthAccountRepository(UserRepository users, SpringDataUserCredentialsRepository credentials) {
        this.users = users;
        this.credentials = credentials;
    }

    @Override
    @Transactional
    public UserProfile register(UserProfile profile, String passwordHash, Role role) {
        var saved = users.save(profile);
        credentials.saveAndFlush(new UserCredentialsEntity(saved.id(), passwordHash, role));
        return saved;
    }

    @Override
    public Optional<AuthAccount> findByEmail(EmailAddress email) {
        return credentials.findByEmail(email.value()).map(UserCredentialsEntity::toAccount);
    }

    @Override
    public Optional<AuthAccount> findByUserId(UUID userId) {
        return credentials.findById(userId).map(UserCredentialsEntity::toAccount);
    }

    @Override
    @Transactional
    public void changePasswordHash(UUID userId, String passwordHash) {
        credentials.findById(userId).orElseThrow().changePasswordHash(passwordHash);
    }
}
