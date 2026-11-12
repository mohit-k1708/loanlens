package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loanlens.ai.AiProperties;
import com.loanlens.ai.AiProviderException;
import com.loanlens.ai.AnthropicAiProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * LIVE verification against the REAL Anthropic API. It runs ONLY when the environment variable AI_API_KEY is set;
 * otherwise JUnit reports every test here as SKIPPED (never as passed).
 *
 * <pre>
 *   PowerShell:  $env:AI_API_KEY = "&lt;your key&gt;" ; cd backend ; .\scripts\verify-real-ai.ps1
 * </pre>
 *
 * Cost: exactly TWO billable requests (the connectivity prompt, then one explanation of the sample loan); each is capped
 * at a small output limit. The two negative tests (invalid key, invalid model) are rejected by Anthropic before any
 * generation and are not billed. No customer data is used: the fixture is the fictitious Rahul sample, in an embedded
 * test database (never the development database). The key is never printed.
 */
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealAnthropicLiveIT extends AbstractPostgresIT {

    private static String key() {
        return System.getenv("AI_API_KEY");
    }

    private static String model() {
        String m = System.getenv("AI_MODEL");
        return m == null || m.isBlank() ? "claude-sonnet-5-5" : m.trim();
    }

    private static AnthropicAiProvider provider(String apiKey, String model, int maxTokens) {
        return new AnthropicAiProvider(new AiProperties("anthropic", model, apiKey, "", 60, maxTokens, 20, 8192));
    }

    @Test
    @Order(1)
    @DisplayName("1. status endpoint reports a configured provider, without any secret")
    void statusEndpoint() throws Exception {
        String body = body(getJson("/api/ai/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(true))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.mode").value("AI")));
        System.out.println("[LIVE] GET /api/ai/status -> " + body);
        assertThat(body).doesNotContain(key());
    }

    @Test
    @Order(2)
    @DisplayName("2. REAL request: minimal prompt, small output limit, usable text comes back (x-api-key auth)")
    void realConnectivity() {
        AnthropicAiProvider provider = provider(key(), model(), 1000);

        String text = provider.complete("You are a concise banking assistant.",
                "Explain why funding costs reduce a bank's loan contribution. Answer in two sentences.");

        System.out.println("[LIVE] model requested: " + model());
        System.out.println("[LIVE] real Anthropic reply (" + text.length() + " chars): " + text);
        assertThat(text).isNotBlank();
        assertThat(text.length()).isGreaterThan(40);
        assertThat(text).doesNotContain(key());
    }

    @Test
    @Order(3)
    @DisplayName("3. REAL /api/ai/explain for Rahul L1001/2026: source AI, backend figures unchanged, fallback not used")
    void realExplainForRahul() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        var result = postJson("/api/ai/explain",
                "{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"Why is this loan showing negative contribution?\"}")
                .andExpect(status().isOk());
        String body = body(result);
        String source = JsonPath.read(body, "$.source");
        Object reason = JsonPath.read(body, "$.fallbackReason");
        System.out.println("[LIVE] source=" + source + " fallbackReason=" + reason + " provider=" + JsonPath.read(body, "$.provider")
                + " model=" + JsonPath.read(body, "$.model"));
        System.out.println("[LIVE] summary: " + JsonPath.read(body, "$.summary"));

        // If the real model's answer is rejected by validation (or the call fails) the backend answers with the labelled
        // fallback; that is correct behaviour but it means the AI path was NOT verified, so this test fails loudly.
        assertThat(source).as("source (fallbackReason=" + reason + "): the answer was NOT genuinely AI-generated").isEqualTo("AI");
        assertThat(reason).isNull();
        result.andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.dataStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.facts[?(@.key=='interestIncome')].display").value(org.hamcrest.Matchers.hasItem("₹60,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='feeIncome')].display").value(org.hamcrest.Matchers.hasItem("₹2,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='fundingCost')].display").value(org.hamcrest.Matchers.hasItem("₹35,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='operationalCost')].display").value(org.hamcrest.Matchers.hasItem("₹7,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='contributionBeforeEcl')].display").value(org.hamcrest.Matchers.hasItem("₹20,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='expectedCreditLoss')].display").value(org.hamcrest.Matchers.hasItem("₹30,000")))
                .andExpect(jsonPath("$.facts[?(@.key=='contributionAfterEcl')].display").value(org.hamcrest.Matchers.hasItem("-₹10,000")));
        String summary = JsonPath.read(body, "$.summary");
        assertThat(summary).as("the AI explanation must mention the real negative result").containsAnyOf("₹10,000", "negative");
        assertThat(body).doesNotContain(key());
        assertThat(count("profitability_snapshots")).as("explaining writes nothing").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("4. an invalid API key is rejected by Anthropic (401, not billed) and the key is not leaked")
    void invalidKeyIsReportedSafely() {
        String bad = "sk-ant-api03-this-is-not-a-real-key-" + "x".repeat(40);
        assertThatThrownBy(() -> provider(bad, model(), 50).complete("s", "Say hi."))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    System.out.println("[LIVE] invalid key -> kind=" + e.kind() + " message=" + e.getMessage());
                    assertThat(e.kind()).isEqualTo(AiProviderException.Kind.AUTHENTICATION);
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(bad);
                    }
                });
    }

    @Test
    @Order(5)
    @DisplayName("5. an invalid model id is rejected by Anthropic (not billed) and reported as an error")
    void invalidModelIsReported() {
        assertThatThrownBy(() -> provider(key(), "claude-this-model-does-not-exist", 50).complete("s", "Say hi."))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    System.out.println("[LIVE] invalid model -> kind=" + e.kind() + " message=" + e.getMessage());
                    assertThat(e.kind()).isIn(AiProviderException.Kind.ERROR, AiProviderException.Kind.AUTHENTICATION);
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(key());
                    }
                });
    }
}
