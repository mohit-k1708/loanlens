package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.loanlens.dto.DashboardSummaryResponse;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.Customer;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import com.loanlens.exception.BadRequestException;
import com.loanlens.repository.LoanRepository;
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

/** The dashboard only adds up the per-loan results it is given; these tests check totals, counts and that nothing is invented. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ProfitabilityCalculator CALC = new ProfitabilityCalculator();

    @Mock LoanRepository loans;
    @Mock ProfitabilityOverviewService overview;
    @Mock ReportingPeriodService periods;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(loans, overview, periods);
        lenient().when(periods.availablePeriods()).thenReturn(List.of("2026"));
    }

    private static Loan loan(long id, String code, LoanStatus status, String outstanding) {
        Customer c = new Customer();
        c.setId(id);
        c.setCustomerCode("C" + id);
        c.setFullName("Customer " + id);
        Loan l = new Loan();
        l.setId(id);
        l.setLoanCode(code);
        l.setCustomer(c);
        l.setLoanType(LoanType.PERSONAL);
        l.setLoanStatus(status);
        l.setOutstandingBalance(new BigDecimal(outstanding));
        return l;
    }

    private static LoanContribution available(String interest, String fee, String funding, String ops, String ecl, RiskRating rating) {
        return new LoanContribution(true, List.of(), CALC.calculate(new ProfitabilityCalculator.Inputs(
                new BigDecimal(interest), new BigDecimal(fee), new BigDecimal(funding), new BigDecimal(ops), new BigDecimal(ecl))), rating);
    }

    @Test
    @DisplayName("totals are the sums of the loans' own results; Rahul's -10,000 plus Priya's +60,390")
    void totalsAreSumsOfLoanResults() {
        Loan rahul = loan(1, "L1001", LoanStatus.ACTIVE, "500000.00");
        Loan priya = loan(2, "L1002", LoanStatus.ACTIVE, "4120000.00");
        when(loans.findAllByOrderByLoanCodeAsc()).thenReturn(List.of(rahul, priya));
        when(overview.contributions(anyCollection(), eq("2026"))).thenReturn(Map.of(
                1L, available("60000", "2000", "35000", "7000", "30000", RiskRating.HIGH),
                2L, available("372400", "9000", "297500", "11150", "12360", RiskRating.LOW)));

        DashboardSummaryResponse d = service.summary("2026");

        assertThat(d.totalActiveLoans()).isEqualTo(2);
        assertThat(d.totalOutstandingBalance()).isEqualByComparingTo("4620000.00");
        assertThat(d.loansWithData()).isEqualTo(2);
        assertThat(d.loansWithoutData()).isZero();
        assertThat(d.totalIncome()).isEqualByComparingTo("443400.00");          // 62,000 + 381,400
        assertThat(d.contributionBeforeEcl()).isEqualByComparingTo("92750.00"); // 20,000 + 72,750
        assertThat(d.expectedCreditLoss()).isEqualByComparingTo("42360.00");    // 30,000 + 12,360
        assertThat(d.contributionAfterEcl()).isEqualByComparingTo("50390.00");  // -10,000 + 60,390
        assertThat(d.contributionAfterEcl()).isEqualByComparingTo(d.contributionBeforeEcl().subtract(d.expectedCreditLoss()));
        assertThat(d.positiveContributionLoans()).isEqualTo(1);
        assertThat(d.negativeContributionLoans()).isEqualTo(1);
        assertThat(d.breakEvenLoans()).isZero();
        assertThat(d.highRiskLoans()).isEqualTo(1);
        assertThat(d.attentionLoans()).singleElement().satisfies(a -> {
            assertThat(a.loanCode()).isEqualTo("L1001");
            assertThat(a.contributionAfterEcl()).isEqualByComparingTo("-10000.00");
            assertThat(a.reasons()).containsExactly("Negative estimated contribution", "High risk rating");
        });
    }

    @Test
    @DisplayName("a loan with missing inputs is counted separately and adds nothing (not zero) to the totals")
    void missingDataIsNotZero() {
        Loan rahul = loan(1, "L1001", LoanStatus.ACTIVE, "500000.00");
        Loan noData = loan(2, "L1002", LoanStatus.ACTIVE, "4120000.00");
        when(loans.findAllByOrderByLoanCodeAsc()).thenReturn(List.of(rahul, noData));
        when(overview.contributions(anyCollection(), anyString())).thenReturn(Map.of(
                1L, available("60000", "2000", "35000", "7000", "30000", RiskRating.HIGH),
                2L, new LoanContribution(false, List.of("FINANCIAL_RECORD", "RISK_ASSESSMENT"), null, null)));

        DashboardSummaryResponse d = service.summary("2026");

        assertThat(d.totalActiveLoans()).isEqualTo(2);
        assertThat(d.loansWithData()).isEqualTo(1);
        assertThat(d.loansWithoutData()).isEqualTo(1);
        assertThat(d.contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        assertThat(d.totalOutstandingBalance()).isEqualByComparingTo("4620000.00"); // balance counts even without period data
    }

    @Test
    @DisplayName("break-even, closed and under-watch loans are classified correctly")
    void classification() {
        Loan breakEven = loan(1, "L1001", LoanStatus.UNDER_WATCH, "1000.00");
        Loan closed = loan(2, "L1002", LoanStatus.CLOSED, "0.00");
        when(loans.findAllByOrderByLoanCodeAsc()).thenReturn(List.of(breakEven, closed));
        when(overview.contributions(anyCollection(), anyString())).thenReturn(Map.of(
                1L, available("100", "0", "60", "10", "30", RiskRating.MEDIUM)));

        DashboardSummaryResponse d = service.summary("2026");

        assertThat(d.totalActiveLoans()).isEqualTo(1);             // closed loan excluded
        assertThat(d.breakEvenLoans()).isEqualTo(1);
        assertThat(d.underWatchLoans()).isEqualTo(1);
        assertThat(d.attentionLoans()).singleElement().satisfies(a -> {
            assertThat(a.contributionStatus()).isEqualTo(ContributionStatus.BREAK_EVEN);
            assertThat(a.reasons()).containsExactly("Under watch");
        });
    }

    @Test
    void emptyPortfolioHasZeroTotalsNotErrors() {
        when(loans.findAllByOrderByLoanCodeAsc()).thenReturn(List.of());
        when(overview.contributions(anyCollection(), anyString())).thenReturn(Map.of());
        when(periods.availablePeriods()).thenReturn(List.of());

        DashboardSummaryResponse d = service.summary("2026");

        assertThat(d.totalActiveLoans()).isZero();
        assertThat(d.contributionAfterEcl()).isEqualByComparingTo("0.00");
        assertThat(d.contributionTrend()).isEmpty();
        assertThat(d.attentionLoans()).isEmpty();
    }

    @Test
    @DisplayName("the trend has one point per period with data; each period uses its own contributions (periods are never mixed)")
    void trendKeepsPeriodsSeparate() {
        Loan rahul = loan(1, "L1001", LoanStatus.ACTIVE, "500000.00");
        when(loans.findAllByOrderByLoanCodeAsc()).thenReturn(List.of(rahul));
        when(periods.availablePeriods()).thenReturn(List.of("2026", "2025"));
        when(overview.contributions(anyCollection(), eq("2026")))
                .thenReturn(Map.of(1L, available("60000", "2000", "35000", "7000", "30000", RiskRating.HIGH)));
        when(overview.contributions(anyCollection(), eq("2025")))
                .thenReturn(Map.of(1L, available("50000", "1000", "35000", "7000", "15000", RiskRating.MEDIUM)));

        DashboardSummaryResponse d = service.summary("2026");

        assertThat(d.contributionTrend()).hasSize(2);
        assertThat(d.contributionTrend().get(0).reportingPeriod()).isEqualTo("2025");
        assertThat(d.contributionTrend().get(0).contributionAfterEcl()).isEqualByComparingTo("-6000.00");
        assertThat(d.contributionTrend().get(1).reportingPeriod()).isEqualTo("2026");
        assertThat(d.contributionTrend().get(1).contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        assertThat(d.contributionAfterEcl()).isEqualByComparingTo("-10000.00"); // headline = requested period only
    }

    @Test
    void invalidPeriodIsABadRequest() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.summary("26")).isInstanceOf(BadRequestException.class);
    }
}
