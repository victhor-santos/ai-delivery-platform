package com.victhor.delivery.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.user.application.EmailAlreadyRegisteredException;
import com.victhor.delivery.user.application.UserRepository;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

@SpringBootTest(properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class UserPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearUsersInTestContainer() {
        jdbc.update("DELETE FROM users");
    }

    @Test
    void commitsCanonicalNameAndEmailAndRestoresTheProfile() {
        UserProfile profile = new UserProfile(UUID.randomUUID(), "  Maria Silva  ",
                new EmailAddress("  MARIA@EXAMPLE.COM  "));

        assertThat(users.save(profile)).isEqualTo(profile);

        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", profile.id()))
                .containsEntry("id", profile.id())
                .containsEntry("name", "Maria Silva")
                .containsEntry("email", "maria@example.com");
        assertThat(users.findById(profile.id())).contains(profile);
    }

    @Test
    void updatesOnlyTheNameAndPreservesTheIdAndImmutableEmail() {
        UserProfile original = users.save(profile("Maria", "maria@example.com"));

        UserProfile updated = users.updateName(original.id(), "  Maria Silva  ").orElseThrow();

        assertThat(updated).isEqualTo(new UserProfile(original.id(), "Maria Silva", original.email()));
        assertThat(users.findById(original.id())).contains(updated);
        assertThat(users.updateName(original.id(), "Maria Silva")).contains(updated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void neverCreatesAProfileWhenUpdatingAMissingId() {
        UUID id = UUID.randomUUID();

        assertThat(users.findById(id)).isEmpty();
        assertThat(users.updateName(id, "Maria")).isEmpty();
        assertThat(users.findById(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void translatesTheDatabaseEmailConstraintAndKeepsTheExistingProfile() {
        UserProfile original = users.save(profile("Maria", "maria@example.com"));
        UserProfile duplicate = profile("Other person", "  MARIA@EXAMPLE.COM  ");

        assertThatThrownBy(() -> users.save(duplicate)).isInstanceOf(EmailAlreadyRegisteredException.class);

        assertThat(users.findById(original.id())).contains(original);
        assertThat(users.findById(duplicate.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void enforcesUniqueEmailsEvenOutsideTheRepository() {
        UserProfile original = users.save(profile("Maria", "maria@example.com"));

        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), "Other person", "maria@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.findById(original.id())).contains(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void allowsExactlyOneConcurrentRegistrationOfTheSameCanonicalEmail() throws Exception {
        UserProfile first = profile("Maria", "maria@example.com");
        UserProfile second = profile("Another Maria", " MARIA@EXAMPLE.COM ");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstAttempt = executor.submit(() -> registerTogether(first, ready, start));
            var secondAttempt = executor.submit(() -> registerTogether(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<RegistrationResult> results = List.of(firstAttempt.get(10, TimeUnit.SECONDS),
                    secondAttempt.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(result -> result.profile() != null)).hasSize(1);
            assertThat(results.stream().filter(result -> result.failure() != null)
                    .map(RegistrationResult::failure)).singleElement()
                    .isInstanceOf(EmailAlreadyRegisteredException.class);
            UserProfile winner = results.stream().map(RegistrationResult::profile)
                    .filter(profile -> profile != null).findFirst().orElseThrow();
            assertThat(users.findById(winner.id())).contains(winner);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void acceptsMaximumNameAndEmailLengths() {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        UserProfile profile = users.save(profile("N".repeat(120), email));

        assertThat(profile.email().value()).hasSize(254);
        assertThat(users.findById(profile.id())).contains(profile);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "\t\n" })
    void rejectsBlankNamesEvenOutsideTheApplication(String name) {
        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), name, "maria@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "MARIA@EXAMPLE.COM", " maria@example.com", "maria@example.com ",
            "maria@exam ple.com", "maria@example.com\t" })
    void rejectsNoncanonicalEmailsEvenOutsideTheApplication(String email) {
        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), "Maria", email))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOversizedNamesAndEmailsEvenOutsideTheApplication() {
        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), "N".repeat(121), "maria@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(UUID.randomUUID(), "Maria", "a".repeat(244) + "@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RegistrationResult registerTogether(UserProfile profile, CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent registration did not start");
        }
        try {
            return new RegistrationResult(users.save(profile), null);
        } catch (RuntimeException exception) {
            return new RegistrationResult(null, exception);
        }
    }

    private UserProfile profile(String name, String email) {
        return new UserProfile(UUID.randomUUID(), name, new EmailAddress(email));
    }

    private void insertUser(UUID id, String name, String email) {
        jdbc.update("INSERT INTO users (id, name, email) VALUES (?, ?, ?)", id, name, email);
    }

    private record RegistrationResult(UserProfile profile, RuntimeException failure) {
    }
}
