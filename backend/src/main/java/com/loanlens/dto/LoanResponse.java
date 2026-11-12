package com.loanlens.dto;

import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LoanResponse(
        Long id,
        String loanCode,
        CustomerSummary customer,
        LoanType loanType,
        BigDecimal originalPrincipal,
        BigDecimal outstandingBalance,
        /** Annual rate as a decimal fraction (0.10 = 10%). */
        BigDecimal interestRate,
        Integer tenureMonths,
        LocalDate startDate,
        LocalDate maturityDate,
        LoanStatus loanStatus,
        /** Rating from the most recent risk assessment, or null if the loan has none. */
        RiskRating latestRiskRating,
        Instant createdAt,
        Instant updatedAt,
        /** Present only when the list was requested with a reporting period. */
        LoanProfitabilitySummary profitability) {

    public record CustomerSummary(Long id, String customerCode, String fullName) {
    }
}
