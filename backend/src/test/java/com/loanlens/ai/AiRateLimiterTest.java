package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiRateLimiterTest {

    /** A clock the test can move. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-12-31T10:00:00Z"));

        void advanceSeconds(long s) {
            now.updateAndGet(i -> i.plusSeconds(s));
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
            return now.get();
        }
    }

    private static AiRateLimiter limiter(MutableClock clock, int perMinute) {
        return new AiRateLimiter(new AiProperties("anthropic", "m", "", "", 30, 100, perMinute, 8192), clock);
    }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        MutableClock clock = new MutableClock();
        AiRateLimiter limiter = limiter(clock, 3);

        assertThatCode(() -> {
            limiter.check("10.0.0.1");
            limiter.check("10.0.0.1");
            limiter.check("10.0.0.1");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isBetween(1, 60);
                    assertThat(e.getMessage()).contains("Too many AI requests");
                });
    }

    @Test
    void clientsAreCountedSeparately() {
        MutableClock clock = new MutableClock();
        AiRateLimiter limiter = limiter(clock, 1);

        limiter.check("10.0.0.1");
        assertThatCode(() -> limiter.check("10.0.0.2")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("10.0.0.1")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void theAllowanceResetsAfterAMinute() {
        MutableClock clock = new MutableClock();
        AiRateLimiter limiter = limiter(clock, 2);
        limiter.check("c");
        limiter.check("c");
        assertThatThrownBy(() -> limiter.check("c")).isInstanceOf(RateLimitExceededException.class);

        clock.advanceSeconds(61);

        assertThatCode(() -> limiter.check("c")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterShrinksAsTheWindowPasses() {
        MutableClock clock = new MutableClock();
        AiRateLimiter limiter = limiter(clock, 1);
        limiter.check("c");
        clock.advanceSeconds(45);

        assertThatThrownBy(() -> limiter.check("c"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getRetryAfterSeconds()).isBetween(14, 16));
    }

    @Test
    void unknownClientAddressIsStillLimited() {
        MutableClock clock = new MutableClock();
        AiRateLimiter limiter = limiter(clock, 1);
        limiter.check(null);
        assertThatThrownBy(() -> limiter.check(null)).isInstanceOf(RateLimitExceededException.class);
    }
}
