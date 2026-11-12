package com.loanlens.service;

import com.loanlens.entity.RiskAssessment;
import com.loanlens.repository.RiskAssessmentRepository;
import com.loanlens.validation.ReportingPeriods;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expected Credit Loss for a loan in ONE reporting period: {@code ECL = PD x LGD x EAD}, using the risk assessment of
 * exactly that period (see {@link EclCalculator} for the arithmetic and the caveats).
 *
 * <p>A period without a risk assessment has no ECL. The result is empty, never zero, so a missing assessment cannot be
 * mistaken for "no expected loss".
 */
@Service
@Transactional(readOnly = true)
public class EclCalculationService {

    private final RiskAssessmentRepository assessments;
    private final EclCalculator calculator;

    public EclCalculationService(RiskAssessmentRepository assessments, EclCalculator calculator) {
        this.assessments = assessments;
        this.calculator = calculator;
    }

    public BigDecimal calculate(RiskAssessment assessment) {
        return calculator.calculate(assessment.getProbabilityOfDefault(), assessment.getLossGivenDefault(),
                assessment.getExposureAtDefault());
    }

    /** The risk assessment of this exact period, or empty. Other periods are never substituted. */
    public Optional<RiskAssessment> assessmentFor(Long loanId, String period) {
        ReportingPeriods.require(period);
        return assessments.findByLoanIdAndReportingPeriod(loanId, period);
    }

    /** ECL from the assessment of this exact period, or empty if there is none. */
    public Optional<BigDecimal> forPeriod(Long loanId, String period) {
        return assessmentFor(loanId, period).map(this::calculate);
    }
}
