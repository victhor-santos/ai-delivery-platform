package com.victhor.delivery.delivery.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the gateway request id, or creates one for direct calls, in the logging context and the response. It runs
 * before Spring Security so rejected requests are correlated too. Only method, path, status and duration are logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.resolve(request.getHeader(RequestIds.HEADER));
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put(RequestIds.MDC_KEY, requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator")) {
                log.info("method={} path={} status={} durationMs={}", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            }
            MDC.remove(RequestIds.MDC_KEY);
        }
    }
}
