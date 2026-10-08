package com.victhor.delivery.order.infrastructure;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.order.application.DeliveryGateway;
import com.victhor.delivery.order.application.DeliveryRequestRepository;
import com.victhor.delivery.order.application.OrderDeliveryService;
import com.victhor.delivery.order.application.OrderPaymentRepository;
import com.victhor.delivery.order.application.OrderPaymentService;
import com.victhor.delivery.order.application.OrderRepository;
import com.victhor.delivery.order.application.RestaurantLookup;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class OrderDeliveryConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient integrationHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Bean
    HttpDeliveryIntegration httpDeliveryIntegration(HttpClient client, ObjectMapper mapper,
            @Value("${order.integration.catalog-url}") String catalogUrl,
            @Value("${order.integration.delivery-url}") String deliveryUrl,
            @Value("${order.integration.timeout-ms}") long timeoutMs) {
        return new HttpDeliveryIntegration(client, mapper, catalogUrl, deliveryUrl, Duration.ofMillis(timeoutMs));
    }

    @Bean
    HttpPaymentGateway httpPaymentGateway(HttpClient client, ObjectMapper mapper,
            @Value("${order.integration.payment-url}") String paymentUrl,
            @Value("${order.integration.timeout-ms}") long timeoutMs) {
        return new HttpPaymentGateway(client, mapper, paymentUrl, Duration.ofMillis(timeoutMs));
    }

    @Bean
    OrderPaymentService orderPaymentService(OrderRepository orders, OrderPaymentRepository payments,
            HttpPaymentGateway gateway, Clock clock) {
        return new OrderPaymentService(orders, payments, gateway, clock);
    }

    @Bean
    OrderDeliveryService orderDeliveryService(OrderRepository orders, DeliveryRequestRepository requests,
            RestaurantLookup restaurants, DeliveryGateway deliveries, Clock clock) {
        return new OrderDeliveryService(orders, requests, restaurants, deliveries, clock);
    }
}
