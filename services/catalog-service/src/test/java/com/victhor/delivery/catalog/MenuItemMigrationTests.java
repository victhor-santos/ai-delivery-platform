package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MenuItemMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void addsAnEmptyMenuWithoutChangingExistingRestaurantData() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("2").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO restaurants (id, name, active, pickup_latitude, pickup_longitude) "
                + "VALUES (?, ?, ?, ?, ?)", id, "Cantina", false, -23.5505, -46.6333);

        var flyway = Flyway.configure().dataSource(dataSource).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();

        assertThat(jdbc.queryForMap("SELECT * FROM restaurants WHERE id = ?", id))
                .containsEntry("id", id).containsEntry("name", "Cantina").containsEntry("active", false)
                .containsEntry("pickup_latitude", -23.5505).containsEntry("pickup_longitude", -46.6333);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_items", Integer.class)).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
