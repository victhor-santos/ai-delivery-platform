package com.victhor.delivery.order.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
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

import com.victhor.delivery.order.application.OrderService;
import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.Order;
import com.victhor.delivery.order.domain.OrderStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "ORDER_DB_PASSWORD=testcontainers-only")
@Testcontainers
class OrderPersistenceTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private OrderService orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagers;

    private Order order;

    @BeforeEach
    void createOrder() {
        jdbc.update("DELETE FROM orders");
        order = orders.create(UUID.randomUUID(), new DeliveryDestination("Rua das Flores, 42", -23.55, -46.63));
    }

    @Test
    void rejectsAStaleUpdateInsteadOfOverwritingTheCommittedState() {
        try (var first = entityManagers.createEntityManager(); var second = entityManagers.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            try {
                var firstCopy = first.find(OrderEntity.class, order.id());
                var secondCopy = second.find(OrderEntity.class, order.id());
                Instant confirmationTime = order.createdAt().plusSeconds(10);
                firstCopy.applyState(firstCopy.toDomain().confirm(confirmationTime));
                first.getTransaction().commit();
                secondCopy.applyState(secondCopy.toDomain().cancel(confirmationTime.plusSeconds(10)));
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            } finally {
                if (first.getTransaction().isActive()) {
                    first.getTransaction().rollback();
                }
                if (second.getTransaction().isActive()) {
                    second.getTransaction().rollback();
                }
            }
        }
        assertThat(orders.findById(order.id()).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.findById(order.id()).cancelledAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "restaurant_id = NULL", "destination_address = '   '",
            "destination_latitude = 91", "destination_longitude = -181",
            "destination_latitude = 'NaN'", "destination_longitude = 'Infinity'",
            "status = 'UNKNOWN'", "status = 'CONFIRMED'", "status = 'CANCELLED'",
            "updated_at = created_at - INTERVAL '1 second'",
            "confirmed_at = created_at", "cancelled_at = created_at"
    })
    void databaseRejectsInvalidStateEvenOutsideTheApplication(String assignment) {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET " + assignment + " WHERE id = ?", order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findById(order.id())).isEqualTo(order);
    }
}
