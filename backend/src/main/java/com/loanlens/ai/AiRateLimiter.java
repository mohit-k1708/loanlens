package com.loanlens.ai;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A small in-memory fixed-window rate limiter for {@code /api/ai/explain}, keyed by client address. It protects the
 * (paid) AI provider quota from a runaway client or loop. It is per application instance and is NOT a substitute for
 * authentication or a gateway-level limit: behind a reverse proxy the key is the proxy's address unless the proxy is
 * configured to forward the client address.
 */
@Component
public class AiRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private record Window(long startMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AiProperties properties;
    private final Clock clock;

    public AiRateLimiter(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @throws RateLimitExceededException when the client already used its allowance in the current minute */
    public void check(String clientKey) {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= WINDOW_MILLIS);
        }
        String key = clientKey == null ? "unknown" : clientKey;
        int limit = properties.rateLimitPerMinute();
        boolean[] exceeded = {false};
        long[] windowStart = {now};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMillis() >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            windowStart[0] = w.startMillis();
            if (w.count() >= limit) {
                exceeded[0] = true;
                return w;
            }
            return new Window(w.startMillis(), w.count() + 1);
        });
        if (exceeded[0]) {
            int retry = (int) Math.max(1, (windowStart[0] + WINDOW_MILLIS - now + 999) / 1000);
            throw new RateLimitExceededException(retry);
        }
    }
}
