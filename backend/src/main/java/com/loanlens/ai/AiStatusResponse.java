package com.loanlens.ai;

/** Whether a real AI provider is configured. Contains no secret (never the key, base URL or any credential). */
public record AiStatusResponse(boolean providerConfigured, String provider, String model, String mode, String message) {

    public static final String MODE_AI = "AI";
    public static final String MODE_FALLBACK_ONLY = "FALLBACK_ONLY";
}
