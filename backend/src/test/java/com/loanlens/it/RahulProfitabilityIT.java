package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The brief's worked example, end to end over HTTP against a real PostgreSQL. */
class RahulProfitabilityIT extends AbstractPostgresIT {

    @Test
    @DisplayName("Rahul / 2026: income 62,000; costs 42,000; before ECL 20,000; ECL 30,000; after ECL -10,000")
    void rahulEndToEnd() throws Exception {
        createRahulAndLoan();

        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalIncome").value(62000.00));

        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00")
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"expectedCreditLoss\":30000.00")))
                .andExpect(jsonPath("$.riskRating").value("HIGH"))
                .andExpect(jsonPath("$.calculationNote").value(containsString("not a full IFRS 9")));

        allocation("L1001", "2026", "LOAN_PROCESSING", "500.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "CUSTOMER_SUPPORT", "150.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_SERVICING", "6350.00").andExpect(status().isCreated());

        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(status().isOk())
                // exact two-decimal JSON numbers, never 6.2E4
                .andExpect(content().string(containsString("\"interestIncome\":60000.00")))
                .andExpect(content().string(containsString("\"feeIncome\":2000.00")))
                .andExpect(content().string(containsString("\"totalIncome\":62000.00")))
                .andExpect(content().string(containsString("\"fundingCost\":35000.00")))
                .andExpect(content().string(containsString("\"operationalCost\":7000.00")))
                .andExpect(content().string(containsString("\"totalCostsBeforeEcl\":42000.00")))
                .andExpect(content().string(containsString("\"totalExpenses\":72000.00")))
                .andExpect(content().string(containsString("\"contributionBeforeEcl\":20000.00")))
                .andExpect(content().string(containsString("\"expectedCreditLoss\":30000.00")))
                .andExpect(content().string(containsString("\"contributionAfterEcl\":-10000.00")))
                .andExpect(jsonPath("$.contributionStatus").value("NEGATIVE"))
                .andExpect(jsonPath("$.reportingPeriod").value("2026"))
                .andExpect(jsonPath("$.loan.loanCode").value("L1001"))
                .andExpect(jsonPath("$.loan.customerName").value("Rahul Sharma"))
                .andExpect(jsonPath("$.loan.outstandingBalance").value(500000.00))
                .andExpect(jsonPath("$.risk.riskRating").value("HIGH"))
                .andExpect(jsonPath("$.risk.probabilityOfDefault").value(0.1))
                .andExpect(jsonPath("$.risk.lossGivenDefault").value(0.6))
                .andExpect(jsonPath("$.operationalCostAllocations", hasSize(3)))
                .andExpect(jsonPath("$.snapshot.newlyCreated").value(true))
                .andExpect(jsonPath("$.snapshot.calculationVersion").value("SIMPLIFIED-1.0"))
                .andExpect(jsonPath("$.snapshot.calculatedAt").exists())
                .andExpect(jsonPath("$.notes[?(@ =~ /.*does not mean the borrower has defaulted.*/)]").exists());

        assertThat(count("profitability_snapshots")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same loan can be addressed by loan code or by numeric id")
    void loanCodeOrNumericId() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        Number id = read(getJson("/api/loans/L1001"), "$.id");

        getJson("/api/loans/" + id + "/profitability?period=2026").andExpect(status().isOk())
                .andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00));
        getJson("/api/loans/l1001/profitability?period=2026").andExpect(status().isOk());
    }

    @Test
    @DisplayName("repeated requests never create duplicate snapshots")
    void noDuplicateSnapshots() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        Number first = read(getJson("/api/loans/L1001/profitability?period=2026"), "$.snapshot.id");
        Boolean firstIsNew = read(getJson("/api/loans/L1001/profitability?period=2026"), "$.snapshot.newlyCreated");
        Number third = read(getJson("/api/loans/L1001/profitability?period=2026"), "$.snapshot.id");

        assertThat(third).isEqualTo(first);
        assertThat(firstIsNew).as("the 2nd request reuses the stored snapshot").isFalse();
        assertThat(count("profitability_snapshots")).isEqualTo(1);
        getJson("/api/loans/L1001/profitability/history?period=2026").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("concurrent identical requests produce exactly one snapshot")
    void concurrentRequests() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Number>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> read(getJson("/api/loans/L1001/profitability?period=2026"), "$.snapshot.id"));
            }
            Set<Long> ids = new HashSet<>();
            for (Future<Number> f : pool.invokeAll(tasks)) {
                ids.add(f.get(60, TimeUnit.SECONDS).longValue());
            }
            assertThat(ids).as("every caller sees the same snapshot").hasSize(1);
            assertThat(count("profitability_snapshots")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("changing an input adds a new snapshot and keeps the old one as history")
    void changedInputsCreateNewSnapshot() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00));

        putJson("/api/loans/L1001/financial-records/2026", """
                {"reportingPeriod":"2026","interestIncome":61000.00,"feeIncome":2000.00,"averageOutstandingBalance":500000.00,
                 "fundingRate":0.07,"fundingCost":35000.00,"operationalCost":7000.00}""")
                .andExpect(status().isOk());

        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(jsonPath("$.contributionAfterEcl").value(-9000.00))
                .andExpect(jsonPath("$.snapshot.newlyCreated").value(true));

        assertThat(count("profitability_snapshots")).isEqualTo(2);
        getJson("/api/loans/L1001/profitability/history?period=2026")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].contributionAfterEcl").value(-9000.00))
                .andExpect(jsonPath("$[1].contributionAfterEcl").value(-10000.00));

        // Going back to the original inputs reuses the original snapshot rather than adding a third.
        putJson("/api/loans/L1001/financial-records/2026", """
                {"reportingPeriod":"2026","interestIncome":60000.00,"feeIncome":2000.00,"averageOutstandingBalance":500000.00,
                 "fundingRate":0.07,"fundingCost":35000.00,"operationalCost":7000.00}""").andExpect(status().isOk());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(false));
        assertThat(count("profitability_snapshots")).isEqualTo(2);
    }

    @Test
    @DisplayName("periods are never mixed: 2025 inputs are not used for a 2026 calculation")
    void periodsAreNeverMixed() throws Exception {
        createRahulAndLoan();
        // Only 2025 inputs exist, with very different numbers.
        financialRecord("L1001", "2025", "999999.00", "0.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2025", "0.50", "0.90", "500000.00").andExpect(status().isCreated());

        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs", hasSize(2)));

        // 2026 financial record exists but the risk assessment only exists for 2025 -> still not calculable.
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs", hasSize(1)))
                .andExpect(jsonPath("$.missingInputs[0]").value(containsString("Risk assessment")));
        assertThat(count("profitability_snapshots")).isZero();

        // 2025 is calculable on its own, from its own inputs only: ECL = 0.50 x 0.90 x 500000 = 225000.
        getJson("/api/loans/L1001/profitability?period=2025")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCreditLoss").value(225000.00))
                .andExpect(jsonPath("$.totalIncome").value(999999.00));
    }

    @Test
    @DisplayName("missing inputs are reported with a 422, never replaced by zero, and nothing is stored")
    void missingInputsAreNeverZero() throws Exception {
        createRahulAndLoan();

        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(containsString("required inputs are missing")))
                .andExpect(jsonPath("$.missingInputs", hasSize(2)))
                .andExpect(jsonPath("$.missingInputs[0]").value(containsString("Financial record")))
                .andExpect(jsonPath("$.missingInputs[1]").value(containsString("Risk assessment")));
        assertThat(count("profitability_snapshots")).isZero();
    }

    @Test
    void duplicatePeriodRecordsAreRejected() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        financialRecord("L1001", "2026", "1.00", "0.00", "500000.00", "0.07", "35000.00", "7000.00")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("already has a financial record")));
        riskAssessment("L1001", "2026", "0.20", "0.20", "1.00")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("already has a risk assessment")));
        allocation("L1001", "2026", "LOAN_PROCESSING", "1.00").andExpect(status().isConflict());

        assertThat(count("financial_records")).isEqualTo(1);
        assertThat(count("risk_assessments")).isEqualTo(1);
        assertThat(count("operational_cost_allocations")).isEqualTo(3);
    }

    @Test
    @DisplayName("an incomplete cost breakdown that does not add up to the operational cost is refused")
    void allocationBreakdownMustAddUp() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_PROCESSING", "500.00").andExpect(status().isCreated()); // 500 of 7,000

        getJson("/api/loans/L1001/profitability?period=2026")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("allocations total 500.00")));
        assertThat(count("profitability_snapshots")).isZero();

        // And an allocation larger than the remaining operational cost is refused up front.
        allocation("L1001", "2026", "LOAN_SERVICING", "7000.00").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void fundingCostConsistencyIsEnforcedWhenTheBalanceIsSupplied() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "40000.00", "7000.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.fundingCost").exists());
        assertThat(count("financial_records")).isZero();
    }

    @Test
    void periodValidationOnReadEndpoints() throws Exception {
        createRahulAndLoan();
        getJson("/api/loans/L1001/profitability?period=26").andExpect(status().isBadRequest());
        getJson("/api/loans/L1001/profitability").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.period").exists());
        getJson("/api/loans/L1001/financial-records/20x6").andExpect(status().isBadRequest());
        getJson("/api/loans/L1001/financial-records/2026").andExpect(status().isNotFound());
        getJson("/api/loans/NOPE/profitability?period=2026").andExpect(status().isNotFound());
    }

    @Test
    void creditRiskInputsAreValidated() throws Exception {
        createRahulAndLoan();
        riskAssessment("L1001", "2026", "1.5", "0.60", "500000.00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.probabilityOfDefault").exists());
        riskAssessment("L1001", "2026", "10", "0.60", "500000.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.probabilityOfDefault").value(containsString("0 and 1")));
        riskAssessment("L1001", "2026", "0.10", "-0.1", "500000.00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.lossGivenDefault").exists());
        riskAssessment("L1001", "2026", "0.10", "0.60", "-1.00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.exposureAtDefault").exists());
        assertThat(count("risk_assessments")).isZero();

        // Boundaries are allowed, and the rating is derived from PD.
        riskAssessment("L1001", "2026", "0", "0", "0.00").andExpect(status().isCreated()).andExpect(jsonPath("$.riskRating").value("LOW"))
                .andExpect(jsonPath("$.expectedCreditLoss").value(0.00));
        riskAssessment("L1001", "2027", "1", "1", "100.50").andExpect(status().isCreated()).andExpect(jsonPath("$.riskRating").value("HIGH"))
                .andExpect(jsonPath("$.expectedCreditLoss").value(100.50));
        getJson("/api/loans/L1001/risk-assessments").andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].reportingPeriod").value("2027"));
        getJson("/api/loans/L1001/risk-assessments/2026").andExpect(jsonPath("$.riskRating").value("LOW"));
    }
}
