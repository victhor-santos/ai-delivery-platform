package com.victhor.delivery.delivery.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.delivery.application.CourierRepository;
import com.victhor.delivery.delivery.application.CourierService;
import com.victhor.delivery.delivery.application.DeliveryRepository;
import com.victhor.delivery.delivery.application.DeliveryService;

@Configuration(proxyBeanMethods = false)
class DeliveryConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DeliveryService deliveryService(DeliveryRepository deliveries, Clock clock) {
        return new DeliveryService(deliveries, clock);
    }

    @Bean
    CourierService courierService(CourierRepository couriers) {
        return new CourierService(couriers);
    }
}
