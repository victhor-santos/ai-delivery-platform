package com.victhor.delivery.delivery.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class DeliveryRouteConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient routeHttpClient(@Value("${delivery.routing.connect-timeout-ms}") long connectTimeoutMs) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    }

    @Bean
    FastApiRouteOptimizerClient routeOptimizer(HttpClient routeHttpClient, ObjectMapper mapper,
            @Value("${delivery.routing.url}") String url,
            @Value("${delivery.routing.timeout-ms}") long timeoutMs) {
        return new FastApiRouteOptimizerClient(routeHttpClient, mapper, url, Duration.ofMillis(timeoutMs));
    }
}
