package com.loanlens.dto;

import com.loanlens.validation.ReportingPeriod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Supplied income and cost inputs for one loan and one reporting period (money in rupees, rates as decimal fractions).
 * Interest income is an input; it is not computed from principal x rate.
 */
public record FinancialRecordRequest(
        @NotNull(message = "Reporting period is required.")
        @ReportingPeriod
        String reportingPeriod,

        @NotNull(message = "Interest income is required.")
        @DecimalMin(value = "0.00", message = "Interest income cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Interest income allows at most 2 decimal places.")
        BigDecimal interestIncome,

        @NotNull(message = "Fee income is required (send 0 if there is none).")
        @DecimalMin(value = "0.00", message = "Fee income cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Fee income allows at most 2 decimal places.")
        BigDecimal feeIncome,

        /** Optional. When supplied, funding cost must agree with balance x funding rate. */
        @DecimalMin(value = "0.00", message = "Average outstanding balance cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Average outstanding balance allows at most 2 decimal places.")
        BigDecimal averageOutstandingBalance,

        @NotNull(message = "Funding rate is required.")
        @DecimalMin(value = "0.00", message = "Funding rate cannot be negative.")
        @DecimalMax(value = "1.00", message = "Funding rate must be a decimal fraction between 0 and 1, e.g. 0.07 for 7%.")
        @Digits(integer = 1, fraction = 6, message = "Funding rate must be a decimal fraction with at most 6 decimal places, e.g. 0.07 for 7%.")
        BigDecimal fundingRate,

        @NotNull(message = "Funding cost is required.")
        @DecimalMin(value = "0.00", message = "Funding cost cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Funding cost allows at most 2 decimal places.")
        BigDecimal fundingCost,

        @NotNull(message = "Operational cost is required.")
        @DecimalMin(value = "0.00", message = "Operational cost cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Operational cost allows at most 2 decimal places.")
        BigDecimal operationalCost) {
}
