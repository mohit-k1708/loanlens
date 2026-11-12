package com.loanlens.ai;

import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.Customer;
import com.loanlens.entity.Loan;
import com.loanlens.entity.OperationalCostAllocation;
import com.loanlens.service.LoanService;
import com.loanlens.service.MoneyRules;
import com.loanlens.service.ProfitabilityCalculator;
import com.loanlens.service.ProfitabilityService;
import com.loanlens.service.ProfitabilityService.Evaluation;
import com.loanlens.validation.ReportingPeriods;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link FinancialContext} for an explanation from the AUTHORITATIVE backend calculation
 * ({@link ProfitabilityService#evaluate}, which is read-only). Nothing is estimated or defaulted here: a missing input
 * makes {@code evaluate} throw, and the caller turns that into an "insufficient information" answer.
 *
 * <p>Besides the loan's own figures it adds, all computed by the backend and never by the AI:
 * <ul>
 *   <li>the previous reporting period's figures and the change, when that period has complete data;</li>
 *   <li>funding-cost sensitivity (+10%, +25%, +50%), clearly hypothetical "what if" arithmetic, not a forecast;</li>
 *   <li>the same headline figures for an optional second loan in the same period.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FinancialContextService {

    /** Funding-cost increases offered for "what if funding costs increase" questions. */
    public static final List<Integer> FUNDING_STRESS_PERCENTS = List.of(10, 25, 50);

    private final ProfitabilityService profitability;
    private final LoanService loanService;
    private final ProfitabilityCalculator calculator;

    public FinancialContextService(ProfitabilityService profitability, LoanService loanService, ProfitabilityCalculator calculator) {
        this.profitability = profitability;
        this.loanService = loanService;
        this.calculator = calculator;
    }

    /**
     * @param compareToLoan optional second loan (id or code) for "why is one loan more profitable than another"
     * @throws com.loanlens.exception.ResourceNotFoundException       unknown loan
     * @throws com.loanlens.exception.MissingInputsException          the loan has no complete data for the period
     * @throws com.loanlens.exception.BusinessRuleException           the period's cost breakdown is inconsistent
     */
    public FinancialContext build(String loanIdOrCode, String period, String compareToLoan) {
        ReportingPeriods.require(period);
        Evaluation e = profitability.evaluate(loanIdOrCode, period);
        Loan loan = e.loan();
        ProfitabilityCalculator.Result r = e.result();

        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.rupees("interestIncome", "Interest income", r.interestIncome()));
        facts.add(Fact.rupees("feeIncome", "Fee income", r.feeIncome()));
        facts.add(Fact.rupees("totalIncome", "Total income (interest + fee)", r.totalIncome()));
        facts.add(Fact.rupees("fundingCost", "Funding cost", r.fundingCost()));
        facts.add(Fact.rupees("operationalCost", "Operational cost", r.operationalCost()));
        facts.add(Fact.rupees("totalCostsBeforeEcl", "Funding + operational cost", r.totalCostsBeforeEcl()));
        facts.add(Fact.rupees("contributionBeforeEcl", "Estimated contribution before ECL", r.contributionBeforeEcl()));
        facts.add(Fact.rupees("expectedCreditLoss", "Expected Credit Loss (ECL estimate)", r.expectedCreditLoss()));
        facts.add(Fact.rupees("contributionAfterEcl", "Estimated contribution after ECL", r.contributionAfterEcl()));
        facts.add(Fact.percentOfFraction("probabilityOfDefault", "Probability of default (PD)", e.risk().getProbabilityOfDefault()));
        facts.add(Fact.percentOfFraction("lossGivenDefault", "Loss given default (LGD)", e.risk().getLossGivenDefault()));
        facts.add(Fact.rupees("exposureAtDefault", "Exposure at default (EAD)", e.risk().getExposureAtDefault()));
        facts.add(Fact.percentOfFraction("fundingRate", "Funding rate (annual)", e.record().getFundingRate()));
        if (e.record().getAverageOutstandingBalance() != null) {
            facts.add(Fact.rupees("averageOutstandingBalance", "Average outstanding balance", e.record().getAverageOutstandingBalance()));
        }
        facts.add(Fact.rupees("originalPrincipal", "Original principal", loan.getOriginalPrincipal()));
        facts.add(Fact.rupees("outstandingBalance", "Outstanding balance", loan.getOutstandingBalance()));
        facts.add(Fact.percentOfFraction("interestRate", "Customer interest rate (annual)", loan.getInterestRate()));
        for (OperationalCostAllocation line : e.allocationLines()) {
            facts.add(Fact.rupees("allocation." + line.getCostCategory().name(),
                    "Operational cost allocated to " + line.getCostCategory().name().toLowerCase().replace('_', ' '), line.getAllocatedAmount()));
        }

        String previousPeriod = addPreviousPeriod(loan, period, r, e, facts);
        addFundingSensitivity(e, facts);
        String comparison = addComparison(compareToLoan, loan, period, facts);

        List<String> assumptions = new ArrayList<>(List.of(
                "Simplified educational model, not a complete bank accounting or regulatory calculation.",
                "Total income = interest income + fee income.",
                "Contribution before ECL = total income - funding cost - operational cost.",
                "Expected Credit Loss (ECL) = PD x LGD x EAD. It is an ESTIMATE of potential credit loss, not a loss that has already occurred.",
                "Contribution after ECL = contribution before ECL - ECL.",
                "Reporting period " + period + " is one calendar year; all figures are for that period only."));
        if (e.record().getAverageOutstandingBalance() == null) {
            assumptions.add("No average outstanding balance was recorded for this period, so the funding cost could not be cross-checked against balance x rate.");
        }
        if (e.allocationLines().isEmpty()) {
            assumptions.add("No operational cost breakdown by activity is recorded; the operational cost total comes from the financial record.");
        }

        Customer customer = loan.getCustomer();
        List<String> sensitive = new ArrayList<>();
        if (customer != null) {
            addTerm(sensitive, customer.getFullName());
            for (String part : customer.getFullName() == null ? new String[0] : customer.getFullName().split("\\s+")) {
                addTerm(sensitive, part);
            }
            addTerm(sensitive, customer.getCustomerCode());
            addTerm(sensitive, customer.getEmail());
            addTerm(sensitive, customer.getPhone());
        }

        return new FinancialContext(loan.getLoanCode(), period, loan.getLoanType().name(), loan.getLoanStatus().name(),
                e.risk().getRiskRating().name(), r.status().name(), facts, assumptions, previousPeriod, comparison, sensitive);
    }

    private String addPreviousPeriod(Loan loan, String period, ProfitabilityCalculator.Result current, Evaluation currentEval, List<Fact> facts) {
        int year = Integer.parseInt(period);
        if (year <= 2000) {
            return null;
        }
        String previous = String.valueOf(year - 1);
        Optional<Evaluation> prev = profitability.findEvaluation(loan, previous);
        if (prev.isEmpty()) {
            return null;
        }
        ProfitabilityCalculator.Result p = prev.get().result();
        facts.add(Fact.rupees("previous.totalIncome", "Total income, " + previous, p.totalIncome()));
        facts.add(Fact.rupees("previous.contributionBeforeEcl", "Contribution before ECL, " + previous, p.contributionBeforeEcl()));
        facts.add(Fact.rupees("previous.expectedCreditLoss", "ECL estimate, " + previous, p.expectedCreditLoss()));
        facts.add(Fact.rupees("previous.contributionAfterEcl", "Contribution after ECL, " + previous, p.contributionAfterEcl()));
        facts.add(Fact.percentOfFraction("previous.probabilityOfDefault", "PD, " + previous, prev.get().risk().getProbabilityOfDefault()));
        facts.add(Fact.percentOfFraction("previous.lossGivenDefault", "LGD, " + previous, prev.get().risk().getLossGivenDefault()));
        facts.add(Fact.rupees("previous.exposureAtDefault", "EAD, " + previous, prev.get().risk().getExposureAtDefault()));
        facts.add(Fact.rupees("change.expectedCreditLoss", "Change in ECL estimate, " + previous + " to " + period,
                current.expectedCreditLoss().subtract(p.expectedCreditLoss())));
        facts.add(Fact.rupees("change.contributionAfterEcl", "Change in contribution after ECL, " + previous + " to " + period,
                current.contributionAfterEcl().subtract(p.contributionAfterEcl())));
        return previous;
    }

    /** Hypothetical arithmetic: funding cost x (1 + p%), everything else unchanged. Rounded once (HALF_UP). */
    private void addFundingSensitivity(Evaluation e, List<Fact> facts) {
        ProfitabilityCalculator.Result base = e.result();
        for (int pct : FUNDING_STRESS_PERCENTS) {
            BigDecimal stressedFunding = MoneyRules.round(base.fundingCost().multiply(BigDecimal.valueOf(100 + pct)).divide(BigDecimal.valueOf(100)));
            ProfitabilityCalculator.Result what = calculator.calculate(new ProfitabilityCalculator.Inputs(
                    base.interestIncome(), base.feeIncome(), stressedFunding, base.operationalCost(), base.expectedCreditLoss()));
            String tag = "whatIfFundingPlus" + pct + ".";
            String note = " if funding cost were " + pct + "% higher (hypothetical)";
            facts.add(Fact.rupees(tag + "fundingCost", "Funding cost" + note, what.fundingCost()));
            facts.add(Fact.rupees(tag + "contributionBeforeEcl", "Contribution before ECL" + note, what.contributionBeforeEcl()));
            facts.add(Fact.rupees(tag + "contributionAfterEcl", "Contribution after ECL" + note, what.contributionAfterEcl()));
        }
    }

    private String addComparison(String compareToLoan, Loan loan, String period, List<Fact> facts) {
        if (compareToLoan == null || compareToLoan.isBlank()) {
            return null;
        }
        Loan other = loanService.requireLoan(compareToLoan);
        if (other.getId().equals(loan.getId())) {
            return null;
        }
        Optional<Evaluation> eval = profitability.findEvaluation(other, period);
        if (eval.isEmpty()) {
            return null;
        }
        ProfitabilityCalculator.Result o = eval.get().result();
        String code = other.getLoanCode();
        facts.add(Fact.rupees("other.totalIncome", "Other loan " + code + ": total income", o.totalIncome()));
        facts.add(Fact.rupees("other.fundingCost", "Other loan " + code + ": funding cost", o.fundingCost()));
        facts.add(Fact.rupees("other.operationalCost", "Other loan " + code + ": operational cost", o.operationalCost()));
        facts.add(Fact.rupees("other.contributionBeforeEcl", "Other loan " + code + ": contribution before ECL", o.contributionBeforeEcl()));
        facts.add(Fact.rupees("other.expectedCreditLoss", "Other loan " + code + ": ECL estimate", o.expectedCreditLoss()));
        facts.add(Fact.rupees("other.contributionAfterEcl", "Other loan " + code + ": contribution after ECL", o.contributionAfterEcl()));
        facts.add(Fact.rupees("other.outstandingBalance", "Other loan " + code + ": outstanding balance", other.getOutstandingBalance()));
        return code;
    }

    private static void addTerm(List<String> terms, String term) {
        if (term != null && term.trim().length() >= 3 && !terms.contains(term.trim())) {
            terms.add(term.trim());
        }
    }

    /** Exposed for the explainer: the status as the enum. */
    static ContributionStatus statusOf(FinancialContext context) {
        return ContributionStatus.valueOf(context.contributionStatus());
    }
}
