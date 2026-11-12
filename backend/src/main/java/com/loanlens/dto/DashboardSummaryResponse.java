package com.loanlens.dto;

import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import java.util.List;

/**
 * Portfolio overview for one reporting period.
 *
 * <p>"Active" loans are ACTIVE or UNDER_WATCH. Contribution figures and the positive/negative/break-even counts cover
 * the active loans that HAVE both a financial record and a risk assessment for the period ({@code loansWithData});
 * the others are counted in {@code loansWithoutData} and are never treated as zero.
 */
public record DashboardSummaryResponse(
        String reportingPeriod,
        int totalActiveLoans,
        /** Sum of outstanding balances of ALL active loans, with or without period data. */
        BigDecimal totalOutstandingBalance,
        int loansWithData,
        int loansWithoutData,
        BigDecimal totalIncome,
        BigDecimal totalCostsBeforeEcl,
        BigDecimal totalExpenses,
        BigDecimal contributionBeforeEcl,
        BigDecimal expectedCreditLoss,
        BigDecimal contributionAfterEcl,
        int positiveContributionLoans,
        int negativeContributionLoans,
        int breakEvenLoans,
        int underWatchLoans,
        int highRiskLoans,
        /** One point per reporting period that has data (oldest first). Annual periods only: no monthly history exists. */
        List<TrendPoint> contributionTrend,
        List<AttentionLoan> attentionLoans) {

    public record TrendPoint(String reportingPeriod, int loansIncluded, BigDecimal contributionBeforeEcl,
                             BigDecimal expectedCreditLoss, BigDecimal contributionAfterEcl) {
    }

    public record AttentionLoan(
            String loanCode,
            String customerCode,
            String customerName,
            LoanType loanType,
            LoanStatus loanStatus,
            BigDecimal outstandingBalance,
            RiskRating riskRating,
            BigDecimal contributionAfterEcl,
            ContributionStatus contributionStatus,
            List<String> reasons) {
    }
}
