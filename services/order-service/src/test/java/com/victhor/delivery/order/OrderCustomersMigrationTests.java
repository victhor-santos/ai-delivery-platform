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
class OrderCustomersMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void preservesExistingOrdersWithoutAssigningThemToACustomer() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("3").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID orderId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-05T12:00:00Z");
        jdbc.update("""
                INSERT INTO orders (id, restaurant_id, destination_address, destination_latitude,
                    destination_longitude, status, created_at, updated_at, total, version)
                VALUES (?, ?, 'Rua das Flores, 42', -23.55, -46.63, 'CREATED', ?, ?, ?, 3)
                """, orderId, UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt),
                new BigDecimal("51.80"));
        jdbc.update("""
                INSERT INTO order_items (order_id, item_position, menu_item_id, name, quantity, unit_price)
                VALUES (?, 0, ?, 'Prato do dia', 2, 25.90)
                """, orderId, UUID.randomUUID());
        var originalOrder = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        var originalItem = jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", orderId);

        var flyway = Flyway.configure().dataSource(dataSource).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId))
                .containsAllEntriesOf(originalOrder).containsEntry("customer_id", null)
                .hasSize(originalOrder.size() + 1);
        assertThat(jdbc.queryForMap("SELECT * FROM order_items WHERE order_id = ?", orderId)).isEqualTo(originalItem);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
