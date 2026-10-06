package com.victhor.delivery.user.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.user.application.AuthAccount;

@Entity
@Table(name = "user_credentials")
public class UserCredentialsEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    protected UserCredentialsEntity() {
    }

    UserCredentialsEntity(UUID userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
    }

    AuthAccount toAccount() {
        return new AuthAccount(userId, passwordHash);
    }
}
