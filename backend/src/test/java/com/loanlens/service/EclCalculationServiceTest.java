package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loanlens.entity.RiskAssessment;
import com.loanlens.exception.BadRequestException;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ECL = PD x LGD x EAD: the arithmetic ({@link EclCalculator}) and the period-correct lookup ({@link EclCalculationService}). */
@ExtendWith(MockitoExtension.class)
class EclCalculationServiceTest {

    @Mock RiskAssessmentRepository assessments;

    private final EclCalculator calculator = new EclCalculator();

    private static RiskAssessment assessment(String period, String pd, String lgd, String ead) {
        RiskAssessment a = new RiskAssessment();
        a.setReportingPeriod(period);
        a.setProbabilityOfDefault(new BigDecimal(pd));
        a.setLossGivenDefault(new BigDecimal(lgd));
        a.setExposureAtDefault(new BigDecimal(ead));
        return a;
    }

    @Test
    @DisplayName("Rahul: 10% x 60% x 5,00,000 = 30,000.00")
    void rahul() {
        BigDecimal ecl = calculator.calculate(new BigDecimal("0.10"), new BigDecimal("0.60"), new BigDecimal("500000.00"));
        assertThat(ecl).isEqualByComparingTo("30000.00");
        assertThat(ecl.scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "PD {0} x LGD {1} x EAD {2} = {3}")
    @CsvSource({
            "0.01,     0.45, 500000.00,    2250.00",       // low PD
            "0.02,     0.60, 500000.00,    6000.00",
            "0.25,     0.60, 500000.00,    75000.00",      // high PD
            "0.10,     0.10, 500000.00,    5000.00",       // low LGD
            "0.10,     0.90, 500000.00,    45000.00",      // high LGD
            "0.10,     0.60, 100000.00,    6000.00",       // different EAD
            "0.10,     0.60, 4120000.00,   247200.00",
            "0,        0.60, 500000.00,    0.00",          // zero PD
            "0.10,     0,    500000.00,    0.00",          // zero LGD
            "0.10,     0.60, 0,            0.00",          // zero EAD
            "1,        1,    500000.00,    500000.00",     // PD = 100%, LGD = 100%: ECL equals the exposure
            "0.035,    0.45, 610000.00,    9607.50",
            "0.000001, 1,    1.00,         0.00",          // below half a paisa rounds down
            "0.333333, 0.5,  1.00,         0.17",          // 0.1666665 -> 0.17 (HALF_UP)
            "0.5,      0.5,  0.02,         0.01",          // 0.005 -> 0.01: HALF_UP, not banker's rounding
            "1,        1,    123456789.12, 123456789.12"
    })
    void eclFormula(String pd, String lgd, String ead, String expected) {
        BigDecimal ecl = calculator.calculate(new BigDecimal(pd), new BigDecimal(lgd), new BigDecimal(ead));
        assertThat(ecl).isEqualByComparingTo(expected);
        assertThat(ecl.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("exact decimal arithmetic: no floating-point drift on awkward values")
    void noFloatingPointError() {
        // 0.1 * 0.2 * 3 is 0.06000000000000001 in double arithmetic.
        assertThat(calculator.calculate(new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("3"))).isEqualByComparingTo("0.06");
    }

    @ParameterizedTest(name = "PD {0}, LGD {1}, EAD {2} is invalid")
    @CsvSource({"-0.01, 0.5, 100", "1.01, 0.5, 100", "0.5, -0.01, 100", "0.5, 1.01, 100", "0.5, 0.5, -1", "10, 0.5, 100", "0.5, 60, 100"})
    void rejectsOutOfRangeInputs(String pd, String lgd, String ead) {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal(pd), new BigDecimal(lgd), new BigDecimal(ead)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> calculator.calculate(null, BigDecimal.ONE, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ECL uses the risk assessment of the requested period, not another period's")
    void usesTheRequestedPeriodsAssessment() {
        EclCalculationService service = new EclCalculationService(assessments, calculator);
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2026")).thenReturn(Optional.of(assessment("2026", "0.10", "0.60", "500000.00")));
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2025")).thenReturn(Optional.of(assessment("2025", "0.05", "0.60", "500000.00")));

        assertThat(service.forPeriod(10L, "2026")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("30000.00"));
        assertThat(service.forPeriod(10L, "2025")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("15000.00"));
        verify(assessments).findByLoanIdAndReportingPeriod(10L, "2026");
        verify(assessments).findByLoanIdAndReportingPeriod(10L, "2025");
    }

    @Test
    @DisplayName("a period without an assessment has NO ECL (empty), never zero and never another period's value")
    void missingAssessmentIsEmptyNotZero() {
        EclCalculationService service = new EclCalculationService(assessments, calculator);
        when(assessments.findByLoanIdAndReportingPeriod(10L, "2027")).thenReturn(Optional.empty());

        assertThat(service.forPeriod(10L, "2027")).isEmpty();
        verify(assessments, never()).findByLoanIdOrderByReportingPeriodDesc(10L);
    }

    @Test
    void invalidPeriodIsRejected() {
        EclCalculationService service = new EclCalculationService(assessments, calculator);
        assertThatThrownBy(() -> service.forPeriod(10L, "FY26")).isInstanceOf(BadRequestException.class);
    }
}
