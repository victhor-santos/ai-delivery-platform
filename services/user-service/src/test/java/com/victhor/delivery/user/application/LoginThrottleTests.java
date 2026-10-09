package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LoginThrottleTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T12:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(clock);

    @Test
    void refusesTheSixthAttemptInTheWindowAndReportsWhenToRetry() {
        for (int attempt = 0; attempt < LoginThrottle.MAX_ATTEMPTS; attempt++) {
            throttle.acquire("a@example.test");
        }
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> throttle.acquire("a@example.test"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                        refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(600));
        throttle.acquire("b@example.test");
    }

    @Test
    void opensAgainWhenTheWindowEndsAndAfterASuccess() {
        for (int attempt = 0; attempt < LoginThrottle.MAX_ATTEMPTS; attempt++) {
            throttle.acquire("a@example.test");
        }
        clock.advance(LoginThrottle.WINDOW);
        throttle.acquire("a@example.test");
        throttle.succeeded("a@example.test");
        for (int attempt = 0; attempt < LoginThrottle.MAX_ATTEMPTS; attempt++) {
            throttle.acquire("a@example.test");
        }
    }

    @Test
    void concurrentGuessesCannotExceedTheLimit() throws Exception {
        var allowed = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int attempt = 0; attempt < 40; attempt++) {
                executor.submit(() -> {
                    start.await();
                    try {
                        throttle.acquire("a@example.test");
                        allowed.incrementAndGet();
                    } catch (TooManyLoginAttemptsException refused) {
                        // expected for all but five
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(allowed).hasValue(LoginThrottle.MAX_ATTEMPTS);
    }

    @Test
    void tracksABoundedNumberOfAccounts() {
        for (int account = 0; account < LoginThrottle.MAX_TRACKED_ACCOUNTS + 50; account++) {
            throttle.acquire("user-" + account + "@example.test");
        }
        assertThat(throttle.trackedAccounts()).isEqualTo(LoginThrottle.MAX_TRACKED_ACCOUNTS);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
