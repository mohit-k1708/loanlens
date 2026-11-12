package com.loanlens.dto;

import com.loanlens.validation.ReportingPeriod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** PD and LGD are decimal fractions between 0 and 1 (0.10 = 10%). The risk rating is derived from PD. */
public record RiskAssessmentRequest(
        @NotNull(message = "Reporting period is required.")
        @ReportingPeriod
        String reportingPeriod,

        @NotNull(message = "Probability of default is required.")
        @DecimalMin(value = "0.0", message = "Probability of default must be between 0 and 1 (e.g. 0.10 for 10%).")
        @DecimalMax(value = "1.0", message = "Probability of default must be between 0 and 1 (e.g. 0.10 for 10%).")
        @Digits(integer = 1, fraction = 6, message = "Probability of default must be a decimal fraction between 0 and 1 with at most 6 decimal places, e.g. 0.10 for 10%.")
        BigDecimal probabilityOfDefault,

        @NotNull(message = "Loss given default is required.")
        @DecimalMin(value = "0.0", message = "Loss given default must be between 0 and 1 (e.g. 0.60 for 60%).")
        @DecimalMax(value = "1.0", message = "Loss given default must be between 0 and 1 (e.g. 0.60 for 60%).")
        @Digits(integer = 1, fraction = 6, message = "Loss given default must be a decimal fraction between 0 and 1 with at most 6 decimal places, e.g. 0.60 for 60%.")
        BigDecimal lossGivenDefault,

        @NotNull(message = "Exposure at default is required.")
        @DecimalMin(value = "0.00", message = "Exposure at default cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Exposure at default allows at most 2 decimal places.")
        BigDecimal exposureAtDefault) {
}
