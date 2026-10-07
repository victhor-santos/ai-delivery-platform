package com.victhor.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;

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
class OrderItemsMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void preservesLegacyOrdersAndDeliveryIntentsWhenAddingPricing() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("2").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID orderId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");
        Instant confirmedAt = createdAt.plusSeconds(10);
        Instant deliveryRequestedAt = confirmedAt.plusSeconds(20);
        jdbc.update("""
                INSERT INTO orders (id, restaurant_id, destination_address, destination_latitude,
                    destination_longitude, status, created_at, updated_at, confirmed_at,
                    delivery_requested_at, version)
                VALUES (?, ?, 'Rua das Flores, 42', -23.55, -46.63, 'CONFIRMED', ?, ?, ?, ?, 7)
                """, orderId, UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(deliveryRequestedAt),
                Timestamp.from(confirmedAt), Timestamp.from(deliveryRequestedAt));
        jdbc.update("""
                INSERT INTO order_delivery_requests (order_id, origin_description, origin_latitude,
                    origin_longitude, destination_address, destination_latitude, destination_longitude)
                VALUES (?, 'Cantina', -23.54, -46.62, 'Rua das Flores, 42', -23.55, -46.63)
                """, orderId);
        var originalOrder = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        var originalRequest = jdbc.queryForMap("SELECT * FROM order_delivery_requests WHERE order_id = ?", orderId);

        var flyway = Flyway.configure().dataSource(dataSource).target("3").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId))
                .containsAllEntriesOf(originalOrder).containsEntry("total", null)
                .hasSize(originalOrder.size() + 1);
        assertThat(jdbc.queryForMap("SELECT * FROM order_delivery_requests WHERE order_id = ?", orderId))
                .isEqualTo(originalRequest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
