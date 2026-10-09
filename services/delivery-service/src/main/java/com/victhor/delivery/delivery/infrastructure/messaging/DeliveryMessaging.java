package com.victhor.delivery.delivery.infrastructure.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology consumed by this service. Order owns the event exchange and declares it too; declaring an existing
 * exchange with the same arguments is a no-op. Messages that fail validation, or keep failing after the retries,
 * go to a dead-letter queue instead of being requeued forever.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryMessaging {

    private static final Logger log = LoggerFactory.getLogger(DeliveryMessaging.class);

    static final String ORDER_EVENTS_EXCHANGE = "orders.events";
    static final String DELIVERY_REQUESTED_KEY = "order.delivery-requested";
    static final String DELIVERY_REQUESTED_TYPE = "order.delivery-requested.v1";
    static final String DELIVERY_REQUESTED_QUEUE = "delivery-service.order.delivery-requested";
    static final String DEAD_LETTER_EXCHANGE = "delivery-service.dead-letter";
    static final String DELIVERY_REQUESTED_DLQ = DELIVERY_REQUESTED_QUEUE + ".dlq";

    @Bean
    TopicExchange orderEvents() {
        return new TopicExchange(ORDER_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deliveryDeadLetters() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue deliveryRequestedQueue() {
        return QueueBuilder.durable(DELIVERY_REQUESTED_QUEUE).quorum()
                .withArguments(Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", DELIVERY_REQUESTED_QUEUE))
                .build();
    }

    @Bean
    Queue deliveryRequestedDeadLetterQueue() {
        return QueueBuilder.durable(DELIVERY_REQUESTED_DLQ).quorum().build();
    }

    @Bean
    Binding deliveryRequestedBinding() {
        return BindingBuilder.bind(deliveryRequestedQueue()).to(orderEvents()).with(DELIVERY_REQUESTED_KEY);
    }

    @Bean
    Binding deliveryRequestedDeadLetterBinding() {
        return BindingBuilder.bind(deliveryRequestedDeadLetterQueue()).to(deliveryDeadLetters())
                .with(DELIVERY_REQUESTED_QUEUE);
    }

    /** Retrying cannot fix an invalid or conflicting request, so those go straight to the dead-letter queue. */
    @Bean
    RabbitListenerRetrySettingsCustomizer skipRetryForRejectedMessages() {
        return settings -> settings.setExceptionPredicate(DeliveryMessaging::isTransient);
    }

    /**
     * Sends the message to the dead-letter queue once retries end. Unlike the default recoverer, it never logs the
     * body, which carries addresses; the listener already logged why a message was rejected.
     */
    @Bean
    MessageRecoverer deadLetterWithoutBody() {
        return (message, cause) -> {
            if (isTransient(cause)) {
                log.warn("Retries exhausted; dead-lettering messageId={} cause={}",
                        message.getMessageProperties().getMessageId(), rootCause(cause).getClass().getSimpleName());
            }
            throw new ListenerExecutionFailedException("Message dead-lettered",
                    new AmqpRejectAndDontRequeueException(cause), message);
        };
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException) {
                return false;
            }
        }
        return true;
    }
}
