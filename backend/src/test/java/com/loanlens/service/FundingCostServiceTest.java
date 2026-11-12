package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Funding Cost = Average Outstanding Balance x Funding Rate x (months / 12), rounded once HALF_UP to 2 decimals. */
class FundingCostServiceTest {

    private final FundingCostService service = new FundingCostService();

    @Test
    @DisplayName("Rahul: 5,00,000 average balance x 7% = 35,000.00")
    void rahul() {
        BigDecimal cost = service.calculate(new BigDecimal("500000.00"), new BigDecimal("0.07"));
        assertThat(cost).isEqualByComparingTo("35000.00");
        assertThat(cost.scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "balance {0} x rate {1} (12 months) = {2}")
    @CsvSource({
            "500000.00,    0.07,     35000.00",
            "500000.00,    0.05,     25000.00",     // different rate
            "500000.00,    0.0925,   46250.00",
            "4250000.00,   0.07,     297500.00",
            "1000000.00,   0.07,     70000.00",     // different balance
            "250000.00,    0.07,     17500.00",
            "12345.67,     0.0725,   895.06",       // 895.061075 rounds down
            "100.01,       0.05,     5.00",         // 5.0005 -> 5.00
            "100.10,       0.05,     5.01",         // 5.005 -> 5.01: HALF_UP, not banker's rounding
            "500000.00,    0,        0.00",         // zero rate
            "0,            0.07,     0.00",         // zero balance
            "0.00,         0,        0.00"
    })
    void annualFundingCost(String balance, String rate, String expected) {
        BigDecimal cost = service.calculate(new BigDecimal(balance), new BigDecimal(rate));
        assertThat(cost).isEqualByComparingTo(expected);
        assertThat(cost.scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{2} months at balance {0}, rate {1} = {3}")
    @CsvSource({
            "500000.00, 0.07, 12, 35000.00",
            "500000.00, 0.07, 6,  17500.00",    // half-year
            "500000.00, 0.07, 3,  8750.00",     // quarter
            "500000.00, 0.07, 1,  2916.67",     // 2916.666... rounded once, only at the end
            "500000.00, 0.07, 7,  20416.67",    // 20416.666...
            "100000.00, 0.12, 5,  5000.00"
    })
    void partYearFundingCost(String balance, String rate, int months, String expected) {
        assertThat(service.calculate(new BigDecimal(balance), new BigDecimal(rate), months)).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("uses the AVERAGE outstanding balance, not the original principal")
    void averageBalanceNotPrincipal() {
        BigDecimal principal = new BigDecimal("500000.00");
        BigDecimal average = new BigDecimal("420000.00"); // part-repaid during the year
        BigDecimal rate = new BigDecimal("0.07");

        assertThat(service.calculate(average, rate)).isEqualByComparingTo("29400.00");
        assertThat(service.calculate(average, rate)).isNotEqualByComparingTo(service.calculate(principal, rate));
    }

    @Test
    @DisplayName("rounding happens once at the end: twelve rounded months would drift from the annual cost")
    void roundsOnceNotPerMonth() {
        BigDecimal balance = new BigDecimal("500000.00");
        BigDecimal rate = new BigDecimal("0.07");
        BigDecimal oneMonth = service.calculate(balance, rate, 1);              // 2916.67
        assertThat(oneMonth.multiply(BigDecimal.valueOf(12))).isEqualByComparingTo("35000.04"); // what per-month rounding would give
        assertThat(service.calculate(balance, rate, 12)).isEqualByComparingTo("35000.00");
    }

    @ParameterizedTest(name = "invalid balance {0} / rate {1} / months {2}")
    @CsvSource({
            "-1.00,    0.07,  12",   // negative balance
            "500000,   -0.01, 12",   // negative rate
            "500000,   1.01,  12",   // rate above 100%
            "500000,   0.07,  0",
            "500000,   0.07,  13",
            "500000,   0.07,  -1"
    })
    void rejectsInvalidInputs(String balance, String rate, int months) {
        assertThatThrownBy(() -> service.calculate(new BigDecimal(balance), new BigDecimal(rate), months))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("missing funding information is an error, never silently zero")
    void missingInputsAreNeverZero() {
        assertThatThrownBy(() -> service.calculate(null, new BigDecimal("0.07")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("balance");
        assertThatThrownBy(() -> service.calculate(new BigDecimal("500000"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rate");
    }

    @ParameterizedTest(name = "supplied {0} vs balance 500000 x 7% (35000.00): consistent = {1}")
    @CsvSource({
            "35000.00, true",
            "35000.99, true",   // within the Rs 1.00 tolerance
            "34999.01, true",
            "35001.00, true",   // exactly at the tolerance
            "35001.01, false",
            "34998.99, false",
            "40000.00, false",
            "0.00,     false"   // zero is not "unknown": it contradicts the balance and rate
    })
    void consistencyCheckUsesTolerance(String supplied, boolean expected) {
        assertThat(service.isConsistent(new BigDecimal(supplied), new BigDecimal("500000.00"), new BigDecimal("0.07")))
                .isEqualTo(expected);
    }
}
