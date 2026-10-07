package com.victhor.delivery.order.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.victhor.delivery.order.application.DeliveryRequestRepository;
import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.DeliveryRequest;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderItem;
import com.victhor.delivery.order.domain.OrderPricing;

@ActiveProfiles("test")
@SpringBootTest(properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderItemsPersistenceTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-05T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private OrderRepository orders;

    @Autowired
    private DeliveryRequestRepository deliveryRequests;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactions;

    private Order order;

    @BeforeEach
    void createOrderWithSnapshots() {
        jdbc.update("DELETE FROM order_delivery_requests");
        jdbc.update("DELETE FROM orders");
        var pricing = new OrderPricing(List.of(
                new OrderItem(UUID.randomUUID(), "Lasagna", 2, new BigDecimal("32.50")),
                new OrderItem(UUID.randomUUID(), "Lemonade", 3, new BigDecimal("7.90"))));
        order = orders.save(Order.create(UUID.randomUUID(), UUID.randomUUID(), destination(), pricing, CREATED_AT));
    }

    @Test
    void commitsOrderedSnapshotsAndTheirExactTotal() {
        assertThat(orders.findById(order.id())).contains(order);
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, order.id()))
                .isEqualTo(new BigDecimal("88.70"));
        assertThat(jdbc.queryForList("""
                SELECT menu_item_id, name, quantity, unit_price, item_position
                FROM order_items WHERE order_id = ? ORDER BY item_position
                """, order.id()))
                .satisfiesExactly(
                        item -> assertThat(item).containsEntry("menu_item_id", order.pricing().items().get(0).menuItemId())
                                .containsEntry("name", "Lasagna").containsEntry("quantity", 2)
                                .containsEntry("unit_price", new BigDecimal("32.50")).containsEntry("item_position", 0),
                        item -> assertThat(item).containsEntry("menu_item_id", order.pricing().items().get(1).menuItemId())
                                .containsEntry("name", "Lemonade").containsEntry("quantity", 3)
                                .containsEntry("unit_price", new BigDecimal("7.90")).containsEntry("item_position", 1));
    }

    @Test
    void preservesCompositionAcrossConfirmationAndCancellation() {
        var confirmation = orders.confirm(order.id(), CREATED_AT.plusSeconds(10)).orElseThrow();
        var cancellation = orders.cancel(order.id(), CREATED_AT.plusSeconds(20)).orElseThrow();

        assertThat(confirmation.pricing()).isEqualTo(order.pricing());
        assertThat(cancellation.pricing()).isEqualTo(order.pricing());
        assertThat(orders.findById(order.id())).contains(cancellation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class,
                order.id())).isEqualTo(2);
    }

    @Test
    void preservesCompositionAcrossTheIdempotentDeliveryIntent() {
        orders.confirm(order.id(), CREATED_AT.plusSeconds(10));
        var request = new DeliveryRequest(order.id(),
                new DeliveryDestination("Cantina", -23.54, -46.62), order.destination());

        assertThat(deliveryRequests.prepare(request, CREATED_AT.plusSeconds(20))).isEqualTo(request);
        assertThat(deliveryRequests.prepare(request, CREATED_AT.plusSeconds(30))).isEqualTo(request);
        var restored = orders.findById(order.id()).orElseThrow();
        assertThat(restored.pricing()).isEqualTo(order.pricing());
        assertThat(restored.deliveryRequestedAt()).isEqualTo(CREATED_AT.plusSeconds(20));
    }

    @Test
    void restoresLegacyOrdersWithoutInventingItemsOrPrices() {
        var legacy = new Order(UUID.randomUUID(), UUID.randomUUID(), destination(), order.status(),
                CREATED_AT, CREATED_AT, null, null);

        assertThat(orders.save(legacy)).isEqualTo(legacy);
        assertThat(orders.findById(legacy.id())).contains(legacy);
        assertThat(jdbc.queryForObject("SELECT total FROM orders WHERE id = ?", BigDecimal.class, legacy.id()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class,
                legacy.id())).isZero();
    }

    @Test
    void supportsTheMaximumOrderTotalAndFiftyItemPositions() {
        var items = IntStream.range(0, 50)
                .mapToObj(index -> new OrderItem(UUID.randomUUID(), "N".repeat(120), 99,
                        new BigDecimal("99999999.99"))).toList();
        var maximum = Order.create(UUID.randomUUID(), UUID.randomUUID(), destination(), new OrderPricing(items), CREATED_AT);

        assertThat(orders.save(maximum)).isEqualTo(maximum);
        assertThat(orders.findById(maximum.id())).contains(maximum);
        assertThat(maximum.pricing().total()).isEqualTo(new BigDecimal("494999999950.50"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.00", "-0.01", "494999999950.51", "1000000000000.00" })
    void rejectsInvalidTotalsEvenOutsideTheApplication(String total) {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET total = ? WHERE id = ?",
                new BigDecimal(total), order.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findById(order.id())).contains(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "menu_item_id = NULL", "name = NULL", "name = ''", "name = '   '", "name = E'\\t\\n'",
            "quantity = NULL", "quantity = 0", "quantity = 100", "item_position = -1", "item_position = 50",
            "unit_price = NULL", "unit_price = 0.00", "unit_price = -0.01", "unit_price = 100000000.00"
    })
    void rejectsInvalidItemSnapshotsEvenOutsideTheApplication(String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET " + assignment
                + " WHERE order_id = ? AND item_position = 0", order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findById(order.id())).contains(order);
    }

    @Test
    void rejectsOversizedNamesAndDuplicateMenuItemsOrPositions() {
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET name = ? WHERE order_id = ?",
                "N".repeat(121), order.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertItem(order.id(), 2, order.pricing().items().get(0).menuItemId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertItem(order.id(), 1, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findById(order.id())).contains(order);
    }

    @Test
    void requiresAnExistingParentAndDeletesOnlyItsOwnedItems() {
        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), 0, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var other = Order.create(UUID.randomUUID(), UUID.randomUUID(), destination(), order.pricing(), CREATED_AT);
        orders.save(other);

        jdbc.update("DELETE FROM orders WHERE id = ?", order.id());

        assertThat(orders.findById(order.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class,
                order.id())).isZero();
        assertThat(orders.findById(other.id())).contains(other);
    }

    @Test
    void rollsBackTheWholeAggregateIfItsTransactionFails() {
        var candidate = Order.create(UUID.randomUUID(), UUID.randomUUID(), destination(), order.pricing(), CREATED_AT);
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            orders.save(candidate);
            entityManager.flush();
            insertItem(candidate.id(), 2, candidate.pricing().items().get(0).menuItemId());
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findById(candidate.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class,
                candidate.id())).isZero();
        assertThat(orders.findById(order.id())).contains(order);
    }

    @Test
    void detectsAStoredTotalThatDoesNotMatchTheSnapshots() {
        jdbc.update("UPDATE orders SET total = 1.00 WHERE id = ?", order.id());

        assertThatThrownBy(() -> orders.findById(order.id()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private void insertItem(UUID orderId, int position, UUID menuItemId) {
        jdbc.update("""
                INSERT INTO order_items (order_id, item_position, menu_item_id, name, quantity, unit_price)
                VALUES (?, ?, ?, 'Lasagna', 1, 32.50)
                """, orderId, position, menuItemId);
    }

    private static DeliveryDestination destination() {
        return new DeliveryDestination("Rua das Flores, 42", -23.55, -46.63);
    }
}
