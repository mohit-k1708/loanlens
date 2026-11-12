package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.loanlens.entity.RiskRating;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskRatingPolicyTest {

    private final RiskRatingPolicy policy = new RiskRatingPolicy();

    @ParameterizedTest(name = "PD {0} -> {1}")
    @CsvSource({
            "0, LOW", "0.010, LOW", "0.029999, LOW",
            "0.03, MEDIUM", "0.05, MEDIUM", "0.079999, MEDIUM",
            "0.08, HIGH", "0.10, HIGH", "1, HIGH"})
    void bands(String pd, RiskRating expected) {
        assertThat(policy.rate(new BigDecimal(pd))).isEqualTo(expected);
    }
}
