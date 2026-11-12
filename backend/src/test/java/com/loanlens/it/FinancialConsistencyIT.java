package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same loan must show the same numbers everywhere, periods must never mix, history must not change, and a missing
 * input must never become zero. Runs against a real PostgreSQL through the whole application.
 */
class FinancialConsistencyIT extends AbstractPostgresIT {

    private static BigDecimal money(Object jsonNumber) {
        return new BigDecimal(jsonNumber.toString()).setScale(2);
    }

    private BigDecimal amount(org.springframework.test.web.servlet.ResultActions r, String path) throws Exception {
        Number n = read(r, path);
        return new BigDecimal(n.toString()).setScale(2);
    }

    /* ---------- 1. loan details match profitability ---------- */

    @Test
    @DisplayName("1. loan details, loan list row and profitability agree on the loan's own figures")
    void loanDetailsMatchProfitability() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        var details = getJson("/api/loans/L1001").andExpect(status().isOk());
        var profitability = getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk());
        var listRow = getJson("/api/loans?period=2026&loanCode=L1001").andExpect(status().isOk());

        BigDecimal principal = amount(details, "$.originalPrincipal");
        BigDecimal outstanding = amount(details, "$.outstandingBalance");
        assertThat(amount(profitability, "$.loan.originalPrincipal")).isEqualByComparingTo(principal);
        assertThat(amount(profitability, "$.loan.outstandingBalance")).isEqualByComparingTo(outstanding);
        assertThat(amount(listRow, "$.content[0].originalPrincipal")).isEqualByComparingTo(principal);
        assertThat(amount(listRow, "$.content[0].outstandingBalance")).isEqualByComparingTo(outstanding);

        // and the contribution in the list row is the profitability endpoint's
        assertThat(amount(listRow, "$.content[0].profitability.contributionAfterEcl"))
                .isEqualByComparingTo(amount(profitability, "$.contributionAfterEcl"))
                .isEqualByComparingTo("-10000.00");
        assertThat(amount(listRow, "$.content[0].profitability.contributionBeforeEcl"))
                .isEqualByComparingTo(amount(profitability, "$.contributionBeforeEcl"))
                .isEqualByComparingTo("20000.00");
        assertThat(amount(listRow, "$.content[0].profitability.expectedCreditLoss"))
                .isEqualByComparingTo(amount(profitability, "$.expectedCreditLoss"))
                .isEqualByComparingTo("30000.00");
    }

    /* ---------- 2. dashboard totals match loan records ---------- */

    @Test
    @DisplayName("2. every dashboard total equals the sum of the loans' own profitability results")
    void dashboardTotalsMatchLoanRecords() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        long b = createCustomer("C2002", "Priya Nair", "priya@example.com");
        createLoan("L1002", b, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");
        financialRecord("L1002", "2026", "372400.00", "9000.00", "4250000.00", "0.07", "297500.00", "11150.00").andExpect(status().isCreated());
        riskAssessment("L1002", "2026", "0.012", "0.25", "4120000.00").andExpect(status().isCreated());
        long c = createCustomer("C2003", "Aman Verma", "aman@example.com");
        createLoan("L1003", c, "AUTO", "800000.00", "650000.00", "0.095", 60, "2024-01-10");
        financialRecord("L1003", "2026", "61750.00", "1500.00", "725000.00", "0.065", "47125.00", "5200.00").andExpect(status().isCreated());
        riskAssessment("L1003", "2026", "0.04", "0.45", "650000.00").andExpect(status().isCreated());

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal costs = BigDecimal.ZERO;
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal ecl = BigDecimal.ZERO;
        BigDecimal after = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (String code : List.of("L1001", "L1002", "L1003")) {
            var p = getJson("/api/loans/" + code + "/profitability?period=2026").andExpect(status().isOk());
            income = income.add(amount(p, "$.totalIncome"));
            costs = costs.add(amount(p, "$.totalCostsBeforeEcl"));
            before = before.add(amount(p, "$.contributionBeforeEcl"));
            ecl = ecl.add(amount(p, "$.expectedCreditLoss"));
            after = after.add(amount(p, "$.contributionAfterEcl"));
            outstanding = outstanding.add(amount(p, "$.loan.outstandingBalance"));
        }

        var dashboard = getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk());
        assertThat(amount(dashboard, "$.totalIncome")).isEqualByComparingTo(income);
        assertThat(amount(dashboard, "$.totalCostsBeforeEcl")).isEqualByComparingTo(costs);
        assertThat(amount(dashboard, "$.contributionBeforeEcl")).isEqualByComparingTo(before);
        assertThat(amount(dashboard, "$.expectedCreditLoss")).isEqualByComparingTo(ecl);
        assertThat(amount(dashboard, "$.contributionAfterEcl")).isEqualByComparingTo(after);
        assertThat(amount(dashboard, "$.totalOutstandingBalance")).isEqualByComparingTo(outstanding);
        // and the accounting identities hold on the portfolio totals
        assertThat(after).isEqualByComparingTo(before.subtract(ecl));
        assertThat(before).isEqualByComparingTo(income.subtract(costs));
        // loan list rows add up to the same figure
        var all = getJson("/api/loans?period=2026&size=50");
        BigDecimal listSum = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            listSum = listSum.add(amount(all, "$.content[" + i + "].profitability.contributionAfterEcl"));
        }
        assertThat(listSum).isEqualByComparingTo(after);
    }

    /* ---------- 3 + 4. ECL uses the correct period; periods never mix ---------- */

    @Test
    @DisplayName("3. ECL uses the risk assessment of the requested period; changing one period leaves the other alone")
    void eclUsesTheCorrectPeriodsAssessment() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2025", "50000.00", "1000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2025", "0.05", "0.60", "500000.00").andExpect(status().isCreated());   // ECL 15,000
        addRahulsInputs2026();                                                                            // ECL 30,000

        assertThat(amount(getJson("/api/loans/L1001/profitability?period=2025"), "$.expectedCreditLoss")).isEqualByComparingTo("15000.00");
        assertThat(amount(getJson("/api/loans/L1001/profitability?period=2026"), "$.expectedCreditLoss")).isEqualByComparingTo("30000.00");

        // 2026's assessment is corrected: only 2026 changes
        putJson("/api/loans/L1001/risk-assessments/2026", """
                {"reportingPeriod":"2026","probabilityOfDefault":0.02,"lossGivenDefault":0.60,"exposureAtDefault":500000.00}""")
                .andExpect(status().isOk());
        assertThat(amount(getJson("/api/loans/L1001/profitability?period=2026"), "$.expectedCreditLoss")).isEqualByComparingTo("6000.00");
        assertThat(amount(getJson("/api/loans/L1001/profitability?period=2025"), "$.expectedCreditLoss")).isEqualByComparingTo("15000.00");
        // the risk assessment endpoint reports the same ECL as the profitability endpoint
        assertThat(amount(getJson("/api/loans/L1001/risk-assessments/2025"), "$.expectedCreditLoss")).isEqualByComparingTo("15000.00");
    }

    @Test
    @DisplayName("4. periods are never mixed: a record for one year and an assessment for another do not combine")
    void periodsNeverMix() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2027", "0.10", "0.60", "500000.00").andExpect(status().isCreated());

        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs.length()").value(1))
                .andExpect(jsonPath("$.missingInputs[0]").value(org.hamcrest.Matchers.containsString("Risk assessment")));
        getJson("/api/loans/L1001/profitability?period=2027").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs[0]").value(org.hamcrest.Matchers.containsString("Financial record")));

        // the list agrees, and the dashboard does not combine them either
        getJson("/api/loans?period=2026&loanCode=L1001")
                .andExpect(jsonPath("$.content[0].profitability.available").value(false))
                .andExpect(jsonPath("$.content[0].profitability.missingInputs", contains("RISK_ASSESSMENT")));
        getJson("/api/dashboard/summary?period=2026").andExpect(jsonPath("$.loansWithData").value(0))
                .andExpect(jsonPath("$.contributionAfterEcl").value(0.00));
        getJson("/api/dashboard/summary?period=2027").andExpect(jsonPath("$.loansWithData").value(0));
    }

    /* ---------- 5 + 6. snapshots: history unchanged, no duplicates ---------- */

    @Test
    @DisplayName("5. adding a new period leaves the stored snapshot of an earlier period untouched")
    void historicalSnapshotsAreUnchangedWhenANewPeriodIsAdded() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk());
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM profitability_snapshots WHERE reporting_period = '2026' ORDER BY id");
        assertThat(before).hasSize(1);

        financialRecord("L1001", "2027", "70000.00", "3000.00", "480000.00", "0.07", "33600.00", "7500.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2027", "0.04", "0.55", "480000.00").andExpect(status().isCreated());
        getJson("/api/loans/L1001/profitability?period=2027").andExpect(status().isOk());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk());

        List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM profitability_snapshots WHERE reporting_period = '2026' ORDER BY id");
        assertThat(after).isEqualTo(before);   // same row, same values, same calculated_at
        assertThat(count("profitability_snapshots")).isEqualTo(2);
        getJson("/api/loans/L1001/profitability/history?period=2026").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("6. recalculating identical inputs creates no duplicate; a changed input adds one new snapshot and keeps the old")
    void recalculationCreatesNoDuplicates() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();

        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(true));
        for (int i = 0; i < 4; i++) {
            getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(false));
        }
        assertThat(count("profitability_snapshots")).isEqualTo(1);

        // a genuine change (PD corrected) is a new calculation; the earlier one stays as history
        putJson("/api/loans/L1001/risk-assessments/2026", """
                {"reportingPeriod":"2026","probabilityOfDefault":0.05,"lossGivenDefault":0.60,"exposureAtDefault":500000.00}""")
                .andExpect(status().isOk());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(true))
                .andExpect(jsonPath("$.contributionAfterEcl").value(5000.00));
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(false));
        assertThat(count("profitability_snapshots")).isEqualTo(2);
        List<BigDecimal> afterEcl = jdbc.queryForList("SELECT contribution_after_ecl FROM profitability_snapshots ORDER BY id", BigDecimal.class);
        assertThat(afterEcl).usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("-10000.00"), new BigDecimal("5000.00"));

        // changing back to the earlier inputs reuses the first snapshot rather than inserting a third
        putJson("/api/loans/L1001/risk-assessments/2026", """
                {"reportingPeriod":"2026","probabilityOfDefault":0.10,"lossGivenDefault":0.60,"exposureAtDefault":500000.00}""")
                .andExpect(status().isOk());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.snapshot.newlyCreated").value(false));
        assertThat(count("profitability_snapshots")).isEqualTo(2);
    }

    /* ---------- 7. missing inputs never silently zero ---------- */

    @Test
    @DisplayName("7. missing inputs are never silently zero: detail 422, list unavailable with null money, dashboard excludes the loan")
    void missingInputsAreNeverZero() throws Exception {
        createRahulAndLoan();                       // no financial record, no assessment

        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs.length()").value(2))
                .andExpect(jsonPath("$.contributionAfterEcl").doesNotExist());
        getJson("/api/loans?period=2026&loanCode=L1001")
                .andExpect(jsonPath("$.content[0].profitability.available").value(false))
                .andExpect(jsonPath("$.content[0].profitability.contributionAfterEcl").doesNotExist())
                .andExpect(jsonPath("$.content[0].profitability.totalIncome").doesNotExist());
        getJson("/api/dashboard/summary?period=2026").andExpect(jsonPath("$.loansWithData").value(0))
                .andExpect(jsonPath("$.loansWithoutData").value(1));
        assertThat(count("profitability_snapshots")).isZero();

        // an absent average balance is null in the response, not 0
        financialRecord("L1001", "2026", "60000.00", "2000.00", "null", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.averageOutstandingBalance", nullValue()));
    }

    /* ---------- validation / data-quality regressions found in the Phase 5 review ---------- */

    @Test
    @DisplayName("regression: a cost breakdown that does not add up is unavailable in the list AND a 422 on the detail page")
    void allocationMismatchIsConsistentEverywhere() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        allocation("L1001", "2026", "LOAN_PROCESSING", "500.00").andExpect(status().isCreated());   // 500 of 7,000 only

        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isUnprocessableEntity());
        getJson("/api/loans?period=2026&loanCode=L1001")
                .andExpect(jsonPath("$.content[0].profitability.available").value(false))
                .andExpect(jsonPath("$.content[0].profitability.missingInputs", contains("COST_ALLOCATION_MISMATCH")));
        getJson("/api/dashboard/summary?period=2026").andExpect(jsonPath("$.loansWithData").value(0))
                .andExpect(jsonPath("$.contributionAfterEcl").value(0.00));

        // completing the breakdown makes all three agree
        allocation("L1001", "2026", "CUSTOMER_SUPPORT", "6500.00").andExpect(status().isCreated());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk());
        getJson("/api/loans?period=2026&loanCode=L1001").andExpect(jsonPath("$.content[0].profitability.available").value(true));
        getJson("/api/dashboard/summary?period=2026").andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00));
    }

    @Test
    @DisplayName("regression: operational cost cannot be corrected below the breakdown already recorded")
    void operationalCostCannotBeLoweredBelowAllocations() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();    // allocations total 7,000

        putJson("/api/loans/L1001/financial-records/2026", """
                {"reportingPeriod":"2026","interestIncome":60000.00,"feeIncome":2000.00,"averageOutstandingBalance":500000.00,
                 "fundingRate":0.07,"fundingCost":35000.00,"operationalCost":6000.00}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.operationalCost").exists());
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.operationalCost").value(7000.00));
    }

    @Test
    @DisplayName("regression: the average outstanding balance cannot exceed the loan's principal; funding cost must match the average balance")
    void averageBalanceRules() throws Exception {
        createRahulAndLoan();   // principal 5,00,000

        financialRecord("L1001", "2026", "60000.00", "2000.00", "550000.00", "0.07", "38500.00", "7000.00")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.averageOutstandingBalance").exists());
        // 4,20,000 x 7% = 29,400, not principal x rate = 35,000
        financialRecord("L1001", "2026", "60000.00", "2000.00", "420000.00", "0.07", "35000.00", "7000.00")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.fundingCost").exists());
        financialRecord("L1001", "2026", "60000.00", "2000.00", "420000.00", "0.07", "29400.00", "7000.00")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("invalid inputs are rejected at the API: negative rate, PD/LGD above 1, negative EAD")
    void invalidInputsAreRejected() throws Exception {
        createRahulAndLoan();

        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "-0.07", "35000.00", "7000.00")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.fundingRate").exists());
        financialRecord("L1001", "2026", "60000.00", "2000.00", "500000.00", "7", "35000.00", "7000.00")   // 7 = "7%" typed as a whole number
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.fundingRate").exists());
        riskAssessment("L1001", "2026", "1.5", "0.60", "500000.00")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.probabilityOfDefault").exists());
        riskAssessment("L1001", "2026", "10", "0.60", "500000.00")      // "10%" typed as 10
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.probabilityOfDefault").exists());
        riskAssessment("L1001", "2026", "0.10", "1.01", "500000.00")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.lossGivenDefault").exists());
        riskAssessment("L1001", "2026", "0.10", "0.60", "-1.00")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.exposureAtDefault").exists());
        assertThat(count("risk_assessments")).isZero();
        assertThat(count("financial_records")).isZero();
    }

    @Test
    @DisplayName("rounding: amounts are plain 2-decimal numbers and the half-paisa case rounds HALF_UP once")
    void roundingPolicy() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2026", "100.10", "0.05", "null", "0.07", "10.00", "5.00").andExpect(status().isCreated());
        // ECL = 0.5 x 0.5 x 0.02 = 0.005 -> 0.01 (HALF_UP, not banker's 0.00)
        riskAssessment("L1001", "2026", "0.5", "0.5", "0.02").andExpect(status().isCreated());

        String json = body(getJson("/api/loans/L1001/profitability?period=2026").andExpect(status().isOk()));
        assertThat(json).contains("\"expectedCreditLoss\":0.01").contains("\"totalIncome\":100.15")
                .contains("\"contributionBeforeEcl\":85.15").contains("\"contributionAfterEcl\":85.14")
                .doesNotContain("E+").doesNotContain("e+");
        assertThat(money(read(getJson("/api/loans/L1001/profitability?period=2026"), "$.contributionAfterEcl")))
                .isEqualByComparingTo("85.14");
    }
}
