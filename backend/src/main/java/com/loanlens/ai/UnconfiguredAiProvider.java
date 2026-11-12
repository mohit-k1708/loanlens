package com.loanlens.ai;

/** Used when no provider/key is configured: the app stays fully operational and answers with the deterministic explanation. */
public class UnconfiguredAiProvider implements AiProvider {

    private final String name;
    private final String model;

    public UnconfiguredAiProvider(String name, String model) {
        this.name = name;
        this.model = model;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        throw new AiProviderException(AiProviderException.Kind.NOT_CONFIGURED, "No AI provider is configured.");
    }
}
