package com.victhor.delivery.delivery.infrastructure;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.delivery.application.DeliveryRouteRepository;
import com.victhor.delivery.delivery.application.DeliveryRouteService;
import com.victhor.delivery.delivery.application.RouteOptimizer;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class DeliveryRouteConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient routeHttpClient(@Value("${delivery.routing.connect-timeout-ms}") long connectTimeoutMs) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    }

    @Bean
    RouteOptimizer routeOptimizer(HttpClient routeHttpClient, ObjectMapper mapper, MeterRegistry registry,
            @Value("${delivery.routing.url}") String url,
            @Value("${delivery.routing.timeout-ms}") long timeoutMs) {
        return new MeteredRouteOptimizer(
                new FastApiRouteOptimizerClient(routeHttpClient, mapper, url, Duration.ofMillis(timeoutMs)), registry);
    }

    @Bean
    DeliveryRouteService deliveryRouteService(DeliveryRouteRepository routes, RouteOptimizer optimizer, Clock clock) {
        return new DeliveryRouteService(routes, optimizer, clock);
    }
}
