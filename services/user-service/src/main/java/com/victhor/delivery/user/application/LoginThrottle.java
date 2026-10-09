package com.victhor.delivery.user.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limits login attempts per account within a fixed window. Attempts are counted when they start, so concurrent
 * guesses cannot slip past the limit; a successful login clears the count. State is in memory, per instance.
 */
public class LoginThrottle {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED_ACCOUNTS = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_TRACKED_ACCOUNTS;
        }
    };

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Counts an attempt for the account, or refuses it while the account is over the limit. */
    public synchronized void acquire(String account) {
        Instant now = clock.instant();
        Window window = windows.get(account);
        if (window == null || !now.isBefore(window.endsAt())) {
            windows.remove(account);
            windows.put(account, new Window(now.plus(WINDOW), 1));
            return;
        }
        if (window.attempts() >= MAX_ATTEMPTS) {
            throw new TooManyLoginAttemptsException(Duration.between(now, window.endsAt()));
        }
        windows.put(account, new Window(window.endsAt(), window.attempts() + 1));
    }

    public synchronized void succeeded(String account) {
        windows.remove(account);
    }

    synchronized int trackedAccounts() {
        return windows.size();
    }

    private record Window(Instant endsAt, int attempts) {
    }
}
