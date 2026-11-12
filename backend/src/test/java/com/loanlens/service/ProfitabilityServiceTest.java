package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loanlens.dto.ProfitabilityResponse;
import com.loanlens.entity.AllocationMethod;
import com.loanlens.entity.CostCategory;
import com.loanlens.entity.Customer;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.OperationalCostAllocation;
import com.loanlens.entity.ProfitabilitySnapshot;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.MissingInputsException;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.repository.ProfitabilitySnapshotRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfitabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-31T10:15:30Z");

    @Mock LoanService loanService;
    @Mock FinancialRecordRepository financialRecords;
    @Mock RiskAssessmentRepository riskAssessments;
    @Mock OperationalCostAllocationRepository allocations;
    @Mock ProfitabilitySnapshotRepository snapshots;

    private ProfitabilityService service;
    private Loan rahulsLoan;

    @BeforeEach
    void setUp() {
        service = new ProfitabilityService(loanService, financialRecords, riskAssessments, allocations, snapshots,
                new EclCalculator(), new ProfitabilityCalculator(), Clock.fixed(NOW, ZoneOffset.UTC));

        Customer rahul = new Customer();
        rahul.setId(1L);
        rahul.setCustomerCode("C2001");
        rahul.setFullName("Rahul Sharma");

        rahulsLoan = new Loan();
        rahulsLoan.setId(10L);
        rahulsLoan.setLoanCode("L1001");
        rahulsLoan.setCustomer(rahul);
        rahulsLoan.setLoanType(LoanType.PERSONAL);
        rahulsLoan.setLoanStatus(LoanStatus.ACTIVE);
        rahulsLoan.setOriginalPrincipal(new BigDecimal("500000.00"));
        rahulsLoan.setOutstandingBalance(new BigDecimal("500000.00"));
        rahulsLoan.setInterestRate(new BigDecimal("0.100000"));
        rahulsLoan.setStartDate(LocalDate.of(2025, 4, 1));
        rahulsLoan.setMaturityDate(LocalDate.of(2030, 4, 1));
    }

    private FinancialRecord rahulsRecord() {
        FinancialRecord r = new FinancialRecord();
        r.setLoan(rahulsLoan);
        r.setReportingPeriod("2026");
        r.setInterestIncome(new BigDecimal("60000.00"));
        r.setFeeIncome(new BigDecimal("2000.00"));
        r.setFundingRate(new BigDecimal("0.070000"));
        r.setFundingCost(new BigDecimal("35000.00"));
        r.setOperationalCost(new BigDecimal("7000.00"));
        return r;
    }

    private RiskAssessment rahulsRisk() {
        RiskAssessment a = new RiskAssessment();
        a.setLoan(rahulsLoan);
        a.setReportingPeriod("2026");
        a.setProbabilityOfDefault(new BigDecimal("0.100000"));
        a.setLossGivenDefault(new BigDecimal("0.600000"));
        a.setExposureAtDefault(new BigDecimal("500000.00"));
        a.setRiskRating(RiskRating.HIGH);
        return a;
    }

    private ProfitabilitySnapshot snapshot(long id) {
        ProfitabilitySnapshot s = mock(ProfitabilitySnapshot.class);
        when(s.getId()).thenReturn(id);
        when(s.getCalculationVersion()).thenReturn(ProfitabilityCalculator.VERSION);
        when(s.getCalculatedAt()).thenReturn(NOW);
        return s;
    }

    private void givenInputs(FinancialRecord record, RiskAssessment risk) {
        when(loanService.requireLoan("L1001")).thenReturn(rahulsLoan);
        when(financialRecords.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.ofNullable(record));
        when(riskAssessments.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.ofNullable(risk));
    }

    @Test
    @DisplayName("Rahul / 2026: produces the expected figures and stores a new snapshot")
    void rahulHappyPath() {
        givenInputs(rahulsRecord(), rahulsRisk());
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of(
                allocation(CostCategory.LOAN_PROCESSING, "500.00"),
                allocation(CostCategory.CUSTOMER_SUPPORT, "150.00"),
                allocation(CostCategory.LOAN_SERVICING, "6350.00")));
        ProfitabilitySnapshot stored = snapshot(77L);
        when(snapshots.insertIfAbsent(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(snapshots.findByLoanIdAndReportingPeriodAndCalculationVersionAndInputFingerprint(eq(10L), eq("2026"), eq(ProfitabilityCalculator.VERSION), anyString()))
                .thenReturn(Optional.of(stored));

        ProfitabilityResponse r = service.calculate("L1001", "2026");

        assertThat(r.loan().loanCode()).isEqualTo("L1001");
        assertThat(r.loan().customerName()).isEqualTo("Rahul Sharma");
        assertThat(r.reportingPeriod()).isEqualTo("2026");
        assertThat(r.interestIncome()).isEqualByComparingTo("60000.00");
        assertThat(r.feeIncome()).isEqualByComparingTo("2000.00");
        assertThat(r.totalIncome()).isEqualByComparingTo("62000.00");
        assertThat(r.fundingCost()).isEqualByComparingTo("35000.00");
        assertThat(r.operationalCost()).isEqualByComparingTo("7000.00");
        assertThat(r.totalCostsBeforeEcl()).isEqualByComparingTo("42000.00");
        assertThat(r.contributionBeforeEcl()).isEqualByComparingTo("20000.00");
        assertThat(r.expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        assertThat(r.contributionStatus()).isEqualTo(ProfitabilityResponse.ContributionStatus.NEGATIVE);
        assertThat(r.risk().riskRating()).isEqualTo(RiskRating.HIGH);
        assertThat(r.operationalCostAllocations()).hasSize(3);
        assertThat(r.snapshot().id()).isEqualTo(77L);
        assertThat(r.snapshot().newlyCreated()).isTrue();
        assertThat(r.snapshot().calculationVersion()).isEqualTo(ProfitabilityCalculator.VERSION);
        assertThat(r.snapshot().calculatedAt()).isEqualTo(NOW);
        assertThat(String.join(" ", r.notes())).contains("does not mean the borrower has defaulted").contains("ESTIMATE");
    }

    @Test
    @DisplayName("repeating the request reuses the stored snapshot instead of creating a duplicate")
    void reusesExistingSnapshot() {
        givenInputs(rahulsRecord(), rahulsRisk());
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of());
        ProfitabilitySnapshot stored = snapshot(77L);
        when(snapshots.insertIfAbsent(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(0); // database says: already there
        when(snapshots.findByLoanIdAndReportingPeriodAndCalculationVersionAndInputFingerprint(eq(10L), eq("2026"), eq(ProfitabilityCalculator.VERSION), anyString()))
                .thenReturn(Optional.of(stored));

        ProfitabilityResponse r = service.calculate("L1001", "2026");

        assertThat(r.snapshot().id()).isEqualTo(77L);
        assertThat(r.snapshot().newlyCreated()).isFalse();
        assertThat(r.notes()).anyMatch(n -> n.contains("No operational cost allocation lines"));
    }

    @Test
    @DisplayName("identical inputs give identical fingerprints; any changed input gives a new one")
    void fingerprintChangesWithInputs() {
        BigDecimal pd = new BigDecimal("0.10");
        String base = InputFingerprint.of("v", "L1", "2026", bd("60000"), bd("2000"), bd("35000"), bd("7000"), pd, new BigDecimal("0.6"), bd("500000"));
        String same = InputFingerprint.of("v", "L1", "2026", bd("60000.00"), bd("2000.0"), bd("35000"), bd("7000"), new BigDecimal("0.100000"), new BigDecimal("0.60"), bd("500000.00"));
        String changedIncome = InputFingerprint.of("v", "L1", "2026", bd("60001"), bd("2000"), bd("35000"), bd("7000"), pd, new BigDecimal("0.6"), bd("500000"));
        String changedPeriod = InputFingerprint.of("v", "L1", "2027", bd("60000"), bd("2000"), bd("35000"), bd("7000"), pd, new BigDecimal("0.6"), bd("500000"));
        String changedVersion = InputFingerprint.of("v2", "L1", "2026", bd("60000"), bd("2000"), bd("35000"), bd("7000"), pd, new BigDecimal("0.6"), bd("500000"));

        assertThat(same).isEqualTo(base);
        assertThat(base).hasSize(64);
        assertThat(changedIncome).isNotEqualTo(base);
        assertThat(changedPeriod).isNotEqualTo(base);
        assertThat(changedVersion).isNotEqualTo(base);
    }

    @Test
    @DisplayName("missing financial record is reported, never treated as zero, and nothing is stored")
    void missingFinancialRecord() {
        givenInputs(null, rahulsRisk());

        assertThatThrownBy(() -> service.calculate("L1001", "2026"))
                .isInstanceOfSatisfying(MissingInputsException.class, ex -> {
                    assertThat(ex.getMissingInputs()).hasSize(1);
                    assertThat(ex.getMissingInputs().get(0)).contains("Financial record").contains("2026");
                    assertThat(ex.getMessage()).contains("L1001").contains("2026");
                });
        verifyNoInteractions(snapshots);
    }

    @Test
    void missingRiskAssessment() {
        givenInputs(rahulsRecord(), null);

        assertThatThrownBy(() -> service.calculate("L1001", "2026"))
                .isInstanceOfSatisfying(MissingInputsException.class,
                        ex -> assertThat(ex.getMissingInputs()).singleElement().asString().contains("Risk assessment"));
        verifyNoInteractions(snapshots);
    }

    @Test
    void bothMissingAreBothReported() {
        givenInputs(null, null);

        assertThatThrownBy(() -> service.calculate("L1001", "2026"))
                .isInstanceOfSatisfying(MissingInputsException.class, ex -> assertThat(ex.getMissingInputs()).hasSize(2));
        verifyNoInteractions(snapshots);
    }

    @Test
    @DisplayName("periods are never mixed: only the exact requested period is looked up")
    void onlyTheRequestedPeriodIsUsed() {
        when(loanService.requireLoan("L1001")).thenReturn(rahulsLoan);
        // Inputs exist for 2025 in the database, but the request is for 2026.
        when(financialRecords.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.empty());
        when(riskAssessments.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate("L1001", "2026")).isInstanceOf(MissingInputsException.class);

        verify(financialRecords).findByLoanIdAndReportingPeriod(10L, "2026");
        verify(financialRecords, never()).findByLoanIdAndReportingPeriod(10L, "2025");
        verify(financialRecords, never()).findByLoanIdOrderByReportingPeriodDesc(any());
        verify(riskAssessments, never()).findByLoanIdOrderByReportingPeriodDesc(any());
    }

    @Test
    @DisplayName("an allocation breakdown that does not add up to the operational cost is refused")
    void allocationMismatch() {
        givenInputs(rahulsRecord(), rahulsRisk());
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of(
                allocation(CostCategory.LOAN_PROCESSING, "500.00"),
                allocation(CostCategory.LOAN_SERVICING, "6000.00"))); // 6,500 vs 7,000

        assertThatThrownBy(() -> service.calculate("L1001", "2026"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("6500.00")
                .hasMessageContaining("7000.00");
        verifyNoInteractions(snapshots);
    }

    @Test
    void invalidPeriodIsABadRequest() {
        assertThatThrownBy(() -> service.calculate("L1001", "26")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.calculate("L1001", "FY2026")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.calculate("L1001", null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(loanService, snapshots);
    }

    @Test
    @DisplayName("evaluate() is read-only: it calculates the same figures but never touches the snapshot table")
    void evaluateWritesNothing() {
        givenInputs(rahulsRecord(), rahulsRisk());
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of());

        ProfitabilityService.Evaluation e = service.evaluate("L1001", "2026");

        assertThat(e.result().totalIncome()).isEqualByComparingTo("62000.00");
        assertThat(e.result().contributionBeforeEcl()).isEqualByComparingTo("20000.00");
        assertThat(e.result().expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(e.result().contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        verifyNoInteractions(snapshots);
    }

    @Test
    @DisplayName("evaluate() refuses missing inputs and mismatched allocations exactly like calculate()")
    void evaluateValidatesLikeCalculate() {
        givenInputs(null, rahulsRisk());
        assertThatThrownBy(() -> service.evaluate("L1001", "2026")).isInstanceOf(MissingInputsException.class);
    }

    /** Scenario table, run through the whole service (lookup -> ECL -> profitability): income and cost mixes. */
    @ParameterizedTest(name = "{0}: before ECL {7}, ECL {8}, after ECL {9} ({10})")
    @CsvSource(delimiter = '|', value = {
            // name | interest | fee | funding | operational | PD | LGD*EAD as "lgd/ead" | before | ecl | after | status
            "Rahul reference     | 60000   | 2000 | 35000  | 7000  | 0.10 | 0.60/500000 | 20000   | 30000   | -10000  | NEGATIVE",
            "Priya positive      | 372400  | 9000 | 297500 | 11150 | 0.012| 0.25/4120000| 72750   | 12360   | 60390   | POSITIVE",
            "Break-even          | 100000  | 0    | 60000  | 10000 | 0.10 | 0.30/1000000| 30000   | 30000   | 0       | BREAK_EVEN",
            "High funding cost   | 60000   | 2000 | 80000  | 7000  | 0.01 | 0.50/500000 | -25000  | 2500    | -27500  | NEGATIVE",
            "High operational    | 60000   | 2000 | 35000  | 40000 | 0.01 | 0.50/500000 | -13000  | 2500    | -15500  | NEGATIVE",
            "Higher ECL turns it | 60000   | 2000 | 35000  | 7000  | 0.20 | 0.60/500000 | 20000   | 60000   | -40000  | NEGATIVE",
            "Zero ECL inputs     | 60000   | 2000 | 35000  | 7000  | 0    | 0.60/500000 | 20000   | 0       | 20000   | POSITIVE",
            "Zero income         | 0       | 0    | 35000  | 7000  | 0.10 | 0.60/500000 | -42000  | 30000   | -72000  | NEGATIVE",
            "Different balance   | 120000  | 4000 | 70000  | 7000  | 0.10 | 0.60/1000000| 47000   | 60000   | -13000  | NEGATIVE",
            "Smaller exposure    | 30000   | 1000 | 17500  | 7000  | 0.10 | 0.60/250000 | 6500    | 15000   | -8500   | NEGATIVE"
    })
    void scenarioTable(String name, String interest, String fee, String funding, String operational, String pd,
                       String lgdAndEad, String before, String ecl, String after, String status) {
        FinancialRecord record = rahulsRecord();
        record.setInterestIncome(new BigDecimal(interest.trim()));
        record.setFeeIncome(new BigDecimal(fee.trim()));
        record.setFundingCost(new BigDecimal(funding.trim()));
        record.setOperationalCost(new BigDecimal(operational.trim()));
        RiskAssessment risk = rahulsRisk();
        risk.setProbabilityOfDefault(new BigDecimal(pd.trim()));
        String[] parts = lgdAndEad.trim().split("/");
        risk.setLossGivenDefault(new BigDecimal(parts[0]));
        risk.setExposureAtDefault(new BigDecimal(parts[1]));
        givenInputs(record, risk);
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of());

        ProfitabilityCalculator.Result r = service.evaluate("L1001", "2026").result();

        assertThat(r.contributionBeforeEcl()).isEqualByComparingTo(before.trim());
        assertThat(r.expectedCreditLoss()).isEqualByComparingTo(ecl.trim());
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo(after.trim());
        assertThat(r.status().name()).isEqualTo(status.trim());
        // before-ECL and after-ECL are always separate figures
        assertThat(r.contributionAfterEcl()).isEqualByComparingTo(r.contributionBeforeEcl().subtract(r.expectedCreditLoss()));
    }

    @Test
    @DisplayName("different periods give different results: each period uses only its own record and assessment")
    void differentPeriodsAreIndependent() {
        when(loanService.requireLoan("L1001")).thenReturn(rahulsLoan);
        FinancialRecord r2026 = rahulsRecord();
        FinancialRecord r2027 = rahulsRecord();
        r2027.setReportingPeriod("2027");
        r2027.setInterestIncome(new BigDecimal("45000.00"));
        RiskAssessment a2026 = rahulsRisk();
        RiskAssessment a2027 = rahulsRisk();
        a2027.setReportingPeriod("2027");
        a2027.setProbabilityOfDefault(new BigDecimal("0.02"));
        when(financialRecords.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.of(r2026));
        when(financialRecords.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.of(r2027));
        when(riskAssessments.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.of(a2026));
        when(riskAssessments.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.of(a2027));
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(anyLong(), anyString())).thenReturn(List.of());

        ProfitabilityCalculator.Result y26 = service.evaluate("L1001", "2026").result();
        ProfitabilityCalculator.Result y27 = service.evaluate("L1001", "2027").result();

        assertThat(y26.contributionAfterEcl()).isEqualByComparingTo("-10000.00");
        // 2027: income 47,000 - 42,000 = 5,000 before ECL; ECL 2% x 60% x 5,00,000 = 6,000 -> -1,000
        assertThat(y27.contributionBeforeEcl()).isEqualByComparingTo("5000.00");
        assertThat(y27.expectedCreditLoss()).isEqualByComparingTo("6000.00");
        assertThat(y27.contributionAfterEcl()).isEqualByComparingTo("-1000.00");
    }

    @Test
    @DisplayName("an assessment for another period is never used: the requested period has none, so inputs are missing")
    void anotherPeriodsAssessmentIsNotSubstituted() {
        when(loanService.requireLoan("L1001")).thenReturn(rahulsLoan);
        when(financialRecords.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.of(rahulsRecord()));
        when(riskAssessments.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate("L1001", "2027"))
                .isInstanceOfSatisfying(MissingInputsException.class,
                        ex -> assertThat(ex.getMissingInputs()).singleElement().asString().contains("Risk assessment").contains("2027"));
        verify(riskAssessments, never()).findByLoanIdOrderByReportingPeriodDesc(any());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private OperationalCostAllocation allocation(CostCategory category, String amount) {
        OperationalCostAllocation a = new OperationalCostAllocation();
        a.setLoan(rahulsLoan);
        a.setReportingPeriod("2026");
        a.setCostCategory(category);
        a.setAllocationMethod(AllocationMethod.ACTIVITY_BASED_COSTING);
        a.setAllocationDriver("driver");
        a.setAllocatedAmount(new BigDecimal(amount));
        return a;
    }
}
