package com.victhor.delivery.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "CATALOG_DB_PASSWORD=testcontainers-only")
@Testcontainers
class CatalogServiceApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithMigratedPostgresSchema() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(POSTGRES.getDatabaseName());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurants", Integer.class)).isZero();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n" })
    void databaseRejectsMissingOrBlankRestaurantNames(String name) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO restaurants (id, name) VALUES (?, ?)",
                UUID.randomUUID(), name)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidLocations")
    void databaseRejectsInvalidOrIncompleteLocations(Double latitude, Double longitude) {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO restaurants (id, name, pickup_latitude, pickup_longitude) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "Cantina", latitude, longitude))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    static Stream<Arguments> invalidLocations() {
        return Stream.of(
                Arguments.of(null, 0.0), Arguments.of(0.0, null),
                Arguments.of(-90.000001, 0.0), Arguments.of(90.000001, 0.0),
                Arguments.of(0.0, -180.000001), Arguments.of(0.0, 180.000001),
                Arguments.of(Double.NaN, 0.0), Arguments.of(0.0, Double.NaN),
                Arguments.of(Double.POSITIVE_INFINITY, 0.0), Arguments.of(0.0, Double.NEGATIVE_INFINITY));
    }
}
