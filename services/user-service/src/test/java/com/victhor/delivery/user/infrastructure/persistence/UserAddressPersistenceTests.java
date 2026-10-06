package com.victhor.delivery.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

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

import com.victhor.delivery.user.application.UserAddressRepository;
import com.victhor.delivery.user.application.UserRepository;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserAddress;
import com.victhor.delivery.user.domain.UserProfile;

@SpringBootTest(properties = "USER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class UserAddressPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private UserRepository users;

    @Autowired
    private UserAddressRepository addresses;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearUsersInTestContainer() {
        jdbc.update("DELETE FROM users");
    }

    @Test
    void commitsTheAddressAndRestoresCanonicalDetails() {
        UUID userId = createUser();
        UserAddress address = new UserAddress(UUID.randomUUID(), userId, "  Casa  ",
                "  Rua A, 10  ", -23.5505, -46.6333);

        assertThat(addresses.save(address)).isEqualTo(address);

        assertThat(addresses.findById(userId, address.id())).contains(address);
        assertThat(jdbc.queryForMap("SELECT * FROM user_addresses WHERE id = ?", address.id()))
                .containsEntry("id", address.id())
                .containsEntry("user_id", userId)
                .containsEntry("label", "Casa")
                .containsEntry("address", "Rua A, 10")
                .containsEntry("latitude", -23.5505)
                .containsEntry("longitude", -46.6333);
    }

    @Test
    void replacesAllEditableDetailsAndPreservesAddressAndUserIds() {
        UUID userId = createUser();
        UserAddress original = addresses.save(address(userId, "Casa"));

        UserAddress updated = addresses.update(userId, original.id(), "  Trabalho  ",
                "  Rua B, 20  ", 45.25, -120.5).orElseThrow();

        assertThat(updated).isEqualTo(new UserAddress(original.id(), userId, "Trabalho",
                "Rua B, 20", 45.25, -120.5));
        assertThat(addresses.findById(userId, original.id())).contains(updated);
        assertThat(addresses.update(userId, original.id(), "Trabalho", "Rua B, 20", 45.25, -120.5))
                .contains(updated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses", Integer.class)).isEqualTo(1);
    }

    @Test
    void neverReadsUpdatesOrTransfersAnAddressOfAnotherUser() {
        UUID userId = createUser();
        UUID otherUserId = createUser();
        UserAddress original = addresses.save(address(userId, "Casa"));

        assertThat(addresses.findById(otherUserId, original.id())).isEmpty();
        assertThat(addresses.update(otherUserId, original.id(), "Trabalho", "Rua B", 0, 0)).isEmpty();
        assertThat(addresses.findAll(otherUserId, 0, 20).items()).isEmpty();
        assertThat(addresses.findById(userId, original.id())).contains(original);
    }

    @Test
    void neverCreatesAnAddressWhenUpdatingAMissingId() {
        UUID userId = createUser();
        UUID addressId = UUID.randomUUID();

        assertThat(addresses.findById(userId, addressId)).isEmpty();
        assertThat(addresses.update(userId, addressId, "Casa", "Rua A", 0, 0)).isEmpty();
        assertThat(addresses.findById(userId, addressId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses", Integer.class)).isZero();
    }

    @Test
    void returnsAnEmptyPageForAUserWithoutAddresses() {
        var page = addresses.findAll(createUser(), 0, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void paginatesOnlyTheRequestedUserByLabelAndUuid() {
        UUID userId = createUser();
        UUID otherUserId = createUser();
        UUID firstAlpha = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondAlpha = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID bravo = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID delta = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID zulu = UUID.fromString("00000000-0000-0000-0000-000000000004");
        insertAddress(zulu, userId, "Zulu", "Rua A", 0, 0);
        insertAddress(secondAlpha, userId, "Alpha", "Rua A", 0, 0);
        insertAddress(delta, userId, "Delta", "Rua A", 0, 0);
        insertAddress(firstAlpha, userId, "Alpha", "Rua A", 0, 0);
        insertAddress(bravo, userId, "Bravo", "Rua A", 0, 0);
        insertAddress(UUID.randomUUID(), otherUserId, "Alpha", "Rua A", 0, 0);
        List<UUID> expectedIds = List.of(firstAlpha, secondAlpha, bravo, delta, zulu);

        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = addresses.findAll(userId, pageNumber, 2);

            assertThat(page.page()).isEqualTo(pageNumber);
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.totalElements()).isEqualTo(5);
            assertThat(page.items()).extracting(UserAddress::id).containsExactlyElementsOf(
                    expectedIds.subList(pageNumber * 2, Math.min(pageNumber * 2 + 2, expectedIds.size())));
        }

        var beyondLastPage = addresses.findAll(userId, 3, 2);
        assertThat(beyondLastPage.items()).isEmpty();
        assertThat(beyondLastPage.totalElements()).isEqualTo(5);
    }

    @Test
    void acceptsMaximumTextLengthsAndCoordinateBoundaries() {
        UUID userId = createUser();
        UserAddress southern = addresses.save(new UserAddress(UUID.randomUUID(), userId,
                "L".repeat(80), "A".repeat(255), -90, -180));
        UserAddress northern = addresses.save(new UserAddress(UUID.randomUUID(), userId,
                "Norte", "Rua A", 90, 180));

        assertThat(addresses.findById(userId, southern.id())).contains(southern);
        assertThat(addresses.findById(userId, northern.id())).contains(northern);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "\t\n" })
    void rejectsBlankLabelsAndAddressesEvenOutsideTheApplication(String blank) {
        UUID userId = createUser();

        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, blank, "Rua A", 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, "Casa", blank, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOversizedLabelsAndAddressesEvenOutsideTheApplication() {
        UUID userId = createUser();

        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, "L".repeat(81), "Rua A", 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, "Casa", "A".repeat(256), 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = { -91, 91, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLatitudesEvenOutsideTheApplication(double latitude) {
        UUID userId = createUser();

        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, "Casa", "Rua A", latitude, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = { -181, 181, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY })
    void rejectsInvalidLongitudesEvenOutsideTheApplication(double longitude) {
        UUID userId = createUser();

        assertThatThrownBy(() -> insertAddress(UUID.randomUUID(), userId, "Casa", "Rua A", 0, longitude))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAnExistingUserAndRemovesItsOwnedAddressesWhenDeleted() {
        assertThatThrownBy(() -> addresses.save(address(UUID.randomUUID(), "Casa")))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID userId = createUser();
        UserAddress original = addresses.save(address(userId, "Casa"));

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(users.findById(userId)).isEmpty();
        assertThat(addresses.findById(userId, original.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses", Integer.class)).isZero();
    }

    private UUID createUser() {
        return users.save(new UserProfile(UUID.randomUUID(), "Maria",
                new EmailAddress(UUID.randomUUID() + "@example.com"))).id();
    }

    private UserAddress address(UUID userId, String label) {
        return new UserAddress(UUID.randomUUID(), userId, label, "Rua A, 10", -23.5505, -46.6333);
    }

    private void insertAddress(UUID id, UUID userId, String label, String address,
            double latitude, double longitude) {
        jdbc.update("INSERT INTO user_addresses (id, user_id, label, address, latitude, longitude) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, userId, label, address, latitude, longitude);
    }
}
