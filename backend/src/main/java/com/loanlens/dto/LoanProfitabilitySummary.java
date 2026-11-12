package com.loanlens.dto;

import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import java.util.List;

/**
 * Compact profitability figures attached to a loan list row when a reporting period is requested.
 * When {@code available} is false the money fields are null (never zero) and {@code missingInputs} names what is
 * missing: FINANCIAL_RECORD and/or RISK_ASSESSMENT.
 */
public record LoanProfitabilitySummary(
        String reportingPeriod,
        boolean available,
        List<String> missingInputs,
        BigDecimal totalIncome,
        BigDecimal totalCostsBeforeEcl,
        BigDecimal totalExpenses,
        BigDecimal contributionBeforeEcl,
        BigDecimal expectedCreditLoss,
        BigDecimal contributionAfterEcl,
        ContributionStatus contributionStatus,
        /** Rating of the assessment for THIS period; null if there is none. */
        RiskRating riskRating) {
}
