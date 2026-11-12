package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loanlens.dto.FinancialRecordRequest;
import com.loanlens.dto.FinancialRecordResponse;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.loanlens.entity.OperationalCostAllocation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FinancialRecordServiceTest {

    @Mock FinancialRecordRepository records;
    @Mock OperationalCostAllocationRepository allocations;
    @Mock LoanService loanService;

    private FinancialRecordService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        service = new FinancialRecordService(records, allocations, loanService, new FundingCostService());
        loan = new Loan();
        loan.setId(10L);
        loan.setLoanCode("L1001");
        loan.setStartDate(LocalDate.of(2025, 4, 1));
        loan.setMaturityDate(LocalDate.of(2030, 4, 1));
        lenient().when(loanService.requireLoan("L1001")).thenReturn(loan);
    }

    private static FinancialRecordRequest request(String period, String avgBalance, String fundingCost) {
        return new FinancialRecordRequest(period, new BigDecimal("60000.00"), new BigDecimal("2000.00"),
                avgBalance == null ? null : new BigDecimal(avgBalance), new BigDecimal("0.07"),
                new BigDecimal(fundingCost), new BigDecimal("7000.00"));
    }

    @Test
    void createsRecordAndReportsTotalIncome() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(records.saveAndFlush(any(FinancialRecord.class))).thenAnswer(i -> i.getArgument(0));

        FinancialRecordResponse response = service.create("L1001", request("2026", "500000.00", "35000.00"));

        assertThat(response.loanCode()).isEqualTo("L1001");
        assertThat(response.totalIncome()).isEqualByComparingTo("62000.00");
        assertThat(response.fundingCost()).isEqualByComparingTo("35000.00");
    }

    @Test
    void duplicatePeriodIsAConflict() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(true);

        assertThatThrownBy(() -> service.create("L1001", request("2026", null, "35000.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2026");
        verify(records, never()).saveAndFlush(any());
    }

    @Test
    void fundingCostMustMatchBalanceTimesRateWhenBalanceIsSupplied() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);

        // 5,00,000 x 7% = 35,000 but 40,000 was supplied
        assertThatThrownBy(() -> service.create("L1001", request("2026", "500000.00", "40000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("fundingCost"));
    }

    @Test
    void fundingCostWithinOneRupeeToleranceIsAccepted() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(records.saveAndFlush(any(FinancialRecord.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create("L1001", request("2026", "500000.00", "35000.75")).fundingCost()).isEqualByComparingTo("35000.75");
    }

    @Test
    void fundingCostIsNotCheckedWithoutABalance() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(records.saveAndFlush(any(FinancialRecord.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create("L1001", request("2026", null, "99999.00")).fundingCost()).isEqualByComparingTo("99999.00");
    }

    @Test
    @DisplayName("the average balance can never exceed the loan's original principal")
    void averageBalanceAbovePrincipalIsRejected() {
        loan.setOriginalPrincipal(new BigDecimal("500000.00"));
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);

        // 5,50,000 x 7% = 38,500 is internally consistent, but the balance is larger than the principal
        assertThatThrownBy(() -> service.create("L1001", request("2026", "550000.00", "38500.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getFieldErrors()).containsKey("averageOutstandingBalance"));
        verify(records, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("funding cost uses the AVERAGE balance: a part-repaid loan is checked against 4,20,000, not the 5,00,000 principal")
    void fundingCostIsCheckedAgainstTheAverageBalance() {
        loan.setOriginalPrincipal(new BigDecimal("500000.00"));
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(records.saveAndFlush(any(FinancialRecord.class))).thenAnswer(i -> i.getArgument(0));

        // 4,20,000 x 7% = 29,400 is accepted ...
        assertThat(service.create("L1001", request("2026", "420000.00", "29400.00")).fundingCost()).isEqualByComparingTo("29400.00");
        // ... and 35,000 (principal x rate) is refused for that average balance
        assertThatThrownBy(() -> service.create("L1001", request("2026", "420000.00", "35000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("fundingCost"));
    }

    @Test
    @DisplayName("operational cost cannot be set below the cost breakdown already recorded for the period")
    void operationalCostCannotDropBelowRecordedAllocations() {
        when(records.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        OperationalCostAllocation a = new OperationalCostAllocation();
        a.setAllocatedAmount(new BigDecimal("6500.00"));
        when(allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(10L, "2026")).thenReturn(List.of(a));

        // the request's operational cost is 7,000 -> fine
        when(records.saveAndFlush(any(FinancialRecord.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service.create("L1001", request("2026", null, "35000.00")).operationalCost()).isEqualByComparingTo("7000.00");

        // allocations of 7,500 exceed the 7,000 operational cost -> refused
        a.setAllocatedAmount(new BigDecimal("7500.00"));
        assertThatThrownBy(() -> service.create("L1001", request("2026", null, "35000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("operationalCost"));
    }

    @Test
    void updateAppliesTheSameChecks() {
        FinancialRecord existing = new FinancialRecord();
        existing.setLoan(loan);
        existing.setReportingPeriod("2026");
        when(records.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> service.update("L1001", "2026", request("2026", "500000.00", "40000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("fundingCost"));
        verify(records, never()).saveAndFlush(any());
    }

    @Test
    void periodOutsideTheLoansLifeIsRejected() {
        assertThatThrownBy(() -> service.create("L1001", request("2024", null, "35000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("reportingPeriod"));
        assertThatThrownBy(() -> service.create("L1001", request("2031", null, "35000.00")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void bodyPeriodMustMatchUrlOnUpdate() {
        assertThatThrownBy(() -> service.update("L1001", "2026", request("2027", null, "35000.00")))
                .isInstanceOf(BadRequestException.class);
    }
}
