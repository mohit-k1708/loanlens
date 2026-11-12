package com.loanlens.dto;

import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import java.time.Instant;

public record RiskAssessmentResponse(
        Long id,
        String loanCode,
        String reportingPeriod,
        BigDecimal probabilityOfDefault,
        BigDecimal lossGivenDefault,
        BigDecimal exposureAtDefault,
        /** PD x LGD x EAD, rounded to 2 decimals (HALF_UP). A simplified ESTIMATE, not a recorded loss. */
        BigDecimal expectedCreditLoss,
        RiskRating riskRating,
        Instant assessedAt,
        String calculationNote) {

    public static final String NOTE =
            "Simplified educational ECL = PD x LGD x EAD. It is an estimate of potential credit loss, not a realised loss, "
                    + "and not a full IFRS 9 implementation.";
}
