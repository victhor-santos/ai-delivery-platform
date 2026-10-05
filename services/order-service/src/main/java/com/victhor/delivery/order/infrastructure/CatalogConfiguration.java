package com.victhor.delivery.order.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    @Bean
    HttpCatalogLookup httpCatalogLookup(HttpClient client, ObjectMapper mapper,
            @Value("${order.integration.catalog-url}") String catalogUrl,
            @Value("${order.integration.timeout-ms}") long timeoutMs) {
        return new HttpCatalogLookup(client, mapper, catalogUrl, Duration.ofMillis(timeoutMs));
    }
}
