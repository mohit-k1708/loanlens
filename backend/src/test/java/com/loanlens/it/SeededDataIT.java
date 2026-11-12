package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.loanlens.config.SampleDataSeeder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the frontend's 13 demo loans and checks the BACKEND reproduces the FRONTEND's results to the paisa,
 * so the two halves of the application agree. Uses its own database so it cannot interfere with other tests.
 */
@SpringBootTest(properties = "loanlens.seed.enabled=true")
@AutoConfigureMockMvc
class SeededDataIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresTestSupport.jdbcUrl("seeded"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataSeeder seeder;

    /** Estimated contribution after ECL per loan, as displayed by the frontend (12-month period). */
    private static final Map<String, String> FRONTEND_CONTRIBUTION = new LinkedHashMap<>();

    static {
        FRONTEND_CONTRIBUTION.put("L1001", "-10000.00");
        FRONTEND_CONTRIBUTION.put("L1002", "60390.00");
        FRONTEND_CONTRIBUTION.put("L1003", "5292.50");
        FRONTEND_CONTRIBUTION.put("L1004", "75725.00");
        FRONTEND_CONTRIBUTION.put("L1005", "-17000.00");
        FRONTEND_CONTRIBUTION.put("L1006", "8900.00");
        FRONTEND_CONTRIBUTION.put("L1007", "91.00");
        FRONTEND_CONTRIBUTION.put("L1008", "102450.00");
        FRONTEND_CONTRIBUTION.put("L1009", "11260.00");
        FRONTEND_CONTRIBUTION.put("L1010", "-22970.00");
        FRONTEND_CONTRIBUTION.put("L1011", "-1830.00");
        FRONTEND_CONTRIBUTION.put("L1012", "36615.00");
        FRONTEND_CONTRIBUTION.put("L1013", "5504.00");
    }

    @Test
    @DisplayName("every seeded loan matches the contribution shown by the frontend")
    void backendMatchesFrontend() throws Exception {
        BigDecimal portfolio = BigDecimal.ZERO;
        for (var entry : FRONTEND_CONTRIBUTION.entrySet()) {
            String json = mvc.perform(get("/api/loans/" + entry.getKey() + "/profitability?period=2026"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            BigDecimal actual = new BigDecimal(JsonPath.read(json, "$.contributionAfterEcl").toString());
            assertThat(actual).as("contribution after ECL of " + entry.getKey()).isEqualByComparingTo(entry.getValue());
            portfolio = portfolio.add(actual);
        }
        // The frontend dashboard's "Total Estimated Contribution" for 12 months: Rs 2,54,427.50
        assertThat(portfolio).isEqualByComparingTo("254427.50");
    }

    @Test
    void rahulIsExactlyAsSpecified() throws Exception {
        mvc.perform(get("/api/loans/L1001/profitability?period=2026"))
                .andExpect(jsonPath("$.interestIncome").value(60000.00))
                .andExpect(jsonPath("$.feeIncome").value(2000.00))
                .andExpect(jsonPath("$.totalIncome").value(62000.00))
                .andExpect(jsonPath("$.fundingCost").value(35000.00))
                .andExpect(jsonPath("$.operationalCost").value(7000.00))
                .andExpect(jsonPath("$.totalCostsBeforeEcl").value(42000.00))
                .andExpect(jsonPath("$.contributionBeforeEcl").value(20000.00))
                .andExpect(jsonPath("$.expectedCreditLoss").value(30000.00))
                .andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00))
                .andExpect(jsonPath("$.operationalCostAllocations", hasSize(3)))
                .andExpect(jsonPath("$.operationalCostAllocations[0].allocatedAmount").value(500.00))   // LOAN_PROCESSING
                .andExpect(jsonPath("$.operationalCostAllocations[1].allocatedAmount").value(150.00))   // CUSTOMER_SUPPORT
                .andExpect(jsonPath("$.operationalCostAllocations[2].allocatedAmount").value(6350.00)); // LOAN_SERVICING
    }

    @Test
    void seededListSupportsPaginationAndFilters() throws Exception {
        mvc.perform(get("/api/loans?size=5")).andExpect(jsonPath("$.totalElements").value(13)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/loans?loanType=HOME")).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/loans?loanType=PERSONAL")).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/loans?loanStatus=UNDER_WATCH")).andExpect(jsonPath("$.totalElements").value(2));
        // High risk = PD >= 8%: Rahul (10), Vikram (9), Rohan (12), Neha (14)
        mvc.perform(get("/api/loans?riskRating=HIGH")).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/loans?riskRating=MEDIUM")).andExpect(jsonPath("$.totalElements").value(4)); // Amit, Sneha, Farhan, Aman (PD exactly 3% is the MEDIUM boundary)
        mvc.perform(get("/api/loans?riskRating=LOW")).andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/loans?customerName=sharma")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/loans?loanType=PERSONAL&riskRating=HIGH&loanStatus=UNDER_WATCH")).andExpect(jsonPath("$.totalElements").value(1)); // Neha
    }


    @Test
    @DisplayName("dashboard: the frontend's portfolio numbers, computed by the backend")
    void dashboardMatchesFrontendPortfolio() throws Exception {
        mvc.perform(get("/api/dashboard/summary?period=2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveLoans").value(13))
                .andExpect(jsonPath("$.totalOutstandingBalance").value(28700000.00))
                .andExpect(jsonPath("$.loansWithData").value(13))
                .andExpect(jsonPath("$.loansWithoutData").value(0))
                .andExpect(jsonPath("$.contributionBeforeEcl").value(871305.00))
                .andExpect(jsonPath("$.expectedCreditLoss").value(616877.50))
                .andExpect(jsonPath("$.contributionAfterEcl").value(254427.50))
                // The backend counts break-even as EXACTLY 0.00, so Rohan's +91.00 is positive here
                // (the mock frontend treats anything within 500 rupees of zero as break-even).
                .andExpect(jsonPath("$.positiveContributionLoans").value(9))
                .andExpect(jsonPath("$.negativeContributionLoans").value(4))
                .andExpect(jsonPath("$.breakEvenLoans").value(0))
                .andExpect(jsonPath("$.underWatchLoans").value(2))
                .andExpect(jsonPath("$.highRiskLoans").value(4))
                .andExpect(jsonPath("$.contributionTrend.length()").value(1))
                .andExpect(jsonPath("$.attentionLoans.length()").value(5))
                .andExpect(jsonPath("$.attentionLoans[0].loanCode").value("L1010"))
                .andExpect(jsonPath("$.attentionLoans[0].contributionAfterEcl").value(-22970.00));
    }

    @Test
    @DisplayName("list rows carry the same contribution as each loan's profitability endpoint")
    void listRowsMatchTheDetailEndpoint() throws Exception {
        String list = mvc.perform(get("/api/loans?period=2026&size=100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var entry : FRONTEND_CONTRIBUTION.entrySet()) {
            java.util.List<Object> values = JsonPath.read(list, "$.content[?(@.loanCode=='" + entry.getKey() + "')].profitability.contributionAfterEcl");
            assertThat(values).as(entry.getKey()).hasSize(1);
            assertThat(new BigDecimal(values.get(0).toString())).as(entry.getKey()).isEqualByComparingTo(entry.getValue());
        }
        mvc.perform(get("/api/loans?period=2026&contributionStatus=NEGATIVE&size=2"))
                .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/loans?search=C2005")).andExpect(jsonPath("$.content[0].loanCode").value("L1005"));
        mvc.perform(get("/api/reporting-periods")).andExpect(jsonPath("$[0]").value("2026")).andExpect(jsonPath("$.length()").value(1));
    }
    @Test
    void seedingIsIdempotent() throws Exception {
        long customers = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);
        long loans = jdbc.queryForObject("SELECT count(*) FROM loans", Long.class);
        seeder.run(null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isEqualTo(customers).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loans", Long.class)).isEqualTo(loans).isEqualTo(13);
    }

    @Test
    void healthEndpointIsUpAndRevealsNothingElse() throws Exception {
        String health = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andReturn().getResponse().getContentAsString();
        assertThat(health).doesNotContain("jdbc:").doesNotContain("postgres").doesNotContain("password");
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
