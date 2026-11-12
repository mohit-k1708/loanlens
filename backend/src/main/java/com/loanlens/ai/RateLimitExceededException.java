package com.loanlens.ai;

/** Too many AI requests from one client in a short time (HTTP 429). */
public class RateLimitExceededException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitExceededException(int retryAfterSeconds) {
        super("Too many AI requests. Please wait " + retryAfterSeconds + " seconds and try again.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
