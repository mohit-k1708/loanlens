package com.loanlens.service;

import com.loanlens.dto.DashboardSummaryResponse;
import com.loanlens.dto.DashboardSummaryResponse.AttentionLoan;
import com.loanlens.dto.DashboardSummaryResponse.TrendPoint;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.RiskRating;
import com.loanlens.repository.LoanRepository;
import com.loanlens.service.ProfitabilityOverviewService.LoanContribution;
import com.loanlens.validation.ReportingPeriods;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolio totals. Every figure is the sum of the SAME per-loan results that the list and the detail endpoint
 * return (via {@link ProfitabilityOverviewService}), so the dashboard can never disagree with a loan's own page.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final int MAX_ATTENTION_LOANS = 20;

    private final LoanRepository loans;
    private final ProfitabilityOverviewService overview;
    private final ReportingPeriodService periods;

    public DashboardService(LoanRepository loans, ProfitabilityOverviewService overview, ReportingPeriodService periods) {
        this.loans = loans;
        this.overview = overview;
        this.periods = periods;
    }

    public DashboardSummaryResponse summary(String period) {
        ReportingPeriods.require(period);
        List<Loan> active = loans.findAllByOrderByLoanCodeAsc().stream().filter(DashboardService::isActive).toList();
        List<Long> ids = active.stream().map(Loan::getId).toList();
        Map<Long, LoanContribution> contributions = overview.contributions(ids, period);

        Totals totals = new Totals();
        int positive = 0;
        int negative = 0;
        int breakEven = 0;
        int highRisk = 0;
        BigDecimal outstanding = MoneyRules.ZERO;
        int underWatch = 0;
        List<AttentionLoan> attention = new ArrayList<>();

        for (Loan loan : active) {
            outstanding = outstanding.add(loan.getOutstandingBalance());
            if (loan.getLoanStatus() == LoanStatus.UNDER_WATCH) {
                underWatch++;
            }
            LoanContribution c = contributions.get(loan.getId());
            boolean available = c != null && c.available();
            RiskRating rating = c == null ? null : c.riskRating();
            if (rating == RiskRating.HIGH) {
                highRisk++;
            }
            if (available) {
                totals.add(c.result());
                switch (c.result().status()) {
                    case POSITIVE -> positive++;
                    case NEGATIVE -> negative++;
                    case BREAK_EVEN -> breakEven++;
                }
            }

            List<String> reasons = new ArrayList<>();
            if (available && c.result().status() == ContributionStatus.NEGATIVE) {
                reasons.add("Negative estimated contribution");
            }
            if (rating == RiskRating.HIGH) {
                reasons.add("High risk rating");
            }
            if (loan.getLoanStatus() == LoanStatus.UNDER_WATCH) {
                reasons.add("Under watch");
            }
            if (!reasons.isEmpty()) {
                attention.add(new AttentionLoan(loan.getLoanCode(), loan.getCustomer().getCustomerCode(), loan.getCustomer().getFullName(),
                        loan.getLoanType(), loan.getLoanStatus(), loan.getOutstandingBalance(), rating,
                        available ? c.result().contributionAfterEcl() : null,
                        available ? c.result().status() : null, List.copyOf(reasons)));
            }
        }
        attention.sort(Comparator.comparing(AttentionLoan::contributionAfterEcl, Comparator.nullsLast(Comparator.naturalOrder())));

        return new DashboardSummaryResponse(period, active.size(), outstanding, totals.count, active.size() - totals.count,
                totals.income, totals.costs, totals.expenses, totals.before, totals.ecl, totals.after,
                positive, negative, breakEven, underWatch, highRisk, trend(ids), attention.stream().limit(MAX_ATTENTION_LOANS).toList());
    }

    /** Portfolio contribution for each reporting period that has data, oldest first. Nothing is estimated for missing periods. */
    private List<TrendPoint> trend(List<Long> activeIds) {
        List<String> ascending = periods.availablePeriods().stream().sorted().toList();
        List<TrendPoint> points = new ArrayList<>();
        for (String p : ascending) {
            Totals t = new Totals();
            overview.contributions(activeIds, p).values().stream().filter(LoanContribution::available).forEach(c -> t.add(c.result()));
            if (t.count > 0) {
                points.add(new TrendPoint(p, t.count, t.before, t.ecl, t.after));
            }
        }
        return points;
    }

    private static boolean isActive(Loan loan) {
        return loan.getLoanStatus() == LoanStatus.ACTIVE || loan.getLoanStatus() == LoanStatus.UNDER_WATCH;
    }

    private static final class Totals {
        int count;
        BigDecimal income = MoneyRules.ZERO;
        BigDecimal costs = MoneyRules.ZERO;
        BigDecimal expenses = MoneyRules.ZERO;
        BigDecimal before = MoneyRules.ZERO;
        BigDecimal ecl = MoneyRules.ZERO;
        BigDecimal after = MoneyRules.ZERO;

        void add(ProfitabilityCalculator.Result r) {
            count++;
            income = income.add(r.totalIncome());
            costs = costs.add(r.totalCostsBeforeEcl());
            expenses = expenses.add(r.totalExpenses());
            before = before.add(r.contributionBeforeEcl());
            ecl = ecl.add(r.expectedCreditLoss());
            after = after.add(r.contributionAfterEcl());
        }
    }
}
