package com.loanlens.ai;

import java.math.BigDecimal;

/**
 * One number computed or stored by the backend. The AI may only quote facts it was given; it refers to them by
 * {@code key} and the backend, not the AI, supplies the amount shown next to every reason.
 *
 * @param key     stable identifier, e.g. {@code fundingCost}
 * @param label   human label, e.g. "Funding cost"
 * @param value   rupees, or a percentage number for {@link FactUnit#PERCENT} (10 = 10%)
 * @param unit    unit of {@code value}
 * @param display the exact text to use when quoting it, e.g. "₹35,000" or "10%"
 */
public record Fact(String key, String label, BigDecimal value, FactUnit unit, String display) {

    public static Fact rupees(String key, String label, BigDecimal value) {
        return new Fact(key, label, value, FactUnit.RUPEES, MoneyFormat.rupees(value));
    }

    /** @param fraction a decimal fraction such as 0.10 for 10% */
    public static Fact percentOfFraction(String key, String label, BigDecimal fraction) {
        BigDecimal percent = fraction.movePointRight(2).stripTrailingZeros();
        if (percent.scale() < 0) {
            percent = percent.setScale(0);
        }
        return new Fact(key, label, percent, FactUnit.PERCENT, MoneyFormat.percent(percent));
    }
}
