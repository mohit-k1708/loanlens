package com.loanlens.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Simplified educational funding cost:
 * <pre>Funding Cost = Average Outstanding Balance x Funding Rate x (months / 12)</pre>
 *
 * <p>Assumptions (documented in docs/FINANCIAL_POLICY.md):
 * <ul>
 *   <li>The balance is the <b>average outstanding balance</b> over the reporting period, never the original principal.
 *       A loan that has amortised or been part-repaid therefore costs less to fund than its principal suggests.</li>
 *   <li>The funding rate is an annual decimal fraction (0.07 = 7% a year).</li>
 *   <li>A reporting period is a calendar year, so the stored records use 12 months. {@code months} (1..12) exists so
 *       a part-year period can be calculated with the same rule; it is not used for stored annual records today.</li>
 *   <li>The product is computed exactly and rounded ONCE to 2 decimals, HALF_UP. Nothing is rounded in between.</li>
 * </ul>
 * Nothing here defaults a missing value to zero: a missing balance or rate is an error.
 */
@Component
public class FundingCostService {

    public static final int MONTHS_IN_YEAR = 12;

    /** A supplied funding cost may differ from the recomputed figure by at most this many rupees (rounding of inputs). */
    public static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    private static final BigDecimal TWELVE = BigDecimal.valueOf(MONTHS_IN_YEAR);

    /** Funding cost for a whole (12-month) reporting period. */
    public BigDecimal calculate(BigDecimal averageOutstandingBalance, BigDecimal annualFundingRate) {
        return calculate(averageOutstandingBalance, annualFundingRate, MONTHS_IN_YEAR);
    }

    public BigDecimal calculate(BigDecimal averageOutstandingBalance, BigDecimal annualFundingRate, int months) {
        if (averageOutstandingBalance == null) {
            throw new IllegalArgumentException("Average outstanding balance is required to calculate funding cost.");
        }
        if (annualFundingRate == null) {
            throw new IllegalArgumentException("Funding rate is required to calculate funding cost.");
        }
        if (averageOutstandingBalance.signum() < 0) {
            throw new IllegalArgumentException("Average outstanding balance cannot be negative.");
        }
        if (annualFundingRate.signum() < 0 || annualFundingRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Funding rate must be between 0 and 1 (e.g. 0.07 for 7%).");
        }
        if (months < 1 || months > MONTHS_IN_YEAR) {
            throw new IllegalArgumentException("Months in the reporting period must be between 1 and 12.");
        }
        return averageOutstandingBalance.multiply(annualFundingRate).multiply(BigDecimal.valueOf(months))
                .divide(TWELVE, MoneyRules.SCALE, RoundingMode.HALF_UP);
    }

    /** True if {@code suppliedFundingCost} agrees with the recomputed figure within {@link #TOLERANCE}. */
    public boolean isConsistent(BigDecimal suppliedFundingCost, BigDecimal averageOutstandingBalance, BigDecimal annualFundingRate) {
        BigDecimal expected = calculate(averageOutstandingBalance, annualFundingRate);
        return suppliedFundingCost.subtract(expected).abs().compareTo(TOLERANCE) <= 0;
    }
}
