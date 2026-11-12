package com.loanlens.mapper;

import com.loanlens.dto.LoanProfitabilitySummary;
import com.loanlens.dto.LoanResponse;
import com.loanlens.entity.Loan;
import com.loanlens.entity.RiskRating;
import com.loanlens.service.ProfitabilityOverviewService.LoanContribution;

public final class LoanMapper {

    private LoanMapper() {
    }

    public static LoanResponse toResponse(Loan l, RiskRating latestRiskRating) {
        return toResponse(l, latestRiskRating, null);
    }

    public static LoanResponse toResponse(Loan l, RiskRating latestRiskRating, LoanProfitabilitySummary profitability) {
        return new LoanResponse(
                l.getId(), l.getLoanCode(),
                new LoanResponse.CustomerSummary(l.getCustomer().getId(), l.getCustomer().getCustomerCode(), l.getCustomer().getFullName()),
                l.getLoanType(), l.getOriginalPrincipal(), l.getOutstandingBalance(), l.getInterestRate(),
                l.getTenureMonths(), l.getStartDate(), l.getMaturityDate(), l.getLoanStatus(),
                latestRiskRating, l.getCreatedAt(), l.getUpdatedAt(), profitability);
    }

    public static LoanProfitabilitySummary toSummary(String period, LoanContribution c) {
        if (c == null || !c.available()) {
            return new LoanProfitabilitySummary(period, false, c == null ? java.util.List.of() : c.missingInputs(),
                    null, null, null, null, null, null, null, c == null ? null : c.riskRating());
        }
        var r = c.result();
        return new LoanProfitabilitySummary(period, true, java.util.List.of(), r.totalIncome(), r.totalCostsBeforeEcl(),
                r.totalExpenses(), r.contributionBeforeEcl(), r.expectedCreditLoss(), r.contributionAfterEcl(), r.status(), c.riskRating());
    }
}
