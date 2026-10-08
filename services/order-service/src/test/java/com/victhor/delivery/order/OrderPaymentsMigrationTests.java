package com.victhor.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OrderPaymentsMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void preservesExistingOrdersAsUnpaidIncludingConfirmedOnes() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID created = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");
        Instant confirmedAt = createdAt.plusSeconds(30);
        jdbc.update("""
                INSERT INTO orders (id, customer_id, restaurant_id, destination_address, destination_latitude,
                    destination_longitude, status, created_at, updated_at, total, version)
                VALUES (?, ?, ?, 'Rua das Flores, 42', -23.55, -46.63, 'CREATED', ?, ?, ?, 1)
                """, created, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(createdAt),
                Timestamp.from(createdAt), new BigDecimal("51.80"));
        jdbc.update("""
                INSERT INTO orders (id, customer_id, restaurant_id, destination_address, destination_latitude,
                    destination_longitude, status, created_at, updated_at, confirmed_at, total, version)
                VALUES (?, ?, ?, 'Rua Central, 10', -23.56, -46.64, 'CONFIRMED', ?, ?, ?, ?, 2)
                """, confirmed, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(createdAt),
                Timestamp.from(confirmedAt), Timestamp.from(confirmedAt), new BigDecimal("25.90"));
        var originalCreated = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", created);
        var originalConfirmed = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", confirmed);

        var flyway = Flyway.configure().dataSource(dataSource).target("5").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", created))
                .containsAllEntriesOf(originalCreated).containsEntry("payment_requested_at", null)
                .containsEntry("payment_id", null).hasSize(originalCreated.size() + 2);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", confirmed))
                .containsAllEntriesOf(originalConfirmed).containsEntry("payment_id", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_payments", Integer.class)).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
