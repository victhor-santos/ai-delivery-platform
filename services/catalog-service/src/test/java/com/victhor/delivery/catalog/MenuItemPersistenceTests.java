package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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

import com.victhor.delivery.catalog.application.MenuItemNotFoundException;
import com.victhor.delivery.catalog.application.MenuItemRepository;
import com.victhor.delivery.catalog.application.MenuItemService;
import com.victhor.delivery.catalog.application.RestaurantService;
import com.victhor.delivery.catalog.domain.MenuItem;

@SpringBootTest(properties = "CATALOG_DB_PASSWORD=testcontainers-only")
@Testcontainers
class MenuItemPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MenuItemService menuItems;

    @Autowired
    private MenuItemRepository repository;

    @Autowired
    private RestaurantService restaurants;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearCatalogInTestContainer() {
        jdbc.update("DELETE FROM menu_items");
        jdbc.update("DELETE FROM restaurants");
    }

    @Test
    void commitsTheCreatedItemAndRestoresItsCanonicalPrice() {
        UUID restaurantId = restaurants.create("Cantina").id();

        MenuItem created = menuItems.create(restaurantId, "  Lasagna  ", "  Homemade pasta  ",
                new BigDecimal("32.5"));

        assertThat(jdbc.queryForMap("SELECT * FROM menu_items WHERE id = ?", created.id()))
                .containsEntry("id", created.id())
                .containsEntry("restaurant_id", restaurantId)
                .containsEntry("name", "Lasagna")
                .containsEntry("description", "Homemade pasta")
                .containsEntry("price", new BigDecimal("32.50"))
                .containsEntry("available", true);
        assertThat(menuItems.findById(restaurantId, created.id())).isEqualTo(created);
    }

    @Test
    void restoresUnavailableItemsAndOptionalDescriptions() {
        UUID restaurantId = restaurants.create("Cantina").id();
        UUID id = UUID.randomUUID();
        insertItem(id, restaurantId, "Lasagna", null, new BigDecimal("32.50"), false);

        assertThat(menuItems.findById(restaurantId, id))
                .isEqualTo(new MenuItem(id, restaurantId, "Lasagna", null, new BigDecimal("32.50"), false));
        assertThat(menuItems.findAll(restaurantId, 0, 20).content()).extracting(MenuItem::id)
                .containsExactly(id);
    }

    @Test
    void updatesTheExistingItemAndCommitsAllEditableFields() {
        UUID restaurantId = restaurants.create("Cantina").id();
        MenuItem created = menuItems.create(restaurantId, "Lasagna", "Pasta", new BigDecimal("32.50"));

        MenuItem updated = menuItems.update(restaurantId, created.id(), "Ravioli", null,
                new BigDecimal("29.90"), false);

        assertThat(updated).isEqualTo(new MenuItem(created.id(), restaurantId, "Ravioli", null,
                new BigDecimal("29.90"), false));
        assertThat(menuItems.findById(restaurantId, created.id())).isEqualTo(updated);
        assertThat(jdbc.queryForMap("SELECT * FROM menu_items WHERE id = ?", created.id()))
                .containsEntry("restaurant_id", restaurantId)
                .containsEntry("name", "Ravioli")
                .containsEntry("description", null)
                .containsEntry("price", new BigDecimal("29.90"))
                .containsEntry("available", false);
        assertThat(menuItems.update(restaurantId, created.id(), "Ravioli", null,
                new BigDecimal("29.90"), false)).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void neverUpdatesOrTransfersAnItemOfAnotherRestaurant() {
        UUID restaurantId = restaurants.create("Cantina").id();
        UUID otherRestaurantId = restaurants.create("Bistro").id();
        MenuItem created = menuItems.create(restaurantId, "Lasagna", null, new BigDecimal("32.50"));

        assertThat(repository.findById(otherRestaurantId, created.id())).isEmpty();
        assertThat(repository.update(new MenuItem(created.id(), otherRestaurantId, "Ravioli", null,
                new BigDecimal("29.90"), false))).isEmpty();
        assertThatThrownBy(() -> menuItems.findById(otherRestaurantId, created.id()))
                .isInstanceOf(MenuItemNotFoundException.class);
        assertThatThrownBy(() -> menuItems.update(otherRestaurantId, created.id(), "Ravioli", null,
                new BigDecimal("29.90"), false)).isInstanceOf(MenuItemNotFoundException.class);
        assertThat(menuItems.findById(restaurantId, created.id())).isEqualTo(created);
        assertThat(menuItems.findAll(otherRestaurantId, 0, 20).content()).isEmpty();
    }

    @Test
    void neverCreatesAnItemWhenUpdatingAMissingId() {
        UUID restaurantId = restaurants.create("Cantina").id();
        UUID id = UUID.randomUUID();

        assertThat(repository.update(new MenuItem(id, restaurantId, "Lasagna", null,
                new BigDecimal("32.50"), true))).isEmpty();
        assertThatThrownBy(() -> menuItems.update(restaurantId, id, "Lasagna", null,
                new BigDecimal("32.50"), true)).isInstanceOf(MenuItemNotFoundException.class);
        assertThat(repository.findById(restaurantId, id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isZero();
    }

    @Test
    void returnsAnEmptyPageForAnExistingRestaurant() {
        UUID restaurantId = restaurants.create("Cantina").id();

        var page = menuItems.findAll(restaurantId, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void paginatesOnlyTheRequestedRestaurantByNameAndUuid() {
        UUID restaurantId = restaurants.create("Cantina").id();
        UUID otherRestaurantId = restaurants.create("Bistro").id();
        UUID firstAlpha = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondAlpha = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID bravo = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID delta = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID zulu = UUID.fromString("00000000-0000-0000-0000-000000000004");
        insertItem(zulu, restaurantId, "Zulu", null, new BigDecimal("1.00"), true);
        insertItem(secondAlpha, restaurantId, "Alpha", null, new BigDecimal("1.00"), true);
        insertItem(delta, restaurantId, "Delta", null, new BigDecimal("1.00"), false);
        insertItem(firstAlpha, restaurantId, "Alpha", null, new BigDecimal("1.00"), true);
        insertItem(bravo, restaurantId, "Bravo", null, new BigDecimal("1.00"), true);
        insertItem(UUID.randomUUID(), otherRestaurantId, "Alpha", null, new BigDecimal("1.00"), true);
        List<UUID> expectedIds = List.of(firstAlpha, secondAlpha, bravo, delta, zulu);

        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = menuItems.findAll(restaurantId, pageNumber, 2);

            assertThat(page.page()).isEqualTo(pageNumber);
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.totalElements()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.content()).extracting(MenuItem::id).containsExactlyElementsOf(
                    expectedIds.subList(pageNumber * 2, Math.min(pageNumber * 2 + 2, expectedIds.size())));
        }

        var beyondLastPage = menuItems.findAll(restaurantId, 3, 2);
        assertThat(beyondLastPage.content()).isEmpty();
        assertThat(beyondLastPage.totalElements()).isEqualTo(5);
        assertThat(beyondLastPage.totalPages()).isEqualTo(3);
    }

    @Test
    void acceptsTheMaximumSupportedPriceAndTextLengths() {
        UUID restaurantId = restaurants.create("Cantina").id();

        MenuItem created = menuItems.create(restaurantId, "N".repeat(120), "D".repeat(1000),
                new BigDecimal("99999999.99"));

        assertThat(menuItems.findById(restaurantId, created.id())).isEqualTo(created);
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.00", "-0.01", "100000000.00" })
    void rejectsInvalidPricesEvenOutsideTheApplication(String price) {
        UUID restaurantId = restaurants.create("Cantina").id();

        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), restaurantId, "Lasagna", null,
                new BigDecimal(price), true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "\t\n" })
    void rejectsBlankNamesEvenOutsideTheApplication(String name) {
        UUID restaurantId = restaurants.create("Cantina").id();

        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), restaurantId, name, null,
                new BigDecimal("1.00"), true)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOversizedNamesAndDescriptionsEvenOutsideTheApplication() {
        UUID restaurantId = restaurants.create("Cantina").id();

        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), restaurantId, "N".repeat(121), null,
                new BigDecimal("1.00"), true)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), restaurantId, "Lasagna", "D".repeat(1001),
                new BigDecimal("1.00"), true)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAnExistingRestaurantAndPreventsOrphaningItsMenuItems() {
        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), UUID.randomUUID(), "Lasagna", null,
                new BigDecimal("1.00"), true)).isInstanceOf(DataIntegrityViolationException.class);
        UUID restaurantId = restaurants.create("Cantina").id();
        MenuItem created = menuItems.create(restaurantId, "Lasagna", null, new BigDecimal("32.50"));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM restaurants WHERE id = ?", restaurantId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(menuItems.findById(restaurantId, created.id())).isEqualTo(created);
        assertThat(restaurants.findById(restaurantId).id()).isEqualTo(restaurantId);
    }

    private void insertItem(UUID id, UUID restaurantId, String name, String description,
            BigDecimal price, boolean available) {
        jdbc.update("INSERT INTO menu_items (id, restaurant_id, name, description, price, available) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, restaurantId, name, description, price, available);
    }
}
