package com.victhor.delivery.user.infrastructure.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = UserProfile.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false, updatable = false, length = EmailAddress.MAX_LENGTH)
    private String email;

    protected UserEntity() {
    }

    private UserEntity(UserProfile user) {
        id = user.id();
        name = user.name();
        email = user.email().value();
    }

    static UserEntity fromDomain(UserProfile user) {
        return new UserEntity(user);
    }

    UserProfile toDomain() {
        return new UserProfile(id, name, new EmailAddress(email));
    }

    void updateName(String name) {
        this.name = toDomain().updateName(name).name();
    }
}
