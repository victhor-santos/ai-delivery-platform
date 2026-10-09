package com.victhor.delivery.order.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.victhor.delivery.order.observability.RequestIds;

/**
 * Publishes pending outbox events in creation order. An event counts as published only after the broker confirms
 * it and routes it to at least one queue; otherwise it stays pending and the next poll tries again. Delivery is
 * at least once, so consumers must be idempotent. {@code SKIP LOCKED} lets several instances poll safely.
 */
@Component
class OutboxPublisher {

    static final String PUBLISHED_METRIC = "order.outbox.published";
    static final String FAILED_METRIC = "order.outbox.failed";
    static final String PENDING_METRIC = "order.outbox.pending";
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final MeterRegistry registry;
    private final Clock clock;
    private final int batchSize;
    private final long confirmTimeoutMs;

    OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transactions, RabbitTemplate rabbit,
            MeterRegistry registry, Clock clock, @Value("${order.outbox.batch-size}") int batchSize,
            @Value("${order.outbox.confirm-timeout}") Duration confirmTimeout) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.registry = registry;
        this.clock = clock;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeout.toMillis();
        // Returns are read from each publish's CorrelationData; this only keeps the template from warning.
        rabbit.setReturnsCallback(returned -> { });
        Gauge.builder(PENDING_METRIC, this, OutboxPublisher::pending).register(registry);
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval}")
    void publishPending() {
        try {
            transactions.executeWithoutResult(status -> publishBatch());
        } catch (DataAccessException exception) {
            log.warn("Outbox poll failed: {}", exception.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    private void publishBatch() {
        var events = jdbc.query("""
                SELECT id, event_type, routing_key, payload, request_id FROM outbox_events
                WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (row, index) -> new PendingEvent(row.getObject("id", UUID.class), row.getString("event_type"),
                row.getString("routing_key"), row.getString("payload"), row.getString("request_id")), batchSize);
        for (PendingEvent event : events) {
            try {
                send(event);
            } catch (AmqpException | UnpublishedEventException exception) {
                // Later events wait too, so a consumer never sees them ahead of an earlier one.
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1 WHERE id = ?", event.id());
                registry.counter(FAILED_METRIC, "type", event.type()).increment();
                log.warn("Outbox event not published eventId={} type={} cause={}", event.id(), event.type(),
                        exception.getClass().getSimpleName());
                return;
            }
            jdbc.update("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                    Timestamp.from(clock.instant()), event.id());
            registry.counter(PUBLISHED_METRIC, "type", event.type()).increment();
            log.info("Outbox event published eventId={} type={}", event.id(), event.type());
        }
    }

    private void send(PendingEvent event) {
        String messageId = event.id().toString();
        var builder = MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                .setType(event.type()).setMessageId(messageId).setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (event.requestId() != null) {
            builder.setHeader(RequestIds.HEADER, event.requestId());
        }
        Message message = builder.build();
        // Spring AMQP records a return from a mandatory publish before completing the confirm.
        var correlation = new CorrelationData(messageId);
        rabbit.send(OrderEvents.EXCHANGE, event.routingKey(), message, correlation);
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UnpublishedEventException();
        } catch (ExecutionException | TimeoutException exception) {
            throw new UnpublishedEventException();
        }
        if (!confirm.ack() || correlation.getReturned() != null) {
            throw new UnpublishedEventException();
        }
    }

    private double pending() {
        try {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL",
                    Integer.class);
            return count == null ? 0 : count;
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }

    private record PendingEvent(UUID id, String type, String routingKey, String payload, String requestId) {
    }

    /** The broker rejected the event, did not confirm it in time or had no queue to route it to. */
    private static final class UnpublishedEventException extends RuntimeException {
    }
}
