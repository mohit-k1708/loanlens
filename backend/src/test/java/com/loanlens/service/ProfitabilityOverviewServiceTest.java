package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.repository.OperationalCostAllocationRepository.LoanAllocationTotal;
import com.loanlens.repository.RiskAssessmentRepository;
import com.loanlens.service.ProfitabilityOverviewService.LoanContribution;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfitabilityOverviewServiceTest {

    @Mock FinancialRecordRepository records;
    @Mock RiskAssessmentRepository risks;
    @Mock OperationalCostAllocationRepository allocations;

    private ProfitabilityOverviewService service;

    @BeforeEach
    void setUp() {
        service = new ProfitabilityOverviewService(records, risks, allocations, new EclCalculator(), new ProfitabilityCalculator());
    }

    private static Loan loan(long id) {
        Loan l = new Loan();
        l.setId(id);
        return l;
    }

    private static FinancialRecord record(Loan loan, String operational) {
        FinancialRecord r = new FinancialRecord();
        r.setLoan(loan);
        r.setReportingPeriod("2026");
        r.setInterestIncome(new BigDecimal("60000.00"));
        r.setFeeIncome(new BigDecimal("2000.00"));
        r.setFundingCost(new BigDecimal("35000.00"));
        r.setOperationalCost(new BigDecimal(operational));
        return r;
    }

    private static RiskAssessment risk(Loan loan) {
        RiskAssessment a = new RiskAssessment();
        a.setLoan(loan);
        a.setReportingPeriod("2026");
        a.setProbabilityOfDefault(new BigDecimal("0.10"));
        a.setLossGivenDefault(new BigDecimal("0.60"));
        a.setExposureAtDefault(new BigDecimal("500000.00"));
        a.setRiskRating(RiskRating.HIGH);
        return a;
    }

    private static LoanAllocationTotal total(long loanId, String amount) {
        LoanAllocationTotal t = mock(LoanAllocationTotal.class);
        when(t.getLoanId()).thenReturn(loanId);
        when(t.getTotal()).thenReturn(new BigDecimal(amount));
        return t;
    }

    @Test
    @DisplayName("Rahul: the list/dashboard calculation gives -10,000 (same as the detail endpoint)")
    void rahul() {
        Loan l = loan(1);
        when(records.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(record(l, "7000.00")));
        when(risks.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(risk(l)));
        List<LoanAllocationTotal> totals = List.of(total(1, "7000.00"));
        when(allocations.totalsForLoansInPeriod("2026", List.of(1L))).thenReturn(totals);

        LoanContribution c = service.contributions(List.of(1L), "2026").get(1L);

        assertThat(c.available()).isTrue();
        assertThat(c.result().contributionBeforeEcl()).isEqualByComparingTo("20000.00");
        assertThat(c.result().expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(c.result().contributionAfterEcl()).isEqualByComparingTo("-10000.00");
    }

    @Test
    @DisplayName("a loan with no cost breakdown at all is still calculated (the breakdown is optional)")
    void noBreakdownIsFine() {
        Loan l = loan(1);
        when(records.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(record(l, "7000.00")));
        when(risks.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(risk(l)));
        when(allocations.totalsForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of());

        assertThat(service.contributions(List.of(1L), "2026").get(1L).available()).isTrue();
    }

    @Test
    @DisplayName("a breakdown that does not add up is unavailable here exactly where the detail endpoint answers 422")
    void allocationMismatchIsUnavailable() {
        Loan l = loan(1);
        when(records.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(record(l, "7000.00")));
        when(risks.findForLoansInPeriod("2026", List.of(1L))).thenReturn(List.of(risk(l)));
        List<LoanAllocationTotal> totals = List.of(total(1, "6500.00"));
        when(allocations.totalsForLoansInPeriod("2026", List.of(1L))).thenReturn(totals);

        LoanContribution c = service.contributions(List.of(1L), "2026").get(1L);

        assertThat(c.available()).isFalse();
        assertThat(c.result()).isNull();
        assertThat(c.missingInputs()).containsExactly(ProfitabilityOverviewService.COST_ALLOCATION_MISMATCH);
        assertThat(c.riskRating()).isEqualTo(RiskRating.HIGH);
    }

    @Test
    @DisplayName("missing inputs are named and never zero; the rating is still reported when an assessment exists")
    void missingInputs() {
        Loan a = loan(1);
        Loan b = loan(2);
        Loan none = loan(3);
        when(records.findForLoansInPeriod("2026", List.of(1L, 2L, 3L))).thenReturn(List.of(record(a, "7000.00")));
        when(risks.findForLoansInPeriod("2026", List.of(1L, 2L, 3L))).thenReturn(List.of(risk(b)));
        when(allocations.totalsForLoansInPeriod("2026", List.of(1L, 2L, 3L))).thenReturn(List.of());

        Map<Long, LoanContribution> result = service.contributions(List.of(1L, 2L, 3L), "2026");

        assertThat(result.get(1L).missingInputs()).containsExactly(ProfitabilityOverviewService.RISK_ASSESSMENT);
        assertThat(result.get(2L).missingInputs()).containsExactly(ProfitabilityOverviewService.FINANCIAL_RECORD);
        assertThat(result.get(2L).riskRating()).isEqualTo(RiskRating.HIGH);
        assertThat(result.get(3L).missingInputs()).containsExactly(ProfitabilityOverviewService.FINANCIAL_RECORD, ProfitabilityOverviewService.RISK_ASSESSMENT);
        assertThat(none.getId()).isEqualTo(3L);
        assertThat(result.values()).noneMatch(LoanContribution::available);
    }

    @Test
    void noLoansNoQueries() {
        assertThat(service.contributions(List.of(), "2026")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(records, risks, allocations);
    }
}
