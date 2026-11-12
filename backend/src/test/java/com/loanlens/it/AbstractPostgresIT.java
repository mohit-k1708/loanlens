package com.loanlens.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Full application context + REAL PostgreSQL (Flyway migrates it, Hibernate validates the mapping against it).
 * Every test starts from empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractPostgresIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresTestSupport.jdbcUrl("postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE profitability_snapshots, operational_cost_allocations, risk_assessments, "
                + "financial_records, loans, customers RESTART IDENTITY CASCADE");
    }

    /* ---------- HTTP helpers ---------- */

    protected ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putJson(String url, String json) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    protected static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(body(result), jsonPath);
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    /* ---------- Fixture builders ---------- */

    protected long createCustomer(String code, String name, String email) throws Exception {
        MvcResult result = postJson("/api/customers", """
                {"customerCode":"%s","fullName":"%s","email":"%s","phone":"+91 9000000001",
                 "employmentType":"SALARIED","annualIncome":900000.00}""".formatted(code, name, email))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    protected void createLoan(String loanCode, long customerId, String type, String principal, String outstanding,
                              String rate, int tenure, String startDate) throws Exception {
        postJson("/api/loans", """
                {"loanCode":"%s","customerId":%d,"loanType":"%s","originalPrincipal":%s,"outstandingBalance":%s,
                 "interestRate":%s,"tenureMonths":%d,"startDate":"%s"}"""
                .formatted(loanCode, customerId, type, principal, outstanding, rate, tenure, startDate))
                .andExpect(status().isCreated());
    }

    /** Rahul Sharma, loan L1001: Rs 5,00,000 personal loan at 10%, started 2025-04-01. */
    protected long createRahulAndLoan() throws Exception {
        long customerId = createCustomer("C2001", "Rahul Sharma", "rahul.sharma@example.com");
        createLoan("L1001", customerId, "PERSONAL", "500000.00", "500000.00", "0.10", 60, "2025-04-01");
        return customerId;
    }

    protected ResultActions financialRecord(String loanCode, String period, String interest, String fee, String avg,
                                            String fundingRate, String fundingCost, String operationalCost) throws Exception {
        return postJson("/api/loans/" + loanCode + "/financial-records", """
                {"reportingPeriod":"%s","interestIncome":%s,"feeIncome":%s,"averageOutstandingBalance":%s,
                 "fundingRate":%s,"fundingCost":%s,"operationalCost":%s}"""
                .formatted(period, interest, fee, avg, fundingRate, fundingCost, operationalCost));
    }

    protected ResultActions riskAssessment(String loanCode, String period, String pd, String lgd, String ead) throws Exception {
        return postJson("/api/loans/" + loanCode + "/risk-assessments", """
                {"reportingPeriod":"%s","probabilityOfDefault":%s,"lossGivenDefault":%s,"exposureAtDefault":%s}"""
                .formatted(period, pd, lgd, ead));
    }

    protected ResultActions allocation(String loanCode, String period, String category, String amount) throws Exception {
        return postJson("/api/loans/" + loanCode + "/operational-cost-allocations", """
                {"reportingPeriod":"%s","costCategory":"%s","allocationMethod":"ACTIVITY_BASED_COSTING",
                 "allocationDriver":"test driver","allocatedAmount":%s,"sourceDescription":"test"}"""
                .formatted(period, category, amount));
    }

    /** The brief's sample inputs for Rahul / 2026. */
    protected void addRahulsInputs2026() throws Exception {
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00")
                .andExpect(status().isCreated());
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_PROCESSING", "500.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "CUSTOMER_SUPPORT", "150.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_SERVICING", "6350.00").andExpect(status().isCreated());
    }
}
