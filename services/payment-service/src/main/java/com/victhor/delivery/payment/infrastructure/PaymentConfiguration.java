package com.victhor.delivery.payment.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.payment.application.PaymentRepository;
import com.victhor.delivery.payment.application.PaymentService;

@Configuration(proxyBeanMethods = false)
class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentService paymentService(PaymentRepository payments, Clock clock) {
        return new PaymentService(payments, clock);
    }
}
