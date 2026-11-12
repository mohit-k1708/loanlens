package com.loanlens.dto;

import com.loanlens.entity.AllocationMethod;
import com.loanlens.entity.CostCategory;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Result of the simplified profitability calculation for one loan and one reporting period.
 * Every amount is rupees with 2 decimals. Rates are decimal fractions (0.07 = 7%).
 */
public record ProfitabilityResponse(
        LoanInfo loan,
        String reportingPeriod,

        BigDecimal interestIncome,
        BigDecimal feeIncome,
        BigDecimal totalIncome,

        /** Supplied average balance behind the funding cost; null if it was not recorded. */
        BigDecimal averageOutstandingBalance,
        BigDecimal fundingRate,
        BigDecimal fundingCost,
        BigDecimal operationalCost,
        /** Funding cost + operational cost. */
        BigDecimal totalCostsBeforeEcl,
        BigDecimal contributionBeforeEcl,

        RiskInfo risk,
        /** PD x LGD x EAD: an ESTIMATE of potential future credit loss, not a realised loss. */
        BigDecimal expectedCreditLoss,
        /** Funding cost + operational cost + expected credit loss. */
        BigDecimal totalExpenses,
        BigDecimal contributionAfterEcl,
        ContributionStatus contributionStatus,

        List<OperationalCostLine> operationalCostAllocations,

        SnapshotInfo snapshot,
        List<String> notes) {

    public enum ContributionStatus {
        POSITIVE, NEGATIVE, BREAK_EVEN
    }

    public record LoanInfo(
            Long id,
            String loanCode,
            String customerCode,
            String customerName,
            LoanType loanType,
            LoanStatus loanStatus,
            BigDecimal originalPrincipal,
            BigDecimal outstandingBalance,
            BigDecimal interestRate) {
    }

    public record RiskInfo(
            BigDecimal probabilityOfDefault,
            BigDecimal lossGivenDefault,
            BigDecimal exposureAtDefault,
            RiskRating riskRating) {
    }

    public record OperationalCostLine(
            CostCategory costCategory,
            AllocationMethod allocationMethod,
            String allocationDriver,
            BigDecimal allocatedAmount,
            String sourceDescription) {
    }

    /** The stored snapshot behind this response. {@code newlyCreated} is false when identical inputs were already stored. */
    public record SnapshotInfo(Long id, String calculationVersion, Instant calculatedAt, boolean newlyCreated) {
    }
}
