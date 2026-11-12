package com.loanlens.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FinancialRecordResponse(
        Long id,
        String loanCode,
        String reportingPeriod,
        BigDecimal interestIncome,
        BigDecimal feeIncome,
        BigDecimal totalIncome,
        BigDecimal averageOutstandingBalance,
        BigDecimal fundingRate,
        BigDecimal fundingCost,
        BigDecimal operationalCost,
        Instant createdAt,
        Instant updatedAt) {
}
