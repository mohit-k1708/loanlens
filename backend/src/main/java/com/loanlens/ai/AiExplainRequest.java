package com.loanlens.ai;

import com.loanlens.validation.ReportingPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/ai/explain}. {@code reportingPeriod} may be sent as the number 2026 or the string
 * "2026". Unknown fields are rejected (the application-wide Jackson setting).
 *
 * @param loanId          loan code (e.g. L1001) or numeric id
 * @param compareToLoanId optional second loan for "why is one loan more profitable than another"
 */
public record AiExplainRequest(
        @NotBlank(message = "Loan is required.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,30}$", message = "Loan must be a loan code such as L1001.")
        String loanId,

        @NotNull(message = "Reporting period is required.")
        @ReportingPeriod
        String reportingPeriod,

        @NotBlank(message = "Please type a question.")
        @Size(min = 3, max = 500, message = "The question must be between 3 and 500 characters.")
        String question,

        @Pattern(regexp = "^[A-Za-z0-9_-]{1,30}$", message = "Comparison loan must be a loan code such as L1002.")
        String compareToLoanId) {
}
