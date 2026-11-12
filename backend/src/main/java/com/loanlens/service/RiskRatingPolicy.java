package com.loanlens.service;

import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Maps a probability of default to a rating band. Educational thresholds, matching the frontend demo:
 * PD below 3% is LOW, below 8% is MEDIUM, otherwise HIGH.
 */
@Component
public class RiskRatingPolicy {

    static final BigDecimal MEDIUM_FROM = new BigDecimal("0.03");
    static final BigDecimal HIGH_FROM = new BigDecimal("0.08");

    public RiskRating rate(BigDecimal probabilityOfDefault) {
        if (probabilityOfDefault.compareTo(MEDIUM_FROM) < 0) {
            return RiskRating.LOW;
        }
        if (probabilityOfDefault.compareTo(HIGH_FROM) < 0) {
            return RiskRating.MEDIUM;
        }
        return RiskRating.HIGH;
    }
}
