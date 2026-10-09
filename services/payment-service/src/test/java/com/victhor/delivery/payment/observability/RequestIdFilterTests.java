package com.victhor.delivery.payment.observability;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void keepsAValidRequestIdForTheRequestAndOutboundCalls() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader("X-Request-Id", "web-0123456789abcdef");
        var response = new MockHttpServletResponse();
        var outbound = new AtomicReference<HttpRequest>();

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
        }, (servletRequest, servletResponse, chain) -> outbound.set(
                RequestIds.propagate(HttpRequest.newBuilder(URI.create("http://localhost/remote"))).build())));

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("web-0123456789abcdef");
        assertThat(outbound.get().headers().firstValue("X-Request-Id")).hasValue("web-0123456789abcdef");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesAnInvalidRequestIdWithAGeneratedOne() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader("X-Request-Id", "bad id;");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void doesNotPropagateOutsideARequest() {
        var outbound = RequestIds.propagate(HttpRequest.newBuilder(URI.create("http://localhost/remote"))).build();

        assertThat(outbound.headers().firstValue("X-Request-Id")).isEmpty();
    }
}
