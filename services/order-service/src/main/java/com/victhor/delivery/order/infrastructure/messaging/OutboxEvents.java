package com.victhor.delivery.order.infrastructure.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.victhor.delivery.order.domain.DeliveryDestination;
import com.victhor.delivery.order.domain.DeliveryRequest;
import com.victhor.delivery.order.observability.RequestIds;

/** Appends events to the outbox inside the caller's transaction, so they commit or roll back with the change. */
@Component
public class OutboxEvents {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID deliveryRequested(DeliveryRequest request, UUID customerId, Instant requestedAt) {
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode().put("eventId", eventId.toString())
                .put("orderId", request.orderId().toString()).put("customerId", customerId.toString())
                .put("requestedAt", requestedAt.toString());
        payload.set("origin", location(request.origin()));
        payload.set("destination", location(request.destination()));
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_id, event_type, routing_key, payload, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, request.orderId(), OrderEvents.DELIVERY_REQUESTED_TYPE,
                OrderEvents.DELIVERY_REQUESTED_KEY, payload.toString(), RequestIds.current().orElse(null),
                Timestamp.from(requestedAt));
        return eventId;
    }

    private ObjectNode location(DeliveryDestination location) {
        return mapper.createObjectNode().put("description", location.address())
                .put("latitude", location.latitude()).put("longitude", location.longitude());
    }
}
