package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.AiProviderException.Kind;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AnthropicAiProvider} against a LOCAL STUB of the Anthropic API (see {@link StubAnthropicServer}).
 * This proves request format, response parsing, error mapping and timeout behaviour. It does not prove that a real
 * Anthropic model answers: no real API key was available when these tests were written.
 */
class AnthropicAiProviderTest {

    private static final String KEY = "sk-ant-test-KEY-12345-do-not-leak";
    private StubAnthropicServer stub;

    @BeforeEach
    void start() throws IOException {
        stub = new StubAnthropicServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private AnthropicAiProvider provider(int timeoutSeconds) {
        return new AnthropicAiProvider(new AiProperties("anthropic", "claude-sonnet-5-5", KEY, stub.baseUrl(), timeoutSeconds, 1234, 20, 8192));
    }

    private static void assertNoSecretIn(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            assertThat(String.valueOf(x.getMessage())).doesNotContain(KEY);
        }
    }

    @Test
    @DisplayName("sends POST /v1/messages with the key and version headers, the model, the limits and no sampling parameters")
    void requestFormat() throws Exception {
        stub.respondWithText("{\"summary\":\"hello\"}");

        String text = provider(10).complete("SYSTEM PROMPT TEXT", "USER MESSAGE TEXT");

        assertThat(text).isEqualTo("{\"summary\":\"hello\"}");
        assertThat(stub.requests).hasSize(1);
        StubAnthropicServer.Captured r = stub.requests.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v1/messages");
        assertThat(headerIgnoreCase(r, "x-api-key")).as("Authorization=" + headerIgnoreCase(r, "authorization") + " headers=" + r.headers().keySet()).isEqualTo(KEY);
        assertThat(headerIgnoreCase(r, "anthropic-version")).as("the SDK pins its own API version").isNotBlank();
        // the key travels only in x-api-key: no duplicate Bearer Authorization header
        assertThat(headerIgnoreCase(r, "authorization")).isNull();
        assertThat(headerIgnoreCase(r, "content-type")).contains("application/json");

        JsonNode body = new ObjectMapper().readTree(r.body());
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1234);
        assertThat(body.get("system").asText()).isEqualTo("SYSTEM PROMPT TEXT");
        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").toString()).contains("USER MESSAGE TEXT");
        // newer Claude models reject non-default sampling parameters, and no thinking/prefill is requested
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("top_k")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("stream")).isFalse();
        // the key is a header only, never part of the body
        assertThat(r.body()).doesNotContain(KEY);
    }

    private static String headerIgnoreCase(StubAnthropicServer.Captured r, String name) {
        return r.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }

    @Test
    @DisplayName("joins multiple text blocks and ignores other block types")
    void parsesContentBlocks() {
        stub.status = 200;
        stub.responseBody = "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hmm\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"part one \"},{\"type\":\"text\",\"text\":\"part two\"}],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

        assertThat(provider(10).complete("s", "u")).isEqualTo("part one part two");
    }

    @Test
    void reportsItself() {
        AnthropicAiProvider p = provider(10);
        assertThat(p.name()).isEqualTo("anthropic");
        assertThat(p.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(p.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("401 -> AUTHENTICATION, one attempt, and the key is not in the exception")
    void unauthorised() {
        stub.respondWithError(401, "authentication_error", "invalid x-api-key");

        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.AUTHENTICATION);
                    assertNoSecretIn(e);
                });
        assertThat(stub.count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 -> RATE_LIMITED and the SDK does not retry (the caller falls back instead of waiting)")
    void rateLimited() {
        stub.respondWithError(429, "rate_limit_error", "slow down");

        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(stub.count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 and 529 (overloaded) -> UNAVAILABLE, no retries")
    void serverErrors() {
        stub.respondWithError(500, "api_error", "boom");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        stub.respondWithError(529, "overloaded_error", "overloaded");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
        assertThat(stub.count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("402 billing_error (no credits) -> BILLING; an invalid model id (404) -> ERROR; neither retried")
    void billingAndInvalidModel() {
        stub.respondWithError(402, "billing_error", "Your credit balance is too low");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BILLING);
                    assertNoSecretIn(e);
                });
        stub.respondWithError(404, "not_found_error", "model: not-a-real-model");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.ERROR));
        assertThat(stub.count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a 200 whose body is not a Messages response is an error, not a crash")
    void unexpectedResponseFormat() {
        stub.status = 200;
        stub.responseBody = "<html>gateway splash page</html>";
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isIn(Kind.ERROR, Kind.UNAVAILABLE));
        stub.responseBody = "{\"unexpected\":true}";
        assertThatThrownBy(() -> provider(10).complete("s", "u")).isInstanceOf(AiProviderException.class);
    }

    @Test
    void badRequestAndNotFoundAreGenericErrors() {
        stub.respondWithError(400, "invalid_request_error", "bad");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.ERROR));
        stub.respondWithError(404, "not_found_error", "model: nope");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.ERROR));
    }

    @Test
    @DisplayName("no answer within the timeout -> TIMEOUT")
    void timeout() {
        stub.delayMillis = 4000;
        long start = System.nanoTime();

        assertThatThrownBy(() -> provider(1).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
                    assertNoSecretIn(e);
                });
        assertThat((System.nanoTime() - start) / 1_000_000).as("returned promptly after the timeout").isLessThan(3500);
    }

    @Test
    void unreachableProviderIsUnavailable() {
        stub.close();   // nothing listens any more
        assertThatThrownBy(() -> provider(2).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isIn(Kind.UNAVAILABLE, Kind.TIMEOUT));
    }

    @Test
    @DisplayName("a truncated (max_tokens) or refused answer is not used")
    void incompleteAnswers() {
        stub.respondWithMessage("{\"summary\":\"cut off", "max_tokens");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED_OR_INCOMPLETE));
        stub.respondWithMessage("I can't help with that.", "refusal");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED_OR_INCOMPLETE));
    }

    @Test
    void emptyTextIsNotAnAnswer() {
        stub.respondWithText("   ");
        assertThatThrownBy(() -> provider(10).complete("s", "u"))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED_OR_INCOMPLETE));
    }

    @Test
    @DisplayName("AiProperties never prints the API key")
    void propertiesMaskTheKey() {
        AiProperties p = new AiProperties("anthropic", "m", KEY, "", 30, 100, 5, 1000);
        assertThat(p.toString()).doesNotContain(KEY).contains("<hidden>");
        assertThat(new AiProperties("anthropic", "m", "", "", 0, 0, 0, 0).toString()).contains("<not set>");
        assertThat(p.providerConfigured()).isTrue();
        assertThat(new AiProperties("anthropic", "m", " ", "", 0, 0, 0, 0).providerConfigured()).isFalse();
        assertThat(new AiProperties("none", "m", KEY, "", 0, 0, 0, 0).providerConfigured()).isFalse();
    }
}
