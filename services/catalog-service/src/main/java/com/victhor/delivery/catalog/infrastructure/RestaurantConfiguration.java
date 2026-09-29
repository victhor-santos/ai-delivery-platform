package com.victhor.delivery.catalog.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.catalog.application.RestaurantRepository;
import com.victhor.delivery.catalog.application.RestaurantService;

@Configuration(proxyBeanMethods = false)
class RestaurantConfiguration {

    @Bean
    RestaurantService restaurantService(RestaurantRepository restaurants) {
        return new RestaurantService(restaurants);
    }
}
