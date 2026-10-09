package com.victhor.delivery.gateway;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Assigns the request id that every downstream service logs and returns. A client value is kept only when it
 * matches a restricted pattern, so it cannot inject content into logs or headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdWebFilter implements WebFilter {

    static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));
        var request = exchange.getRequest().mutate().headers(headers -> headers.set(HEADER, requestId)).build();
        var mutated = exchange.mutate().request(request).build();
        mutated.getResponse().getHeaders().set(HEADER, requestId);
        if (request.getPath().value().startsWith("/actuator")) {
            return chain.filter(mutated);
        }
        long started = System.nanoTime();
        return chain.filter(mutated).doFinally(signal -> {
            var status = mutated.getResponse().getStatusCode();
            log.info("requestId={} method={} path={} status={} durationMs={}", requestId, request.getMethod(),
                    request.getPath().value(), status == null ? "-" : status.value(),
                    (System.nanoTime() - started) / 1_000_000);
        });
    }

    static String resolve(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
