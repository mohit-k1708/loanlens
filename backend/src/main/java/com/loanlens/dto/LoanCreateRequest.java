package com.loanlens.dto;

import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Rates are decimal fractions: 0.10 means 10%. */
public record LoanCreateRequest(
        @NotBlank(message = "Loan code is required.")
        @Pattern(regexp = "^[A-Z][A-Z0-9-]{2,19}$", message = "Loan code must start with a letter and be 3-20 characters: uppercase letters, digits or hyphens.")
        String loanCode,

        @NotNull(message = "Customer is required.")
        @Positive(message = "Customer id must be a positive number.")
        Long customerId,

        @NotNull(message = "Loan type is required.")
        LoanType loanType,

        @NotNull(message = "Original principal is required.")
        @DecimalMin(value = "0.01", message = "Original principal must be greater than zero.")
        @Digits(integer = 17, fraction = 2, message = "Original principal allows at most 2 decimal places.")
        BigDecimal originalPrincipal,

        @NotNull(message = "Outstanding balance is required.")
        @DecimalMin(value = "0.00", message = "Outstanding balance cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Outstanding balance allows at most 2 decimal places.")
        BigDecimal outstandingBalance,

        @NotNull(message = "Interest rate is required.")
        @DecimalMin(value = "0.000001", message = "Interest rate must be greater than zero. Rates are decimal fractions, e.g. 0.10 for 10%.")
        @DecimalMax(value = "0.60", message = "Interest rate cannot exceed 0.60 (60%). Rates are decimal fractions, e.g. 0.10 for 10%.")
        @Digits(integer = 1, fraction = 6, message = "Interest rate must be a decimal fraction with at most 6 decimal places, e.g. 0.10 for 10%.")
        BigDecimal interestRate,

        @NotNull(message = "Tenure is required.")
        @Min(value = 1, message = "Tenure must be at least 1 month.")
        @Max(value = 480, message = "Tenure cannot exceed 480 months.")
        Integer tenureMonths,

        @NotNull(message = "Start date is required.")
        @PastOrPresent(message = "Start date cannot be in the future.")
        LocalDate startDate,

        /** Optional; defaults to ACTIVE. */
        LoanStatus loanStatus) {
}
