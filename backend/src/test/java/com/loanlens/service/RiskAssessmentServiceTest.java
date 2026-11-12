package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loanlens.dto.RiskAssessmentRequest;
import com.loanlens.dto.RiskAssessmentResponse;
import com.loanlens.entity.Loan;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class RiskAssessmentServiceTest {

    @Mock RiskAssessmentRepository assessments;
    @Mock LoanService loanService;

    private RiskAssessmentService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        service = new RiskAssessmentService(assessments, loanService, new EclCalculator(), new RiskRatingPolicy());
        loan = new Loan();
        loan.setId(10L);
        loan.setLoanCode("L1001");
        loan.setStartDate(LocalDate.of(2025, 4, 1));
        loan.setMaturityDate(LocalDate.of(2030, 4, 1));
        lenient().when(loanService.requireLoan("L1001")).thenReturn(loan);
    }

    private static RiskAssessmentRequest request(String period, String pd, String lgd, String ead) {
        return new RiskAssessmentRequest(period, new BigDecimal(pd), new BigDecimal(lgd), new BigDecimal(ead));
    }

    @Test
    @DisplayName("Rahul / 2026: PD 10%, LGD 60%, EAD 5,00,000 -> ECL 30,000.00 and a HIGH rating")
    void rahul() {
        when(assessments.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(assessments.saveAndFlush(any(RiskAssessment.class))).thenAnswer(i -> i.getArgument(0));

        RiskAssessmentResponse r = service.create("L1001", request("2026", "0.10", "0.60", "500000.00"));

        assertThat(r.expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(r.riskRating()).isEqualTo(RiskRating.HIGH);
        assertThat(r.reportingPeriod()).isEqualTo("2026");
        assertThat(r.calculationNote()).contains("estimate").contains("not a realised loss");
    }

    @ParameterizedTest(name = "PD {0} -> {1}")
    @CsvSource({"0, LOW", "0.0299, LOW", "0.03, MEDIUM", "0.0799, MEDIUM", "0.08, HIGH", "0.10, HIGH", "1, HIGH"})
    void ratingIsDerivedFromPdBands(String pd, RiskRating expected) {
        when(assessments.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(false);
        when(assessments.saveAndFlush(any(RiskAssessment.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create("L1001", request("2026", pd, "0.50", "100000.00")).riskRating()).isEqualTo(expected);
    }

    @Test
    void duplicatePeriodIsAConflict() {
        when(assessments.existsByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(true);

        assertThatThrownBy(() -> service.create("L1001", request("2026", "0.10", "0.60", "500000.00")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("2026");
        verify(assessments, never()).saveAndFlush(any());
    }

    @Test
    void periodOutsideTheLoansLifeIsRejected() {
        assertThatThrownBy(() -> service.create("L1001", request("2024", "0.10", "0.60", "500000.00")))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> assertThat(ex.getFieldErrors()).containsKey("reportingPeriod"));
        assertThatThrownBy(() -> service.create("L1001", request("2031", "0.10", "0.60", "500000.00")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("updating one period changes only that period's assessment and re-derives its rating")
    void updateChangesOnlyThatPeriod() {
        RiskAssessment a2026 = existing("2026", "0.10", "0.60", "500000.00", RiskRating.HIGH);
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.of(a2026));
        when(assessments.saveAndFlush(any(RiskAssessment.class))).thenAnswer(i -> i.getArgument(0));

        RiskAssessmentResponse r = service.update("L1001", "2026", request("2026", "0.02", "0.60", "500000.00"));

        assertThat(r.riskRating()).isEqualTo(RiskRating.LOW);
        assertThat(r.expectedCreditLoss()).isEqualByComparingTo("6000.00");
        verify(assessments, never()).findByLoanIdAndReportingPeriod(10L, "2025");
        verify(assessments, never()).findByLoanIdAndReportingPeriod(10L, "2027");
    }

    @Test
    void bodyPeriodMustMatchUrlOnUpdate() {
        assertThatThrownBy(() -> service.update("L1001", "2026", request("2027", "0.10", "0.60", "500000.00")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a missing assessment is a 404, never a zero-risk default")
    void missingAssessmentIsNotFound() {
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("L1001", "2027")).isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("2027");
        assertThatThrownBy(() -> service.update("L1001", "2027", request("2027", "0.1", "0.6", "1000.00")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("each period's assessment gives its own ECL (risk assessment changes across periods)")
    void differentPeriodsGiveDifferentEcl() {
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2025"))
                .thenReturn(Optional.of(existing("2025", "0.05", "0.60", "500000.00", RiskRating.MEDIUM)));
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2026"))
                .thenReturn(Optional.of(existing("2026", "0.10", "0.60", "500000.00", RiskRating.HIGH)));

        assertThat(service.get("L1001", "2025").expectedCreditLoss()).isEqualByComparingTo("15000.00");
        assertThat(service.get("L1001", "2026").expectedCreditLoss()).isEqualByComparingTo("30000.00");
        assertThat(service.get("L1001", "2025").riskRating()).isEqualTo(RiskRating.MEDIUM);
        assertThat(service.get("L1001", "2026").riskRating()).isEqualTo(RiskRating.HIGH);
    }

    @Test
    void invalidPeriodOnReadIsABadRequest() {
        assertThatThrownBy(() -> service.get("L1001", "2026-27")).isInstanceOf(BadRequestException.class);
    }

    private RiskAssessment existing(String period, String pd, String lgd, String ead, RiskRating rating) {
        RiskAssessment a = new RiskAssessment();
        a.setLoan(loan);
        a.setReportingPeriod(period);
        a.setProbabilityOfDefault(new BigDecimal(pd));
        a.setLossGivenDefault(new BigDecimal(lgd));
        a.setExposureAtDefault(new BigDecimal(ead));
        a.setRiskRating(rating);
        return a;
    }
}
