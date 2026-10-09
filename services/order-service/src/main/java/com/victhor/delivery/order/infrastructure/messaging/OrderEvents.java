package com.victhor.delivery.order.infrastructure.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Exchange owned by Order. Each consumer declares and binds its own queue. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OrderEvents {

    public static final String EXCHANGE = "orders.events";
    public static final String DELIVERY_REQUESTED_KEY = "order.delivery-requested";
    public static final String DELIVERY_REQUESTED_TYPE = "order.delivery-requested.v1";

    @Bean
    TopicExchange orderEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
