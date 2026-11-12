package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loanlens.dto.LoanCreateRequest;
import com.loanlens.dto.LoanResponse;
import com.loanlens.dto.LoanUpdateRequest;
import com.loanlens.entity.Customer;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.repository.CustomerRepository;
import com.loanlens.repository.LoanRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock LoanRepository loans;
    @Mock CustomerRepository customers;
    @Mock RiskAssessmentRepository riskAssessments;
    @Mock ProfitabilityOverviewService overview;

    private LoanService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new LoanService(loans, customers, riskAssessments, overview);
        customer = new Customer();
        customer.setId(1L);
        customer.setCustomerCode("C2001");
        customer.setFullName("Rahul Sharma");
    }

    private LoanCreateRequest request(String code, long customerId, String principal, String outstanding, LoanStatus status) {
        return new LoanCreateRequest(code, customerId, LoanType.PERSONAL, new BigDecimal(principal), new BigDecimal(outstanding),
                new BigDecimal("0.10"), 60, LocalDate.of(2025, 4, 1), status);
    }

    @Test
    void createsLoanWithDerivedMaturityAndDefaultStatus() {
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(loans.existsByLoanCode("L1001")).thenReturn(false);
        when(loans.saveAndFlush(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

        LoanResponse response = service.create(request("L1001", 1L, "500000.00", "500000.00", null));

        assertThat(response.loanCode()).isEqualTo("L1001");
        assertThat(response.loanStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(response.maturityDate()).isEqualTo(LocalDate.of(2030, 4, 1));
        assertThat(response.customer().fullName()).isEqualTo("Rahul Sharma");
        assertThat(response.latestRiskRating()).isNull();
    }

    @Test
    void rejectsLoanForNonexistentCustomer() {
        when(customers.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("L1001", 99L, "500000.00", "500000.00", null)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getFieldErrors()).containsKey("customerId"));
        verify(loans, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateLoanCode() {
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(loans.existsByLoanCode("L1001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("L1001", 1L, "500000.00", "500000.00", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("L1001");
        verify(loans, never()).saveAndFlush(any());
    }

    @Test
    void rejectsOutstandingBalanceAbovePrincipal() {
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(loans.existsByLoanCode("L1001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request("L1001", 1L, "500000.00", "500000.01", null)))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getFieldErrors()).containsKey("outstandingBalance"));
    }

    @Test
    void closedLoanMustHaveZeroBalance() {
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(loans.existsByLoanCode("L1001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request("L1001", 1L, "500000.00", "1.00", LoanStatus.CLOSED)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("closed loan");
    }

    @Test
    void resolvesLoanByNumericIdOrByCode() {
        Loan loan = new Loan();
        loan.setId(10L);
        loan.setLoanCode("L1001");
        when(loans.findWithCustomerById(10L)).thenReturn(Optional.of(loan));
        when(loans.findByLoanCode("L1001")).thenReturn(Optional.of(loan));

        assertThat(service.requireLoan("10")).isSameAs(loan);
        assertThat(service.requireLoan("L1001")).isSameAs(loan);
        assertThat(service.requireLoan("l1001")).isSameAs(loan); // case-insensitive code
    }

    @Test
    void unknownLoanIsNotFound() {
        when(loans.findByLoanCode("NOPE")).thenReturn(Optional.empty());
        when(loans.findWithCustomerById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireLoan("NOPE")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requireLoan("404")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("loan details and the list row show the same loan figures (principal, balance, rate)")
    void getReturnsTheStoredLoanFigures() {
        Loan loan = new Loan();
        loan.setId(10L);
        loan.setLoanCode("L1001");
        loan.setCustomer(customer);
        loan.setLoanType(LoanType.PERSONAL);
        loan.setLoanStatus(LoanStatus.ACTIVE);
        loan.setOriginalPrincipal(new BigDecimal("500000.00"));
        loan.setOutstandingBalance(new BigDecimal("500000.00"));
        loan.setInterestRate(new BigDecimal("0.100000"));
        when(loans.findByLoanCode("L1001")).thenReturn(Optional.of(loan));

        LoanResponse r = service.get("L1001");

        assertThat(r.originalPrincipal()).isEqualByComparingTo("500000.00");
        assertThat(r.outstandingBalance()).isEqualByComparingTo("500000.00");
        assertThat(r.interestRate()).isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("filtering by contribution status needs a reporting period")
    void contributionStatusNeedsAPeriod() {
        var query = new LoanService.LoanQuery(null, null, null, null, null, null, null,
                com.loanlens.dto.ProfitabilityResponse.ContributionStatus.NEGATIVE);

        assertThatThrownBy(() -> service.search(query, org.springframework.data.domain.PageRequest.of(0, 10)))
                .isInstanceOf(com.loanlens.exception.BadRequestException.class);
        verify(overview, never()).contributions(any(), any());
    }

    @Test
    @DisplayName("an unknown loan code never falls back to another loan")
    void unknownCodeDoesNotFallBack() {
        when(loans.findByLoanCode("L9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("L9999")).isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("L9999");
    }

    @Test
    @DisplayName("an update that would make the balance exceed the principal is refused and nothing is saved")
    void updateRejectsBalanceAbovePrincipal() {
        Loan existing = new Loan();
        existing.setId(10L);
        existing.setLoanCode("L1001");
        existing.setCustomer(customer);
        when(loans.findByLoanCode("L1001")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update("L1001", new LoanUpdateRequest(LoanType.PERSONAL, new BigDecimal("100.00"),
                new BigDecimal("100.01"), new BigDecimal("0.1"), 12, LocalDate.of(2025, 1, 1), LoanStatus.ACTIVE)))
                .isInstanceOf(BusinessRuleException.class);
        verify(loans, never()).saveAndFlush(any());
    }

    @Test
    void updateKeepsCodeAndCustomerAndRecomputesMaturity() {
        Loan existing = new Loan();
        existing.setId(10L);
        existing.setLoanCode("L1001");
        existing.setCustomer(customer);
        existing.setLoanType(LoanType.PERSONAL);
        existing.setLoanStatus(LoanStatus.ACTIVE);
        when(loans.findByLoanCode("L1001")).thenReturn(Optional.of(existing));
        when(loans.saveAndFlush(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

        LoanResponse response = service.update("L1001", new LoanUpdateRequest(LoanType.AUTO, new BigDecimal("600000.00"),
                new BigDecimal("550000.00"), new BigDecimal("0.095"), 36, LocalDate.of(2025, 1, 1), LoanStatus.UNDER_WATCH));

        assertThat(response.loanCode()).isEqualTo("L1001");
        assertThat(response.customer().id()).isEqualTo(1L);
        assertThat(response.loanType()).isEqualTo(LoanType.AUTO);
        assertThat(response.loanStatus()).isEqualTo(LoanStatus.UNDER_WATCH);
        assertThat(response.maturityDate()).isEqualTo(LocalDate.of(2028, 1, 1));
    }
}
