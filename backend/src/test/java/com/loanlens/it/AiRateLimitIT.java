package com.loanlens.it;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The per-client request limit on /api/ai/explain, through the real application context. */
class AiRateLimitIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void limit(DynamicPropertyRegistry registry) {
        registry.add("loanlens.ai.api-key", () -> "");
        registry.add("loanlens.ai.rate-limit-per-minute", () -> "2");
    }

    @Test
    @DisplayName("the third request in a minute is refused with 429 and Retry-After; other endpoints are unaffected")
    void limitsExplainRequests() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        String body = "{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"Why is it negative?\"}";

        postJson("/api/ai/explain", body).andExpect(status().isOk());
        postJson("/api/ai/explain", body).andExpect(status().isOk());
        postJson("/api/ai/explain", body).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(containsString("Too many AI requests")));

        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk());
        getJson("/api/ai/status").andExpect(status().isOk());
    }
}
