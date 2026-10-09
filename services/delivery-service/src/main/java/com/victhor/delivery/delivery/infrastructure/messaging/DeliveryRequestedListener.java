package com.victhor.delivery.delivery.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.victhor.delivery.delivery.application.DeliveryService;
import com.victhor.delivery.delivery.domain.DeliveryLocation;
import com.victhor.delivery.delivery.domain.DeliveryStateConflictException;
import com.victhor.delivery.delivery.domain.GeoPoint;
import com.victhor.delivery.delivery.observability.RequestIds;

/**
 * Creates the delivery requested by Order. Delivery is at least once, so a repeated event returns the delivery
 * already stored for the order. Invalid or conflicting events are rejected without requeue and reach the
 * dead-letter queue; database failures propagate and are retried.
 */
@Component
class DeliveryRequestedListener {

    static final String METRIC = "delivery.requests.consumed";
    private static final Logger log = LoggerFactory.getLogger(DeliveryRequestedListener.class);

    private final DeliveryService deliveries;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;

    DeliveryRequestedListener(DeliveryService deliveries, ObjectMapper mapper, MeterRegistry registry) {
        this.deliveries = deliveries;
        this.mapper = mapper;
        this.registry = registry;
    }

    @RabbitListener(queues = DeliveryMessaging.DELIVERY_REQUESTED_QUEUE)
    void onDeliveryRequested(Message message) {
        var properties = message.getMessageProperties();
        RequestIds.runWith(properties.getHeader(RequestIds.HEADER), () -> handle(message));
    }

    private void handle(Message message) {
        var properties = message.getMessageProperties();
        DeliveryRequested event;
        try {
            if (!DeliveryMessaging.DELIVERY_REQUESTED_TYPE.equals(properties.getType())) {
                throw new IllegalArgumentException("Unsupported event type");
            }
            event = parse(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | NullPointerException | JacksonException exception) {
            reject(properties.getMessageId(), null, "invalid", exception);
            return;
        }
        try {
            var creation = deliveries.createForOrder(event.orderId(), event.customerId(), event.origin(),
                    event.destination());
            String outcome = creation.created() ? "created" : "duplicate";
            registry.counter(METRIC, "outcome", outcome).increment();
            log.info("event=order.delivery-requested eventId={} orderId={} deliveryId={} outcome={}",
                    event.eventId(), event.orderId(), creation.delivery().id(), outcome);
        } catch (DeliveryStateConflictException exception) {
            reject(event.eventId().toString(), event.orderId(), "conflict", exception);
        }
    }

    private void reject(String eventId, UUID orderId, String outcome, Exception cause) {
        registry.counter(METRIC, "outcome", outcome).increment();
        log.warn("event=order.delivery-requested eventId={} orderId={} outcome={} reason={}", eventId, orderId,
                outcome, cause.getMessage());
        throw new AmqpRejectAndDontRequeueException("Delivery request rejected: " + outcome, cause);
    }

    private DeliveryRequested parse(String body) {
        JsonNode json = mapper.readTree(body);
        return new DeliveryRequested(uuid(json.path("eventId")), uuid(json.path("orderId")),
                uuid(json.path("customerId")), location(json.path("origin")), location(json.path("destination")));
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isString()) {
            throw new IllegalArgumentException("Missing identifier");
        }
        UUID id = UUID.fromString(node.asString());
        if (!id.toString().equals(node.asString())) {
            throw new IllegalArgumentException("Identifier must be a canonical UUID");
        }
        return id;
    }

    private static DeliveryLocation location(JsonNode node) {
        JsonNode description = node.path("description");
        JsonNode latitude = node.path("latitude");
        JsonNode longitude = node.path("longitude");
        if (!description.isString() || !latitude.isNumber() || !longitude.isNumber()) {
            throw new IllegalArgumentException("Incomplete location");
        }
        return new DeliveryLocation(description.asString(), new GeoPoint(latitude.asDouble(), longitude.asDouble()));
    }

    private record DeliveryRequested(UUID eventId, UUID orderId, UUID customerId, DeliveryLocation origin,
            DeliveryLocation destination) {
    }
}
