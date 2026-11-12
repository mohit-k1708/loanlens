package com.loanlens.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single place that defines how money is rounded.
 * All amounts are BigDecimal rupees with 2 decimal places, rounded HALF_UP, and rounding happens once, on a final
 * result, never on intermediate values. float and double are never used for money.
 */
public final class MoneyRules {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private MoneyRules() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }
}
