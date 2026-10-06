package com.victhor.delivery.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.application.AuthAccountRepository;
import com.victhor.delivery.user.application.EmailAlreadyRegisteredException;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

@SpringBootTest(properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
@ActiveProfiles("test")
class AuthAccountPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @Autowired
    private AuthAccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registersAndRestoresTheCredentialByCanonicalEmail() {
        var profile = profile();
        assertThat(accounts.register(profile, "encoded-password")).isEqualTo(profile);
        var stored = accounts.findByEmail(profile.email()).orElseThrow();
        assertThat(stored.userId()).isEqualTo(profile.id());
        assertThat(stored.passwordHash()).isEqualTo("encoded-password");
    }

    @Test
    void credentialFailureRollsBackTheAlreadyFlushedProfile() {
        var profile = profile();
        assertThatThrownBy(() -> accounts.register(profile, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, profile.id())).isZero();
        assertThat(accounts.findByEmail(profile.email())).isEmpty();
    }

    @Test
    void twoConcurrentRegistrationsCreateExactlyOneCompleteAccount() throws Exception {
        var first = profile();
        var second = new UserProfile(UUID.randomUUID(), "Other", first.email());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, start));
            var b = executor.submit(() -> attempt(second, start));
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        UUID winner = accounts.findByEmail(first.email()).orElseThrow().userId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, first.email().value())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE user_id = ?", Integer.class, winner)).isEqualTo(1);
    }

    @Test
    void migrationPreservesLegacyProfilesAndAddressesWithoutInventingCredentials() {
        String schema = "legacy_auth_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("1").load().migrate();
        var profile = profile();
        UUID addressId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".users (id,name,email) VALUES (?,?,?)", profile.id(), profile.name(), profile.email().value());
        jdbc.update("INSERT INTO " + schema + ".user_addresses (id,user_id,label,address,latitude,longitude) VALUES (?,?,?,?,?,?)",
                addressId, profile.id(), "Casa", "Rua A", 0, 0);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).load().migrate();
        assertThat(jdbc.queryForObject("SELECT email FROM " + schema + ".users WHERE id = ?", String.class, profile.id()))
                .isEqualTo(profile.email().value());
        assertThat(jdbc.queryForObject("SELECT address FROM " + schema + ".user_addresses WHERE id = ?", String.class, addressId))
                .isEqualTo("Rua A");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".user_credentials", Integer.class)).isZero();
    }

    private int attempt(UserProfile profile, CountDownLatch start) throws InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Registration did not start");
        try {
            accounts.register(profile, "encoded-password");
            return 1;
        } catch (EmailAlreadyRegisteredException exception) {
            return 0;
        }
    }

    private UserProfile profile() {
        return UserProfile.create("Cliente", new EmailAddress("account-" + UUID.randomUUID() + "@example.test"));
    }
}
