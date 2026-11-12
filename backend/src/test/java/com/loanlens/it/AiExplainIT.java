package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * POST /api/ai/explain through the whole application on a real PostgreSQL, with NO AI provider configured: the
 * endpoint must stay fully usable and answer with the clearly labelled deterministic explanation.
 */
class AiExplainIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void noProvider(DynamicPropertyRegistry registry) {
        registry.add("loanlens.ai.provider", () -> "anthropic");
        registry.add("loanlens.ai.api-key", () -> "");          // overrides any AI_API_KEY in the developer's environment
        registry.add("loanlens.ai.rate-limit-per-minute", () -> "1000");
    }

    private static String ask(String loan, String period, String question) {
        return "{\"loanId\":\"%s\",\"reportingPeriod\":%s,\"question\":\"%s\"}".formatted(loan, period, question);
    }

    @Test
    @DisplayName("Rahul / L1001 / 2026 with no AI key: labelled FALLBACK, explanation uses exactly the backend's numbers")
    void rahulWithoutAnAiKey() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        postJson("/api/ai/explain", ask("L1001", "2026", "Why is this loan showing a negative contribution?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value("L1001"))
                .andExpect(jsonPath("$.reportingPeriod").value("2026"))
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.provider").doesNotExist())       // not presented as an AI model
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.dataStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true))
                .andExpect(jsonPath("$.summary").value(containsString("₹62,000")))
                .andExpect(jsonPath("$.summary").value(containsString("₹42,000")))
                .andExpect(jsonPath("$.summary").value(containsString("₹20,000")))
                .andExpect(jsonPath("$.summary").value(containsString("₹30,000")))
                .andExpect(jsonPath("$.summary").value(containsString("-₹10,000")))
                .andExpect(jsonPath("$.summary").value(containsString("not a loss that has already occurred")))
                // backend-calculated facts, with the exact amounts
                .andExpect(jsonPath("$.facts[?(@.key=='totalIncome')].value").value(hasItem(62000.00)))
                .andExpect(jsonPath("$.facts[?(@.key=='fundingCost')].value").value(hasItem(35000.00)))
                .andExpect(jsonPath("$.facts[?(@.key=='contributionBeforeEcl')].value").value(hasItem(20000.00)))
                .andExpect(jsonPath("$.facts[?(@.key=='expectedCreditLoss')].value").value(hasItem(30000.00)))
                .andExpect(jsonPath("$.facts[?(@.key=='contributionAfterEcl')].value").value(hasItem(-10000.00)))
                .andExpect(jsonPath("$.facts[?(@.key=='probabilityOfDefault')].display").value(hasItem("10%")))
                .andExpect(jsonPath("$.reasons[?(@.factorKey=='fundingCost')].impact").value(hasItem("NEGATIVE")))
                .andExpect(jsonPath("$.reasons[?(@.factorKey=='totalIncome')].impact").value(hasItem("POSITIVE")))
                .andExpect(jsonPath("$.reasons[?(@.factorKey=='expectedCreditLoss')].amount").value(hasItem(30000.00)))
                .andExpect(jsonPath("$.recommendations[0]").value(containsString("human reviewer")))
                .andExpect(jsonPath("$.limitations[0]").value(containsString("No AI model was used")));
    }

    @Test
    @DisplayName("the explanation agrees with the profitability endpoint (one authoritative calculation)")
    void agreesWithTheProfitabilityEndpoint() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        Number detail = read(getJson("/api/loans/L1001/profitability?period=2026"), "$.contributionAfterEcl");
        var explain = postJson("/api/ai/explain", ask("L1001", "2026", "Explain in simple language"));
        net.minidev.json.JSONArray facts = read(explain, "$.facts[?(@.key=='contributionAfterEcl')].value");
        assertThat(facts).hasSize(1);
        assertThat(((Number) facts.get(0)).doubleValue()).isEqualTo(detail.doubleValue()).isEqualTo(-10000.00);
    }

    @Test
    @DisplayName("explaining never changes data: no snapshots, records or assessments are written")
    void isReadOnly() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        long records = count("financial_records");
        long risks = count("risk_assessments");
        long allocations = count("operational_cost_allocations");

        postJson("/api/ai/explain", ask("L1001", "2026", "Why is it negative?")).andExpect(status().isOk());

        assertThat(count("profitability_snapshots")).isZero();
        assertThat(count("financial_records")).isEqualTo(records);
        assertThat(count("risk_assessments")).isEqualTo(risks);
        assertThat(count("operational_cost_allocations")).isEqualTo(allocations);
    }

    @ParameterizedTest(name = "suggested question: {0}")
    @ValueSource(strings = {
            "Why is this loan showing a negative contribution?",
            "What is the largest cost for this loan?",
            "Why did ECL increase?",
            "What if funding costs increased?",
            "Which factors are reducing contribution?",
            "Why is this loan more profitable than another loan?",
            "Explain this in simple language."
    })
    void everySuggestedQuestionIsAnswered(String question) throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        postJson("/api/ai/explain", ask("L1001", "2026", question))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.limitations").isNotEmpty());
    }

    @Test
    @DisplayName("ECL change uses the previous period only when it is complete; otherwise it says the information is insufficient")
    void eclChange() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        postJson("/api/ai/explain", ask("L1001", "2026", "Why did ECL increase?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(containsString("not enough information")))
                .andExpect(jsonPath("$.summary").value(not(containsString("higher"))));

        financialRecord("L1001", "2025", "50000.00", "1000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2025", "0.05", "0.60", "500000.00").andExpect(status().isCreated());
        postJson("/api/ai/explain", ask("L1001", "2026", "Why did ECL increase?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(containsString("₹30,000 in 2026")))
                .andExpect(jsonPath("$.summary").value(containsString("₹15,000 in 2025")))
                .andExpect(jsonPath("$.summary").value(containsString("PD moved from 5% to 10%")))
                .andExpect(jsonPath("$.facts[?(@.key=='previous.expectedCreditLoss')].value").value(hasItem(15000.00)));
    }

    @Test
    @DisplayName("comparison question with a second loan uses both loans' backend figures; the other customer's name is not in the answer")
    void compareTwoLoans() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        long b = createCustomer("C2002", "Priya Nair", "priya@example.com");
        createLoan("L1002", b, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");
        financialRecord("L1002", "2026", "372400.00", "9000.00", "4250000.00", "0.07", "297500.00", "11150.00").andExpect(status().isCreated());
        riskAssessment("L1002", "2026", "0.012", "0.25", "4120000.00").andExpect(status().isCreated());

        postJson("/api/ai/explain", "{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"Why is the other loan more profitable?\",\"compareToLoanId\":\"L1002\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(containsString("L1002 has ₹60,390")))
                .andExpect(jsonPath("$.summary").value(containsString("L1002 has the higher estimated contribution after ECL")))
                .andExpect(jsonPath("$.summary").value(not(containsString("Priya"))))
                .andExpect(jsonPath("$.facts[?(@.key=='other.contributionAfterEcl')].value").value(hasItem(60390.00)));
    }

    @Test
    @DisplayName("missing inputs: HTTP 200 'insufficient information', no figures, nothing assumed")
    void missingInputs() throws Exception {
        createRahulAndLoan();   // no records at all

        postJson("/api/ai/explain", ask("L1001", "2026", "Why is it negative?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.summary").value(containsString("not enough information")))
                .andExpect(jsonPath("$.missingInputs.length()").value(2))
                .andExpect(jsonPath("$.facts.length()").value(0))
                .andExpect(jsonPath("$.reasons.length()").value(0));

        // a period for which the loan has nothing is just as insufficient
        addRahulsInputs2026();
        postJson("/api/ai/explain", ask("L1001", "2028", "Why is it negative?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("INSUFFICIENT"));
    }

    @Test
    @DisplayName("errors: unknown loan 404; inconsistent cost breakdown 422; bad input 400; no stack traces")
    void errors() throws Exception {
        postJson("/api/ai/explain", ask("L9999", "2026", "Why is it negative?")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("L9999")));

        createRahulAndLoan();
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_PROCESSING", "500.00").andExpect(status().isCreated());   // breakdown 500 != 7,000
        postJson("/api/ai/explain", ask("L1001", "2026", "Why is it negative?")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("allocations")));

        postJson("/api/ai/explain", ask("L1001", "26", "Why is it negative?")).andExpect(status().isBadRequest());
        postJson("/api/ai/explain", ask("L1001", "2026", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.question").exists());
        postJson("/api/ai/explain", "{}").andExpect(status().isBadRequest());
        getJson("/api/ai/explain").andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("an oversized request body is refused with 413 by the real filter chain")
    void requestSizeLimit() throws Exception {
        String huge = "{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"" + "x".repeat(9000) + "\"}";
        postJson("/api/ai/explain", huge).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(containsString("too large")));
    }

    @Test
    @DisplayName("GET /api/ai/status with no key: fallback-only mode, no secret in the response")
    void statusWithoutKey() throws Exception {
        String body = body(getJson("/api/ai/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(false))
                .andExpect(jsonPath("$.mode").value("FALLBACK_ONLY")));
        assertThat(body).doesNotContainIgnoringCase("apikey").doesNotContain("sk-");
    }
}
