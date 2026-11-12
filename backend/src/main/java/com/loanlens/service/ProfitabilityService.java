package com.loanlens.service;

import com.loanlens.dto.ProfitabilityResponse;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.dto.SnapshotSummaryResponse;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.entity.OperationalCostAllocation;
import com.loanlens.entity.ProfitabilitySnapshot;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.MissingInputsException;
import com.loanlens.mapper.CostAllocationMapper;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.repository.ProfitabilitySnapshotRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import com.loanlens.validation.ReportingPeriods;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates and stores a loan's estimated profitability for ONE reporting period.
 *
 * <ul>
 *   <li>Every input is looked up by (loan, exact period). Records from other periods are never used.</li>
 *   <li>Missing inputs are reported with a 422 listing what is missing. They are never replaced by zero.</li>
 *   <li>ECL comes from the risk assessment of the same period (PD x LGD x EAD).</li>
 *   <li>A snapshot is stored once per distinct set of inputs; repeating the request reuses it.</li>
 * </ul>
 *
 * <p>Simplified educational model: not a complete bank accounting or regulatory profitability calculation.
 */
@Service
public class ProfitabilityService {

    private final LoanService loanService;
    private final FinancialRecordRepository financialRecords;
    private final RiskAssessmentRepository riskAssessments;
    private final OperationalCostAllocationRepository allocations;
    private final ProfitabilitySnapshotRepository snapshots;
    private final EclCalculator eclCalculator;
    private final ProfitabilityCalculator calculator;
    private final Clock clock;

    public ProfitabilityService(LoanService loanService, FinancialRecordRepository financialRecords,
                                RiskAssessmentRepository riskAssessments, OperationalCostAllocationRepository allocations,
                                ProfitabilitySnapshotRepository snapshots, EclCalculator eclCalculator,
                                ProfitabilityCalculator calculator, Clock clock) {
        this.loanService = loanService;
        this.financialRecords = financialRecords;
        this.riskAssessments = riskAssessments;
        this.allocations = allocations;
        this.snapshots = snapshots;
        this.eclCalculator = eclCalculator;
        this.calculator = calculator;
        this.clock = clock;
    }

    /** Everything the calculation used, so callers (snapshot writer, AI explanation) work from one authoritative result. */
    public record Evaluation(Loan loan, String period, FinancialRecord record, RiskAssessment risk,
                             List<OperationalCostAllocation> allocationLines, ProfitabilityCalculator.Result result) {
    }

    /**
     * READ-ONLY calculation: looks up the loan's inputs for exactly this period, validates them and calculates.
     * It writes nothing (no snapshot), so it is safe to call for explanations and previews.
     */
    @Transactional(readOnly = true)
    public Evaluation evaluate(String loanIdOrCode, String period) {
        ReportingPeriods.require(period);
        return evaluate(loanService.requireLoan(loanIdOrCode), period);
    }

    /**
     * Same as {@link #evaluate(String, String)} but returns empty instead of throwing when the period has no financial
     * record or risk assessment, or when the cost breakdown does not add up. For optional comparisons (e.g. the previous
     * period) where "not available" is an ordinary answer. Nothing is substituted for the missing data.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Evaluation> findEvaluation(Loan loan, String period) {
        ReportingPeriods.require(period);
        FinancialRecord record = financialRecords.findByLoanIdAndReportingPeriod(loan.getId(), period).orElse(null);
        RiskAssessment risk = riskAssessments.findByLoanIdAndReportingPeriod(loan.getId(), period).orElse(null);
        if (record == null || risk == null) {
            return java.util.Optional.empty();
        }
        List<OperationalCostAllocation> lines = allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(loan.getId(), period);
        if (!lines.isEmpty() && lines.stream().map(OperationalCostAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(record.getOperationalCost()) != 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(build(loan, period, record, risk, lines));
    }

    private Evaluation evaluate(Loan loan, String period) {
        FinancialRecord record = financialRecords.findByLoanIdAndReportingPeriod(loan.getId(), period).orElse(null);
        RiskAssessment risk = riskAssessments.findByLoanIdAndReportingPeriod(loan.getId(), period).orElse(null);
        requireInputs(loan, period, record, risk);

        List<OperationalCostAllocation> lines = allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(loan.getId(), period);
        requireAllocationsToMatch(lines, record);
        return build(loan, period, record, risk, lines);
    }

    private Evaluation build(Loan loan, String period, FinancialRecord record, RiskAssessment risk,
                             List<OperationalCostAllocation> lines) {
        BigDecimal ecl = eclCalculator.calculate(risk.getProbabilityOfDefault(), risk.getLossGivenDefault(), risk.getExposureAtDefault());
        ProfitabilityCalculator.Result result = calculator.calculate(new ProfitabilityCalculator.Inputs(
                record.getInterestIncome(), record.getFeeIncome(), record.getFundingCost(), record.getOperationalCost(), ecl));
        return new Evaluation(loan, period, record, risk, lines, result);
    }

    /** Calculates (via {@link #evaluate}) and stores a snapshot unless this exact calculation is already stored. */
    @Transactional
    public ProfitabilityResponse calculate(String loanIdOrCode, String period) {
        Evaluation e = evaluate(loanIdOrCode, period);
        Loan loan = e.loan();
        FinancialRecord record = e.record();
        RiskAssessment risk = e.risk();
        ProfitabilityCalculator.Result result = e.result();

        String fingerprint = InputFingerprint.of(ProfitabilityCalculator.VERSION, loan.getLoanCode(), period,
                result.interestIncome(), result.feeIncome(), result.fundingCost(), result.operationalCost(),
                risk.getProbabilityOfDefault(), risk.getLossGivenDefault(), risk.getExposureAtDefault());

        // The database decides whether this exact calculation is already stored, so concurrent identical
        // requests cannot create duplicates.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int inserted = snapshots.insertIfAbsent(loan.getId(), period, result.interestIncome(), result.feeIncome(),
                result.totalIncome(), result.fundingCost(), result.operationalCost(), result.contributionBeforeEcl(),
                result.expectedCreditLoss(), result.contributionAfterEcl(), ProfitabilityCalculator.VERSION, fingerprint, now);
        ProfitabilitySnapshot snapshot = snapshots
                .findByLoanIdAndReportingPeriodAndCalculationVersionAndInputFingerprint(loan.getId(), period, ProfitabilityCalculator.VERSION, fingerprint)
                .orElseThrow(() -> new IllegalStateException("Snapshot was not found after insert-if-absent"));

