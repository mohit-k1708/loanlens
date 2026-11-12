package com.loanlens.service;

import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * READ-ONLY profitability for many loans at once (list rows and the dashboard).
 *
 * <p>It uses the very same {@link EclCalculator} and {@link ProfitabilityCalculator} as the single-loan endpoint, so a
 * loan's figures can never differ between the list, the dashboard and its detail page. Unlike
 * {@code GET /loans/{id}/profitability} it stores NO snapshots (browsing a list must not write data). It applies the
 * same allocation-reconciliation rule: a loan whose recorded cost breakdown does not add up to its operational cost is
 * reported as unavailable ({@link #COST_ALLOCATION_MISMATCH}), exactly where the detail endpoint answers 422.
 *
 * <p>A loan lacking its financial record and/or risk assessment for the period is reported as unavailable with the
 * missing items named; it is never given zero figures.
 */
@Service
@Transactional(readOnly = true)
public class ProfitabilityOverviewService {

    public static final String FINANCIAL_RECORD = "FINANCIAL_RECORD";
    public static final String RISK_ASSESSMENT = "RISK_ASSESSMENT";
    /** The recorded operational cost breakdown does not add up to the financial record's operational cost. */
    public static final String COST_ALLOCATION_MISMATCH = "COST_ALLOCATION_MISMATCH";

    /** {@code result} is non-null only when {@code available}. {@code riskRating} is set whenever an assessment exists. */
    public record LoanContribution(boolean available, List<String> missingInputs,
                                   ProfitabilityCalculator.Result result, RiskRating riskRating) {
    }

    private final FinancialRecordRepository financialRecords;
    private final RiskAssessmentRepository riskAssessments;
    private final OperationalCostAllocationRepository allocations;
    private final EclCalculator eclCalculator;
    private final ProfitabilityCalculator calculator;

    public ProfitabilityOverviewService(FinancialRecordRepository financialRecords, RiskAssessmentRepository riskAssessments,
                                        OperationalCostAllocationRepository allocations,
                                        EclCalculator eclCalculator, ProfitabilityCalculator calculator) {
        this.financialRecords = financialRecords;
        this.riskAssessments = riskAssessments;
        this.allocations = allocations;
        this.eclCalculator = eclCalculator;
        this.calculator = calculator;
    }

    public Map<Long, LoanContribution> contributions(Collection<Long> loanIds, String period) {
        if (loanIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FinancialRecord> records = new HashMap<>();
        financialRecords.findForLoansInPeriod(period, loanIds).forEach(r -> records.put(r.getLoan().getId(), r));
        Map<Long, RiskAssessment> risks = new HashMap<>();
        riskAssessments.findForLoansInPeriod(period, loanIds).forEach(r -> risks.put(r.getLoan().getId(), r));
        Map<Long, BigDecimal> allocated = new HashMap<>();
        allocations.totalsForLoansInPeriod(period, loanIds).forEach(t -> allocated.put(t.getLoanId(), t.getTotal()));

        Map<Long, LoanContribution> result = new HashMap<>();
        for (Long id : loanIds) {
            FinancialRecord record = records.get(id);
            RiskAssessment risk = risks.get(id);
            RiskRating rating = risk == null ? null : risk.getRiskRating();
            if (record == null || risk == null) {
                List<String> missing = new java.util.ArrayList<>();
                if (record == null) {
                    missing.add(FINANCIAL_RECORD);
                }
                if (risk == null) {
                    missing.add(RISK_ASSESSMENT);
                }
                result.put(id, new LoanContribution(false, List.copyOf(missing), null, rating));
                continue;
            }
            // Same rule as GET /loans/{id}/profitability: a recorded breakdown must add up to the operational cost.
            BigDecimal breakdown = allocated.get(id);
            if (breakdown != null && breakdown.compareTo(record.getOperationalCost()) != 0) {
                result.put(id, new LoanContribution(false, List.of(COST_ALLOCATION_MISMATCH), null, rating));
                continue;
            }
            BigDecimal ecl = eclCalculator.calculate(risk.getProbabilityOfDefault(), risk.getLossGivenDefault(), risk.getExposureAtDefault());
            result.put(id, new LoanContribution(true, List.of(), calculator.calculate(new ProfitabilityCalculator.Inputs(
                    record.getInterestIncome(), record.getFeeIncome(), record.getFundingCost(), record.getOperationalCost(), ecl)), rating));
        }
        return result;
    }
}
