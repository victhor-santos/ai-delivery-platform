package com.victhor.delivery.user.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.user.application.AuthAccount;
import com.victhor.delivery.user.domain.Role;

@Entity
@Table(name = "user_credentials")
public class UserCredentialsEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Role role;

    protected UserCredentialsEntity() {
    }

    UserCredentialsEntity(UUID userId, String passwordHash, Role role) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    AuthAccount toAccount() {
        return new AuthAccount(userId, passwordHash, role);
    }
}
