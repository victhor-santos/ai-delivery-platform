package com.victhor.delivery.delivery.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Testcontainers first so the context closes before the broker stops, without reconnection noise.
@Testcontainers
@SpringBootTest(properties = {"DELIVERY_DB_PASSWORD=testcontainers-only", "RABBITMQ_PASSWORD=unused",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms"})
@DirtiesContext
class DeliveryRequestedMessagingTests {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-alpine");

    @Autowired
    private RabbitTemplate template;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void clearState() {
        jdbc.update("DELETE FROM deliveries");
        template.setReceiveTimeout(0);
        while (template.receive(DeliveryMessaging.DELIVERY_REQUESTED_DLQ) != null) {
            // Drains messages left by a previous test.
        }
    }

    @Test
    void createsTheDeliveryOnceWithTheCustomerAndRequestId() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String event = event(UUID.randomUUID(), orderId, customerId, -23.55);
        double duplicatesBefore = count("duplicate");

        publish(event, "req-delivery-0001");
        await().atMost(WAIT).until(() -> deliveries(orderId) == 1);
        publish(event, "req-delivery-0001");
        await().atMost(WAIT).until(() -> count("duplicate") == duplicatesBefore + 1);

        assertThat(deliveries(orderId)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT customer_id, status, origin_description FROM deliveries WHERE order_id = ?",
                orderId);
        assertThat(row.get("customer_id")).isEqualTo(customerId);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("origin_description")).isEqualTo("Restaurante Central");
        assertThat(template.receive(DeliveryMessaging.DELIVERY_REQUESTED_DLQ)).isNull();
    }

    @Test
    void deadLettersInvalidEventsWithoutCreatingDeliveries() {
        publish("{not json", "req-invalid-0001");
        publish(event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 91), "req-invalid-0002");
        publishRaw(event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), -23.55), "order.unknown.v9");
        publish("""
                {"eventId":"%s","orderId":"%s","origin":{"description":"A","latitude":0,"longitude":0},
                 "destination":{"description":"B","latitude":0,"longitude":0}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()), "req-invalid-0003");

        for (int i = 0; i < 4; i++) {
            assertThat(template.receive(DeliveryMessaging.DELIVERY_REQUESTED_DLQ, WAIT.toMillis())).isNotNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries", Integer.class)).isZero();
    }

    @Test
    void deadLettersARequestThatConflictsWithTheStoredDelivery() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        publish(event(UUID.randomUUID(), orderId, customerId, -23.55), "req-conflict-0001");
        await().atMost(WAIT).until(() -> deliveries(orderId) == 1);

        publish(event(UUID.randomUUID(), orderId, customerId, -23.40), "req-conflict-0002");
        publish(event(UUID.randomUUID(), orderId, UUID.randomUUID(), -23.55), "req-conflict-0003");

        Message first = template.receive(DeliveryMessaging.DELIVERY_REQUESTED_DLQ, WAIT.toMillis());
        Message second = template.receive(DeliveryMessaging.DELIVERY_REQUESTED_DLQ, WAIT.toMillis());
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(jdbc.queryForObject("SELECT origin_latitude FROM deliveries WHERE order_id = ?", Double.class,
                orderId)).isEqualTo(-23.55);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM deliveries WHERE order_id = ?", UUID.class,
                orderId)).isEqualTo(customerId);
    }

    @Test
    void adoptsTheCustomerForADeliveryCreatedBeforeIt() {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deliveries (id, order_id, origin_description, origin_latitude, origin_longitude,
                    destination_description, destination_latitude, destination_longitude,
                    status, created_at, updated_at, version)
                VALUES (?, ?, 'Restaurante Central', -23.55, -46.63, 'Rua Central, 42', -23.56, -46.64,
                    'CREATED', now(), now(), 0)
                """, UUID.randomUUID(), orderId);
        UUID customerId = UUID.randomUUID();

        publish(event(UUID.randomUUID(), orderId, customerId, -23.55), "req-legacy-0001");

        await().atMost(WAIT).until(() -> customerId.equals(jdbc.queryForObject(
                "SELECT customer_id FROM deliveries WHERE order_id = ?", UUID.class, orderId)));
    }

    private void publish(String body, String requestId) {
        template.send(DeliveryMessaging.ORDER_EVENTS_EXCHANGE, DeliveryMessaging.DELIVERY_REQUESTED_KEY,
                message(body, DeliveryMessaging.DELIVERY_REQUESTED_TYPE, requestId));
    }

    private void publishRaw(String body, String type) {
        template.send(DeliveryMessaging.ORDER_EVENTS_EXCHANGE, DeliveryMessaging.DELIVERY_REQUESTED_KEY,
                message(body, type, "req-raw-00001"));
    }

    private static Message message(String body, String type, String requestId) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).setContentType("application/json")
                .setType(type).setMessageId(UUID.randomUUID().toString()).setHeader("X-Request-Id", requestId)
                .build();
    }

    private static String event(UUID eventId, UUID orderId, UUID customerId, double originLatitude) {
        return """
                {"eventId":"%s","orderId":"%s","customerId":"%s",
                 "origin":{"description":"Restaurante Central","latitude":%s,"longitude":-46.63},
                 "destination":{"description":"Rua Central, 42","latitude":-23.56,"longitude":-46.64},
                 "requestedAt":"2026-10-09T12:00:00Z"}
                """.formatted(eventId, orderId, customerId, originLatitude);
    }

    private int deliveries(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE order_id = ?", Integer.class, orderId);
    }

    private double count(String outcome) {
        var counter = registry.find(DeliveryRequestedListener.METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
