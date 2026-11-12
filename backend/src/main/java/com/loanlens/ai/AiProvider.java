package com.loanlens.ai;

/**
 * The only seam between the application and an AI vendor. An implementation sends one system prompt plus one user
 * message and returns the model's raw text. Everything else (what facts go in, validation of what comes back, the
 * fallback) is vendor-independent and lives in {@link AiExplanationService}.
 */
public interface AiProvider {

    /** Short vendor name, e.g. {@code anthropic}. Not secret. */
    String name();

    /** Model id in use. Not secret. */
    String model();

    /** False when no call can be made (no key / provider disabled). */
    boolean isConfigured();

    /**
     * @return the model's text answer (never null)
     * @throws AiProviderException on any failure
     */
    String complete(String systemPrompt, String userMessage);
}
