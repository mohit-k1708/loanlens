package com.loanlens.service;

import com.loanlens.dto.RiskAssessmentRequest;
import com.loanlens.dto.RiskAssessmentResponse;
import com.loanlens.entity.Loan;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.mapper.RiskAssessmentMapper;
import com.loanlens.repository.RiskAssessmentRepository;
import com.loanlens.validation.ReportingPeriods;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RiskAssessmentService {

    private final RiskAssessmentRepository assessments;
    private final LoanService loanService;
    private final EclCalculator eclCalculator;
    private final RiskRatingPolicy ratingPolicy;

    public RiskAssessmentService(RiskAssessmentRepository assessments, LoanService loanService,
                                 EclCalculator eclCalculator, RiskRatingPolicy ratingPolicy) {
        this.assessments = assessments;
        this.loanService = loanService;
        this.eclCalculator = eclCalculator;
        this.ratingPolicy = ratingPolicy;
    }

    @Transactional
    public RiskAssessmentResponse create(String loanIdOrCode, RiskAssessmentRequest request) {
        Loan loan = loanService.requireLoan(loanIdOrCode);
        String period = request.reportingPeriod();
        LoanPeriodRules.requireWithinLoanLife(loan, period);

        if (assessments.existsByLoanIdAndReportingPeriod(loan.getId(), period)) {
            throw new ConflictException("Loan " + loan.getLoanCode() + " already has a risk assessment for reporting period "
                    + period + ". Update it instead of creating another.");
        }
        RiskAssessment assessment = new RiskAssessment();
        assessment.setLoan(loan);
        assessment.setReportingPeriod(period);
        apply(request, assessment);
        return toResponse(assessments.saveAndFlush(assessment), loan);
    }

    @Transactional
    public RiskAssessmentResponse update(String loanIdOrCode, String period, RiskAssessmentRequest request) {
        ReportingPeriods.require(period);
        if (!period.equals(request.reportingPeriod())) {
            throw new BadRequestException("The reporting period in the body must match the URL.",
                    Map.of("reportingPeriod", "Must be " + period + "."));
        }
        Loan loan = loanService.requireLoan(loanIdOrCode);
        RiskAssessment assessment = find(loan, period);
        apply(request, assessment);
        return toResponse(assessments.saveAndFlush(assessment), loan);
    }

    public List<RiskAssessmentResponse> list(String loanIdOrCode) {
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return assessments.findByLoanIdOrderByReportingPeriodDesc(loan.getId()).stream()
                .map(a -> toResponse(a, loan))
                .toList();
    }

    public RiskAssessmentResponse get(String loanIdOrCode, String period) {
        ReportingPeriods.require(period);
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return toResponse(find(loan, period), loan);
    }

    private RiskAssessment find(Loan loan, String period) {
        return assessments.findByLoanIdAndReportingPeriod(loan.getId(), period).orElseThrow(() -> new ResourceNotFoundException(
                "Loan " + loan.getLoanCode() + " has no risk assessment for reporting period " + period + "."));
    }

    private void apply(RiskAssessmentRequest request, RiskAssessment assessment) {
        assessment.setProbabilityOfDefault(request.probabilityOfDefault());
        assessment.setLossGivenDefault(request.lossGivenDefault());
        assessment.setExposureAtDefault(request.exposureAtDefault());
        assessment.setRiskRating(ratingPolicy.rate(request.probabilityOfDefault()));
        assessment.setAssessedAt(Instant.now());
    }

    private RiskAssessmentResponse toResponse(RiskAssessment a, Loan loan) {
        return RiskAssessmentMapper.toResponse(a, loan.getLoanCode(),
                eclCalculator.calculate(a.getProbabilityOfDefault(), a.getLossGivenDefault(), a.getExposureAtDefault()));
    }
}
