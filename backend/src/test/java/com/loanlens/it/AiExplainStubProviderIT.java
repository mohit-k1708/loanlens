package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.StubAnthropicServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The full path with the real Anthropic provider CODE (official SDK, HTTP, response parsing, validation) pointed at a
 * LOCAL STUB of the Anthropic API. This proves the application's behaviour end to end. It does NOT prove that a real
 * Claude model answers: no real API key was available.
 */
class AiExplainStubProviderIT extends AbstractPostgresIT {

    private static final String API_KEY = "sk-ant-stub-it-KEY-98765";
    private static final StubAnthropicServer STUB = StubAnthropicServer.start();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("loanlens.ai.provider", () -> "anthropic");
        registry.add("loanlens.ai.api-key", () -> API_KEY);
        registry.add("loanlens.ai.base-url", STUB::baseUrl);
        registry.add("loanlens.ai.model", () -> "claude-sonnet-5-5");
        registry.add("loanlens.ai.timeout-seconds", () -> "3");   // generous: the first call also loads the SDK classes
        registry.add("loanlens.ai.rate-limit-per-minute", () -> "1000");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private static final String GOOD_ANSWER = """
            {"summary":"Total income of ₹62,000 is reduced by funding cost (₹35,000) and operational cost (₹7,000), which together are ₹42,000, leaving ₹20,000 before ECL. The ECL estimate of ₹30,000 (PD 10%, LGD 60%, EAD ₹5,00,000) is then deducted, giving -₹10,000. The ECL is an estimate, not a realised loss.",
             "reasons":[
               {"factorKey":"fundingCost","impact":"NEGATIVE","explanation":"Funding cost of ₹35,000 is the largest deduction."},
               {"factorKey":"expectedCreditLoss","impact":"NEGATIVE","explanation":"The ECL estimate of ₹30,000 turns a positive pre-ECL result negative."}],
             "recommendations":["A reviewer could check whether the PD of 10% and LGD of 60% are appropriate."],
             "limitations":["Simplified educational model; ECL is an estimate."]}""";

    @BeforeEach
    void resetStub() {
        STUB.reset();
        STUB.respondWithText(GOOD_ANSWER);
    }

    private static String ask(String question) {
        return "{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"" + question + "\"}";
    }

    @Test
    @DisplayName("real provider code + stub API: source AI, model named, amounts supplied by the backend")
    void aiAnswer() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        postJson("/api/ai/explain", ask("Why is this loan showing a negative contribution?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.fallbackReason").doesNotExist())
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.summary").value(containsString("The ECL estimate of ₹30,000")))
                .andExpect(jsonPath("$.reasons[?(@.factorKey=='fundingCost')].amount").value(hasItem(35000.00)))
                .andExpect(jsonPath("$.reasons[?(@.factorKey=='fundingCost')].factor").value(hasItem("Funding cost")))
                .andExpect(jsonPath("$.facts[?(@.key=='contributionAfterEcl')].value").value(hasItem(-10000.00)))
                .andExpect(jsonPath("$.limitations[0]").value(not(containsString("No AI model was used"))));
        assertThat(STUB.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("what the provider is sent: backend facts only; no customer name, code, e-mail or phone, even if typed in the question")
    void promptContainsNoCustomerIdentity() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        postJson("/api/ai/explain", ask("Why is Rahul Sharma (C2001, rahul.sharma@example.com, +91 9000000001) negative?"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("AI"));

        String sent = STUB.lastRequestBody();
        JsonNode body = new ObjectMapper().readTree(sent);
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(body.has("temperature")).isFalse();
        assertThat(sent).doesNotContain("Rahul").doesNotContain("Sharma").doesNotContain("C2001")
                .doesNotContain("rahul.sharma").doesNotContain("example.com").doesNotContain("9000000001");
        assertThat(sent).doesNotContain(API_KEY);
        assertThat(sent).contains("the customer").contains("₹35,000").contains("whatIfFundingPlus10.fundingCost").contains("L1001");
        assertThat(STUB.lastRequestHeader("x-api-key")).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("a model answer with an invented number is rejected and replaced by the labelled fallback")
    void inventedNumberFallsBack() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        STUB.respondWithText("{\"summary\":\"The bank has lost ₹99,999 on this loan.\",\"reasons\":[],\"limitations\":[\"x\"]}");

        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("INVALID_AI_RESPONSE"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.summary").value(not(containsString("99,999"))))
                .andExpect(jsonPath("$.summary").value(containsString("-₹10,000")));
    }

    @Test
    @DisplayName("a model answer that claims default or recommends a decision is replaced by the fallback")
    void unsafeAnswerFallsBack() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        STUB.respondWithText("{\"summary\":\"This customer will default, so reject the loan.\",\"reasons\":[],\"limitations\":[\"x\"]}");

        postJson("/api/ai/explain", ask("Should we keep this loan?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("INVALID_AI_RESPONSE"))
                .andExpect(jsonPath("$.summary").value(not(containsString("will default"))))
                .andExpect(jsonPath("$.summary").value(not(containsString("reject"))));
    }

    @Test
    @DisplayName("provider failures never break the endpoint: 401, 429, 500 and a timeout (3 s) all fall back with a reason, and the key never leaks")
    void providerFailures() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        STUB.respondWithError(401, "authentication_error", "invalid x-api-key");
        String unauthorised = body(postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK")).andExpect(jsonPath("$.fallbackReason").value("PROVIDER_AUTH")));
        assertThat(unauthorised).doesNotContain(API_KEY);

        STUB.respondWithError(429, "rate_limit_error", "slow down");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("RATE_LIMITED"));

        STUB.respondWithError(500, "api_error", "boom");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("PROVIDER_ERROR"));

        STUB.respondWithText(GOOD_ANSWER);
        STUB.setDelayMillis(6000);
        long start = System.nanoTime();
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("TIMEOUT"))
                .andExpect(jsonPath("$.summary").value(containsString("-₹10,000")));
        assertThat((System.nanoTime() - start) / 1_000_000).as("answered promptly after the 3 s timeout").isLessThan(5500);
    }

    @Test
    @DisplayName("billing/credit (402), bad credentials (401), invalid model (404) and a malformed 200 each give a specific labelled fallback, never a 500")
    void specificProviderProblems() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        STUB.respondWithError(402, "billing_error", "Your credit balance is too low");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK")).andExpect(jsonPath("$.fallbackReason").value("PROVIDER_BILLING"));

        STUB.respondWithError(401, "authentication_error", "invalid x-api-key");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("PROVIDER_AUTH"));

        STUB.respondWithError(404, "not_found_error", "model: nope");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("PROVIDER_ERROR"));

        STUB.respondWithRaw(200, "<html>gateway splash page</html>");
        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK")).andExpect(jsonPath("$.fallbackReason").value("PROVIDER_ERROR"))
                .andExpect(jsonPath("$.summary").value(containsString("-₹10,000")));
    }

    @Test
    @DisplayName("missing inputs: the provider is not called at all")
    void providerNotCalledWhenDataIsMissing() throws Exception {
        createRahulAndLoan();   // no records

        postJson("/api/ai/explain", ask("Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.fallbackReason").value("INSUFFICIENT_DATA"));
        assertThat(STUB.count()).isZero();
    }

    @Test
    @DisplayName("GET /api/ai/status reports the configured provider and model, and never the key or URL")
    void statusWithKey() throws Exception {
        String body = body(getJson("/api/ai/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(true))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.mode").value("AI")));
        assertThat(body).doesNotContain(API_KEY).doesNotContain("127.0.0.1").doesNotContain("http");
    }
}
