package com.loanlens.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI explanation settings ({@code loanlens.ai.*}). Everything comes from environment variables; the API key is never
 * committed, never sent to the frontend and never logged ({@link #toString()} masks it).
 *
 * @param provider          {@code anthropic} or {@code none}. With {@code none}, or without an API key, the feature
 *                          still works but answers with the deterministic backend explanation (clearly labelled).
 * @param model             model id sent to the provider
 * @param apiKey            provider API key; blank means "not configured"
 * @param baseUrl           optional provider base URL override (blank = the provider's default). Used for tests/proxies.
 * @param timeoutSeconds    whole-request timeout for one provider call
 * @param maxOutputTokens   cap on the model's response
 * @param rateLimitPerMinute maximum {@code /api/ai/explain} requests per client address per minute
 * @param maxRequestBytes   maximum request body size accepted on {@code /api/ai/*}
 */
@ConfigurationProperties(prefix = "loanlens.ai")
public record AiProperties(String provider, String model, String apiKey, String baseUrl, int timeoutSeconds,
                           int maxOutputTokens, int rateLimitPerMinute, int maxRequestBytes) {

    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_NONE = "none";

    public AiProperties {
        provider = provider == null || provider.isBlank() ? PROVIDER_ANTHROPIC : provider.trim().toLowerCase();
        model = model == null || model.isBlank() ? "claude-sonnet-5-5" : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        maxOutputTokens = maxOutputTokens <= 0 ? 4000 : maxOutputTokens;
        rateLimitPerMinute = rateLimitPerMinute <= 0 ? 20 : rateLimitPerMinute;
        maxRequestBytes = maxRequestBytes <= 0 ? 8192 : maxRequestBytes;
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /** True when a real provider call can be attempted. */
    public boolean providerConfigured() {
        return PROVIDER_ANTHROPIC.equals(provider) && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AiProperties[provider=" + provider + ", model=" + model + ", apiKey=" + (apiKey.isBlank() ? "<not set>" : "<hidden>")
                + ", baseUrl=" + baseUrl + ", timeoutSeconds=" + timeoutSeconds + ", maxOutputTokens=" + maxOutputTokens
                + ", rateLimitPerMinute=" + rateLimitPerMinute + ", maxRequestBytes=" + maxRequestBytes + "]";
    }
}
