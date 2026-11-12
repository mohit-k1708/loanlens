package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfitabilityCalculatorTest {

    private final ProfitabilityCalculator calculator = new ProfitabilityCalculator();

    private static ProfitabilityCalculator.Inputs inputs(String interest, String fee, String funding, String ops, String ecl) {
        return new ProfitabilityCalculator.Inputs(new BigDecimal(interest), new BigDecimal(fee), new BigDecimal(funding),
                new BigDecimal(ops), new BigDecimal(ecl));
    }

    @Test
    @DisplayName("Rahul: income 62,000; costs before ECL 42,000; before ECL +20,000; ECL 30,000; after ECL -10,000")
    void rahul() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("60000", "2000", "35000", "7000", "30000"));

        assertThat(r.totalIncome()).isEqualByComparingTo("62000.00");
        assertThat(r.totalCostsBeforeEcl()).isEqualByComparingTo("42000.00");
        assertThat(r.contributionBeforeEcl()).isEqualByComparingTo("20000.00");
        assertThat(r.expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        assertThat(r.status()).isEqualTo(ContributionStatus.NEGATIVE);
    }

    @Test
    void positiveContribution() {
        // Priya (frontend demo): income 3,81,400; funding 2,97,500; ops 11,150; ECL 12,360 -> +60,390
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("372400", "9000", "297500", "11150", "12360"));
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("60390.00");
        assertThat(r.status()).isEqualTo(ContributionStatus.POSITIVE);
    }

    @Test
    void breakEvenIsExactlyZero() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("100", "0", "60", "10", "30"));
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("0.00");
        assertThat(r.status()).isEqualTo(ContributionStatus.BREAK_EVEN);
    }

    @Test
    @DisplayName("negative even before ECL when income does not cover funding and operating cost")
    void negativeBeforeEcl() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("1000", "0", "900", "300", "0"));
        assertThat(r.contributionBeforeEcl()).isEqualByComparingTo("-200.00");
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("-200.00");
        assertThat(r.status()).isEqualTo(ContributionStatus.NEGATIVE);
    }

    @Test
    @DisplayName("paise are exact: 0.1 + 0.2 style sums do not drift")
    void exactPaise() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("0.10", "0.20", "0.10", "0.10", "0.00"));
        assertThat(r.totalIncome()).isEqualByComparingTo("0.30");
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("0.10");
        assertThat(r.contributionAfterEcl().scale()).isEqualTo(2);
    }

    @Test
    void resultsAreAlwaysTwoDecimals() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("60000", "2000", "35000", "7000", "30000"));
        assertThat(r.totalIncome().scale()).isEqualTo(2);
        assertThat(r.contributionBeforeEcl().scale()).isEqualTo(2);
        assertThat(r.contributionAfterEcl().scale()).isEqualTo(2);
    }

    @Test
    void identitiesHold() {
        ProfitabilityCalculator.Result r = calculator.calculate(inputs("123456.78", "999.99", "54321.09", "1234.56", "7777.77"));
        assertThat(r.totalIncome()).isEqualByComparingTo(r.interestIncome().add(r.feeIncome()));
        assertThat(r.contributionBeforeEcl()).isEqualByComparingTo(r.totalIncome().subtract(r.fundingCost()).subtract(r.operationalCost()));
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo(r.contributionBeforeEcl().subtract(r.expectedCreditLoss()));
    }
}
