package com.loanlens.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Simplified educational Expected Credit Loss:
 * <pre>ECL = PD x LGD x EAD</pre>
 *
 * <p><b>This is NOT a complete IFRS 9 / regulatory ECL model.</b> Real models can use multiple economic
 * scenarios, forward-looking information, discounting at the effective interest rate, 12-month versus lifetime
 * measurement by credit stage, and exposure that changes over time. None of that is modelled here.
 *
 * <p>PD and LGD are decimal fractions in [0, 1]; EAD is rupees. The product is computed exactly (BigDecimal)
 * and rounded once to 2 decimals, HALF_UP.
 */
@Component
public class EclCalculator {

    public BigDecimal calculate(BigDecimal probabilityOfDefault, BigDecimal lossGivenDefault, BigDecimal exposureAtDefault) {
        requireFraction(probabilityOfDefault, "Probability of default");
        requireFraction(lossGivenDefault, "Loss given default");
        if (exposureAtDefault == null || exposureAtDefault.signum() < 0) {
            throw new IllegalArgumentException("Exposure at default cannot be negative.");
        }
        return MoneyRules.round(probabilityOfDefault.multiply(lossGivenDefault).multiply(exposureAtDefault));
    }

    private static void requireFraction(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
    }
}
