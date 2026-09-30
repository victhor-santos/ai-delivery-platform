package com.victhor.delivery.order.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.application.OrderService;

@Configuration(proxyBeanMethods = false)
class OrderConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderService orderService(OrderRepository orders, Clock clock) {
        return new OrderService(orders, clock);
    }
}
