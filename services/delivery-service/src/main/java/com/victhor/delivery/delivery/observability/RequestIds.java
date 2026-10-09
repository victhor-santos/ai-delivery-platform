package com.victhor.delivery.delivery.observability;

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

    /** Runs work triggered by a message under the request id it carries, or a new one, as the HTTP filter does. */
    public static void runWith(String candidate, Runnable work) {
        MDC.put(MDC_KEY, resolve(candidate));
        try {
            work.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
