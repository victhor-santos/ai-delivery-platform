package com.victhor.delivery.payment.observability;

import java.net.http.HttpRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Request id shared with the gateway contract: {@value #HEADER}, letters, digits and hyphens, 8 to 64 characters. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private RequestIds() {
    }

    /** Id of the request handled by the current thread, to propagate on outbound calls. */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /** Adds the current request id, when there is one, to a call made to another service. */
    public static HttpRequest.Builder propagate(HttpRequest.Builder request) {
        current().ifPresent(requestId -> request.header(HEADER, requestId));
        return request;
    }

    static String resolve(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
