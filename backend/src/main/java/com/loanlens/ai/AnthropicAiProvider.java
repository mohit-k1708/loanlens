package com.loanlens.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

/**
 * Anthropic (Claude) provider built on the official Anthropic Java SDK.
 *
 * <ul>
 *   <li>The key is read from configuration (environment) only and is passed to the SDK; it is never logged.</li>
 *   <li>No sampling parameters are sent (newer Claude models reject them) and no assistant prefill is used.</li>
 *   <li>SDK retries are disabled: the caller falls back to the deterministic explanation instead of waiting.</li>
 *   <li>Only text blocks are used; a refusal or a truncated answer is reported as such.</li>
 * </ul>
 */
public class AnthropicAiProvider implements AiProvider {

    private final AnthropicClient client;
    private final String model;
    private final int maxOutputTokens;

    public AnthropicAiProvider(AiProperties properties) {
        // The Messages API authenticates with the "x-api-key" header. It is set explicitly because SDK 3.2.0, given only
        // apiKey(...), sent "Authorization: Bearer <key>" (found by the stub-server test). With the header present the SDK
        // adds no Authorization header.
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .putHeader("x-api-key", properties.apiKey())
                .timeout(properties.timeout())
                .maxRetries(0);
        if (!properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        this.client = builder.build();
        this.model = properties.model();
        this.maxOutputTokens = properties.maxOutputTokens();
    }

    @Override
    public String name() {
        return AiProperties.PROVIDER_ANTHROPIC;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOutputTokens)
                .system(systemPrompt)
                .addUserMessage(userMessage)
                .build();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (RateLimitException e) {
            throw new AiProviderException(AiProviderException.Kind.RATE_LIMITED, "Provider rate limit (HTTP 429).", e);
        } catch (AnthropicServiceException e) {
            throw new AiProviderException(kindFor(e.statusCode()), "Provider returned HTTP " + e.statusCode() + ".", e);
        } catch (AnthropicIoException e) {
            boolean timeout = isTimeout(e);
            throw new AiProviderException(timeout ? AiProviderException.Kind.TIMEOUT : AiProviderException.Kind.UNAVAILABLE,
                    timeout ? "Provider call timed out." : "Could not reach the provider.", e);
        } catch (AnthropicException e) {
            throw new AiProviderException(AiProviderException.Kind.ERROR, "Provider call failed: " + e.getClass().getSimpleName(), e);
        }

        try {
            return extractText(response);
        } catch (AiProviderException e) {
            throw e;
        } catch (AnthropicException e) {
            // The SDK parses lazily: a 200 whose body is not a Messages response fails here, not inside create().
            throw new AiProviderException(AiProviderException.Kind.ERROR, "Provider returned an unexpected response format: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String extractText(Message response) {
        StopReason stop = response.stopReason().orElse(null);
        if (stop != null && (stop.equals(StopReason.REFUSAL) || stop.equals(StopReason.MAX_TOKENS))) {
            throw new AiProviderException(AiProviderException.Kind.REFUSED_OR_INCOMPLETE,
                    "Provider stopped with reason " + stop.asString() + ".");
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new AiProviderException(AiProviderException.Kind.REFUSED_OR_INCOMPLETE, "Provider returned no text.");
        }
        return text;
    }

    private static AiProviderException.Kind kindFor(int status) {
        return switch (status) {
            case 401, 403 -> AiProviderException.Kind.AUTHENTICATION;
            case 402 -> AiProviderException.Kind.BILLING;
            case 408, 504 -> AiProviderException.Kind.TIMEOUT;
            case 429 -> AiProviderException.Kind.RATE_LIMITED;
            case 400, 404, 413, 422 -> AiProviderException.Kind.ERROR;
            default -> status >= 500 ? AiProviderException.Kind.UNAVAILABLE : AiProviderException.Kind.ERROR;
        };
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
