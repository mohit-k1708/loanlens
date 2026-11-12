package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The public-demo protection: with loanlens.read-only=true nothing can change data, but reading and explaining still work. */
class ReadOnlyModeIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void readOnly(DynamicPropertyRegistry registry) {
        registry.add("loanlens.read-only", () -> "true");
        registry.add("loanlens.ai.api-key", () -> "");
        registry.add("loanlens.ai.rate-limit-per-minute", () -> "1000");
    }

    @Test
    @DisplayName("every data-changing request is refused with 403 and nothing is written")
    void writesAreRefused() throws Exception {
        postJson("/api/customers", """
                {"customerCode":"C9001","fullName":"Nobody","email":"n@example.com","phone":"+91 9000000009",
                 "employmentType":"SALARIED","annualIncome":1000.00}""")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("read-only demo")));
        putJson("/api/loans/L1001", "{}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/customers/1")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/loans/L1001")).andExpect(status().isForbidden());
        postJson("/api/loans/L1001/financial-records", "{}").andExpect(status().isForbidden());

        assertThat(count("customers")).isZero();
    }

    @Test
    @DisplayName("reading still works, and so does explaining (which writes nothing)")
    void readsAndExplanationsStillWork() throws Exception {
        // fixture inserted directly: the API itself is read-only in this mode
        jdbc.update("INSERT INTO customers (customer_code, full_name, email, employment_type, annual_income, created_at, updated_at) "
                + "VALUES ('C2001','Rahul Sharma','rahul@example.com','SALARIED',900000, now(), now())");
        getJson("/api/loans").andExpect(status().isOk());
        getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk());
        getJson("/api/ai/status").andExpect(status().isOk());
        getJson("/actuator/health").andExpect(status().isOk());
        postJson("/api/ai/explain", "{\"loanId\":\"L9999\",\"reportingPeriod\":2026,\"question\":\"Why is it negative?\"}")
                .andExpect(status().isNotFound());   // reached the service (not blocked by the filter)
    }
}
