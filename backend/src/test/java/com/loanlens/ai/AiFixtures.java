package com.loanlens.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loanlens.entity.Customer;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import com.loanlens.service.EclCalculator;
import com.loanlens.service.LoanService;
import com.loanlens.service.ProfitabilityCalculator;
import com.loanlens.service.ProfitabilityService;
import com.loanlens.service.ProfitabilityService.Evaluation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Builds real {@link FinancialContext}s (via the real {@link FinancialContextService}) from in-memory entities, no database. */
final class AiFixtures {

    static final String RAHUL_NAME = "Rahul Sharma";
    static final String RAHUL_EMAIL = "rahul.sharma@example.com";
    static final String RAHUL_PHONE = "+91 9000000001";
    static final String RAHUL_CODE = "C2001";

    private static final EclCalculator ECL = new EclCalculator();
    private static final ProfitabilityCalculator CALC = new ProfitabilityCalculator();

    private AiFixtures() {
    }

    static Customer customer(long id, String code, String name, String email, String phone) {
        Customer c = new Customer();
        c.setId(id);
        c.setCustomerCode(code);
        c.setFullName(name);
        c.setEmail(email);
        c.setPhone(phone);
        return c;
    }

    static Loan loan(long id, String code, Customer customer, String principal, String outstanding, String rate) {
        Loan l = new Loan();
        l.setId(id);
        l.setLoanCode(code);
        l.setCustomer(customer);
        l.setLoanType(LoanType.PERSONAL);
        l.setLoanStatus(LoanStatus.ACTIVE);
        l.setOriginalPrincipal(new BigDecimal(principal));
        l.setOutstandingBalance(new BigDecimal(outstanding));
        l.setInterestRate(new BigDecimal(rate));
        l.setStartDate(LocalDate.of(2025, 4, 1));
        l.setMaturityDate(LocalDate.of(2030, 4, 1));
        return l;
    }

    static Evaluation evaluation(Loan loan, String period, String interest, String fee, String funding, String ops,
                                 String pd, String lgd, String ead, String avgBalance, String fundingRate) {
        FinancialRecord r = new FinancialRecord();
        r.setLoan(loan);
        r.setReportingPeriod(period);
        r.setInterestIncome(new BigDecimal(interest));
        r.setFeeIncome(new BigDecimal(fee));
        r.setFundingCost(new BigDecimal(funding));
        r.setOperationalCost(new BigDecimal(ops));
        r.setAverageOutstandingBalance(avgBalance == null ? null : new BigDecimal(avgBalance));
        r.setFundingRate(new BigDecimal(fundingRate));
        RiskAssessment a = new RiskAssessment();
        a.setLoan(loan);
        a.setReportingPeriod(period);
        a.setProbabilityOfDefault(new BigDecimal(pd));
        a.setLossGivenDefault(new BigDecimal(lgd));
        a.setExposureAtDefault(new BigDecimal(ead));
        a.setRiskRating(new BigDecimal(pd).compareTo(new BigDecimal("0.08")) >= 0 ? RiskRating.HIGH
                : new BigDecimal(pd).compareTo(new BigDecimal("0.03")) >= 0 ? RiskRating.MEDIUM : RiskRating.LOW);
        BigDecimal ecl = ECL.calculate(a.getProbabilityOfDefault(), a.getLossGivenDefault(), a.getExposureAtDefault());
        ProfitabilityCalculator.Result result = CALC.calculate(new ProfitabilityCalculator.Inputs(
                r.getInterestIncome(), r.getFeeIncome(), r.getFundingCost(), r.getOperationalCost(), ecl));
        return new Evaluation(loan, period, r, a, List.of(), result);
    }

    /**
     * Rahul / L1001 / 2026 (the brief's reference scenario). Optionally with 2025 data and a comparison loan (Priya / L1002).
     */
    static FinancialContext rahul(boolean withPreviousPeriod, boolean withComparisonLoan) {
        ProfitabilityService profitability = mock(ProfitabilityService.class);
        LoanService loanService = mock(LoanService.class);
        Customer rahul = customer(1, RAHUL_CODE, RAHUL_NAME, RAHUL_EMAIL, RAHUL_PHONE);
        Loan loan = loan(10, "L1001", rahul, "500000.00", "500000.00", "0.100000");
        when(profitability.evaluate("L1001", "2026")).thenReturn(
                evaluation(loan, "2026", "60000.00", "2000.00", "35000.00", "7000.00", "0.10", "0.60", "500000.00", "500000.00", "0.07"));
        when(profitability.findEvaluation(loan, "2025")).thenReturn(withPreviousPeriod
                ? Optional.of(evaluation(loan, "2025", "50000.00", "1000.00", "35000.00", "7000.00", "0.05", "0.60", "500000.00", "500000.00", "0.07"))
                : Optional.empty());
        if (withComparisonLoan) {
            Customer priya = customer(2, "C2002", "Priya Nair", "priya.nair@example.com", "+91 9000000002");
            Loan other = loan(11, "L1002", priya, "4500000.00", "4120000.00", "0.0875");
            when(loanService.requireLoan("L1002")).thenReturn(other);
            when(profitability.findEvaluation(other, "2026")).thenReturn(Optional.of(
                    evaluation(other, "2026", "372400.00", "9000.00", "297500.00", "11150.00", "0.012", "0.25", "4120000.00", "4250000.00", "0.07")));
        }
        FinancialContextService service = new FinancialContextService(profitability, loanService, new ProfitabilityCalculator());
        return service.build("L1001", "2026", withComparisonLoan ? "L1002" : null);
    }

    static FinancialContext rahul() {
        return rahul(false, false);
    }
}
