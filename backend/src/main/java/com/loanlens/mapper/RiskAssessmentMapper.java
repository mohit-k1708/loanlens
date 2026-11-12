package com.loanlens.mapper;

import com.loanlens.dto.RiskAssessmentResponse;
import com.loanlens.entity.RiskAssessment;
import java.math.BigDecimal;

public final class RiskAssessmentMapper {

    private RiskAssessmentMapper() {
    }

    public static RiskAssessmentResponse toResponse(RiskAssessment r, String loanCode, BigDecimal expectedCreditLoss) {
        return new RiskAssessmentResponse(r.getId(), loanCode, r.getReportingPeriod(), r.getProbabilityOfDefault(),
                r.getLossGivenDefault(), r.getExposureAtDefault(), expectedCreditLoss, r.getRiskRating(), r.getAssessedAt(),
                RiskAssessmentResponse.NOTE);
    }
}
