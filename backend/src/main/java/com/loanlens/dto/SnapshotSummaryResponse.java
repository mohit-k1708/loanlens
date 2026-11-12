package com.loanlens.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of a loan's stored calculation history for a reporting period. */
public record SnapshotSummaryResponse(
        Long id,
        String reportingPeriod,
        BigDecimal totalIncome,
        BigDecimal fundingCost,
        BigDecimal operationalCost,
        BigDecimal contributionBeforeEcl,
        BigDecimal expectedCreditLoss,
        BigDecimal contributionAfterEcl,
        String calculationVersion,
        Instant calculatedAt) {
}
