package com.loanlens.ai;

/**
 * A provider call failed. The message is for the server log only and never contains the API key, the prompt or the
 * model's answer; clients only ever see the {@link Kind}-based fallback reason.
 */
public class AiProviderException extends RuntimeException {

    public enum Kind {
        /** No provider/key is configured. */
        NOT_CONFIGURED,
        /** The provider rejected our credentials or permissions (401/403). */
        AUTHENTICATION,
        /** Billing, credit or payment problem on the provider account (402 billing_error). */
        BILLING,
        /** The provider is rate limiting us (429; also used for spend/usage caps). */
        RATE_LIMITED,
        /** No answer within the configured timeout. */
        TIMEOUT,
        /** Network failure, 5xx or overloaded provider. */
        UNAVAILABLE,
        /** The model declined to answer or the answer was cut off. */
        REFUSED_OR_INCOMPLETE,
        /** Anything else, including a request the provider rejected as invalid (400/404). */
        ERROR
    }

    private final Kind kind;

    public AiProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
