package com.victhor.delivery.user.observability;

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
    void exposesAValidRequestIdWhileHandlingTheRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader("X-Request-Id", "web-0123456789abcdef");
        var response = new MockHttpServletResponse();
        var seen = new String[1];

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
        }, (servletRequest, servletResponse, chain) -> seen[0] = RequestIds.current().orElseThrow()));

        assertThat(seen[0]).isEqualTo("web-0123456789abcdef");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("web-0123456789abcdef");
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
}
