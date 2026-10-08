package com.victhor.delivery.payment.infrastructure;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.payment.application.OrderLookup;
import com.victhor.delivery.payment.application.PaymentRepository;
import com.victhor.delivery.payment.application.PaymentService;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    HttpClient integrationHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Bean
    HttpOrderLookup httpOrderLookup(HttpClient client, ObjectMapper mapper,
            @Value("${payment.integration.order-url}") String orderUrl,
            @Value("${payment.integration.timeout-ms}") long timeoutMs) {
        return new HttpOrderLookup(client, mapper, orderUrl, Duration.ofMillis(timeoutMs));
    }

    @Bean
    PaymentService paymentService(PaymentRepository payments, OrderLookup orders, Clock clock) {
        return new PaymentService(payments, orders, clock);
    }
}
