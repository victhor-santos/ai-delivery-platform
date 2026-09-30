package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.catalog.application.RestaurantNotFoundException;
import com.victhor.delivery.catalog.application.RestaurantService;
import com.victhor.delivery.catalog.domain.Restaurant;
import com.victhor.delivery.catalog.domain.PickupLocation;

@SpringBootTest(properties = "CATALOG_DB_PASSWORD=testcontainers-only")
@Testcontainers
class RestaurantPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private RestaurantService restaurants;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearRestaurantsInTestContainer() {
        jdbc.update("DELETE FROM restaurants");
    }

    @Test
    void commitsTheCreatedRestaurantAndFindsItById() {
        Restaurant created = restaurants.create("  Cantina Central  ");

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Cantina Central");
        assertThat(created.active()).isTrue();
        assertThat(jdbc.queryForMap("SELECT id, name, active FROM restaurants WHERE id = ?", created.id()))
                .containsEntry("id", created.id())
                .containsEntry("name", "Cantina Central")
                .containsEntry("active", true);
        assertThat(restaurants.findById(created.id())).isEqualTo(created);
    }

    @Test
    void persistsAndRestoresThePickupLocation() {
        var location = new PickupLocation(-23.5505, -46.6333);

        Restaurant created = restaurants.create("Cantina", location);

        assertThat(restaurants.findById(created.id()).pickupLocation()).isEqualTo(location);
        assertThat(jdbc.queryForMap("SELECT pickup_latitude, pickup_longitude FROM restaurants WHERE id = ?",
                created.id())).containsEntry("pickup_latitude", location.latitude())
                .containsEntry("pickup_longitude", location.longitude());
        assertThat(restaurants.findAll(0, 20).content()).containsExactly(created);
    }

    @Test
    void updatesOnlyTheLocationOfAnExistingRestaurant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO restaurants (id, name, active) VALUES (?, ?, ?)", id, "Cantina", false);
        var first = new PickupLocation(-23.5505, -46.6333);
        var replacement = new PickupLocation(-22.9068, -43.1729);

        restaurants.updatePickupLocation(id, first);
        Restaurant updated = restaurants.updatePickupLocation(id, replacement);

        assertThat(updated).isEqualTo(new Restaurant(id, "Cantina", false, replacement));
        assertThat(restaurants.findById(id)).isEqualTo(updated);
        assertThat(jdbc.queryForMap("SELECT pickup_latitude, pickup_longitude FROM restaurants WHERE id = ?", id))
                .containsEntry("pickup_latitude", replacement.latitude())
                .containsEntry("pickup_longitude", replacement.longitude());
        assertThat(restaurants.updatePickupLocation(id, replacement)).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isEqualTo(1);
    }

    @Test
    void restoresTheStoredActiveState() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO restaurants (id, name, active) VALUES (?, ?, ?)", id, "Cantina", false);

        assertThat(restaurants.findById(id)).isEqualTo(new Restaurant(id, "Cantina", false));
    }

    @Test
    void reportsAMissingRestaurant() {
        assertThatThrownBy(() -> restaurants.findById(UUID.randomUUID()))
                .isInstanceOf(RestaurantNotFoundException.class);
    }

    @Test
    void returnsAnEmptyPage() {
        var page = restaurants.findAll(0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void paginatesByNameWithUuidAsTheTieBreaker() {
        UUID firstAlpha = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondAlpha = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID bravo = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID delta = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID zulu = UUID.fromString("00000000-0000-0000-0000-000000000004");
        insertRestaurant(zulu, "Zulu");
        insertRestaurant(secondAlpha, "Alpha");
        insertRestaurant(delta, "Delta");
        insertRestaurant(firstAlpha, "Alpha");
        insertRestaurant(bravo, "Bravo");
        List<UUID> expectedIds = List.of(firstAlpha, secondAlpha, bravo, delta, zulu);

        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = restaurants.findAll(pageNumber, 2);

            assertThat(page.page()).isEqualTo(pageNumber);
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.totalElements()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.content()).extracting(Restaurant::id).containsExactlyElementsOf(
                    expectedIds.subList(pageNumber * 2, Math.min(pageNumber * 2 + 2, expectedIds.size())));
        }

        assertThat(restaurants.findAll(0, 2).content()).extracting(Restaurant::id)
                .containsExactly(firstAlpha, secondAlpha);
        var beyondLastPage = restaurants.findAll(3, 2);
        assertThat(beyondLastPage.content()).isEmpty();
        assertThat(beyondLastPage.totalElements()).isEqualTo(5);
        assertThat(beyondLastPage.totalPages()).isEqualTo(3);
    }

    private void insertRestaurant(UUID id, String name) {
        jdbc.update("INSERT INTO restaurants (id, name, active) VALUES (?, ?, ?)", id, name, true);
    }
}