        return toResponse(loan, period, record, risk, e.allocationLines(), result, snapshot, inserted == 1);
    }

    /** Stored calculation history for a loan and period, newest first. */
    @Transactional(readOnly = true)
    public List<SnapshotSummaryResponse> history(String loanIdOrCode, String period) {
        ReportingPeriods.require(period);
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return snapshots.findByLoanIdAndReportingPeriodOrderByCalculatedAtDescIdDesc(loan.getId(), period).stream()
                .map(s -> new SnapshotSummaryResponse(s.getId(), s.getReportingPeriod(), s.getTotalIncome(), s.getFundingCost(),
                        s.getOperationalCost(), s.getContributionBeforeEcl(), s.getExpectedCreditLoss(), s.getContributionAfterEcl(),
                        s.getCalculationVersion(), s.getCalculatedAt()))
                .toList();
    }

    private void requireInputs(Loan loan, String period, FinancialRecord record, RiskAssessment risk) {
        List<String> missing = new ArrayList<>();
        if (record == null) {
            missing.add("Financial record for reporting period " + period + " (POST /api/loans/" + loan.getLoanCode() + "/financial-records)");
        }
        if (risk == null) {
            missing.add("Risk assessment for reporting period " + period + " (POST /api/loans/" + loan.getLoanCode() + "/risk-assessments)");
        }
        if (!missing.isEmpty()) {
            throw new MissingInputsException("Cannot calculate profitability for loan " + loan.getLoanCode()
                    + " and reporting period " + period + " because required inputs are missing.", missing);
        }
    }

    /** If a cost breakdown was recorded it must add up to the operational cost used in the calculation. */
    private void requireAllocationsToMatch(List<OperationalCostAllocation> lines, FinancialRecord record) {
        if (lines.isEmpty()) {
            return;
        }
        BigDecimal total = lines.stream().map(OperationalCostAllocation::getAllocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(record.getOperationalCost()) != 0) {
            throw new BusinessRuleException("The operational cost allocations total " + total.toPlainString()
                    + " but the financial record's operational cost is " + record.getOperationalCost().toPlainString()
                    + ". Correct one of them before calculating.");
        }
    }

    private ProfitabilityResponse toResponse(Loan loan, String period, FinancialRecord record, RiskAssessment risk,
                                             List<OperationalCostAllocation> lines, ProfitabilityCalculator.Result r,
                                             ProfitabilitySnapshot snapshot, boolean newlyCreated) {
        return new ProfitabilityResponse(
                new ProfitabilityResponse.LoanInfo(loan.getId(), loan.getLoanCode(), loan.getCustomer().getCustomerCode(),
                        loan.getCustomer().getFullName(), loan.getLoanType(), loan.getLoanStatus(), loan.getOriginalPrincipal(),
                        loan.getOutstandingBalance(), loan.getInterestRate()),
                period,
                r.interestIncome(), r.feeIncome(), r.totalIncome(),
                record.getAverageOutstandingBalance(), record.getFundingRate(), r.fundingCost(), r.operationalCost(), r.totalCostsBeforeEcl(), r.contributionBeforeEcl(),
                new ProfitabilityResponse.RiskInfo(risk.getProbabilityOfDefault(), risk.getLossGivenDefault(), risk.getExposureAtDefault(), risk.getRiskRating()),
                r.expectedCreditLoss(), r.totalExpenses(), r.contributionAfterEcl(), r.status(),
                lines.stream().map(CostAllocationMapper::toLine).toList(),
                new ProfitabilityResponse.SnapshotInfo(snapshot.getId(), snapshot.getCalculationVersion(), snapshot.getCalculatedAt(), newlyCreated),
                notes(r, lines.isEmpty()));
    }

    private static List<String> notes(ProfitabilityCalculator.Result r, boolean noAllocationLines) {
        List<String> notes = new ArrayList<>();
        notes.add("Simplified educational model, not a complete bank accounting or regulatory profitability calculation.");
        notes.add("Expected Credit Loss is an ESTIMATE of potential future credit loss (PD x LGD x EAD). It is not a realised loss.");
        if (r.status() == ContributionStatus.NEGATIVE) {
            notes.add(r.contributionBeforeEcl().signum() >= 0
                    ? "The contribution is negative only after the estimated credit loss. This does not mean the borrower has defaulted "
                    + "or that the bank has already lost this amount."
                    : "The contribution is negative even before the estimated credit loss: income does not cover funding and operational costs.");
        }
        if (noAllocationLines) {
            notes.add("No operational cost allocation lines are recorded for this period; the operational cost total comes from the financial record.");
        }
        return List.copyOf(notes);
    }
}
