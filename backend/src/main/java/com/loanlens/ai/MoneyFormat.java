package com.loanlens.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Text forms of rupee amounts and percentages used in explanations. Indian digit grouping (₹5,00,000), whole rupees
 * without decimals, paise shown only when there are any. Formatting never changes a value: it is for display only.
 */
public final class MoneyFormat {

    public static final String RUPEE = "₹";

    private MoneyFormat() {
    }

    public static String rupees(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        BigDecimal abs = rounded.abs();
        String plain = abs.toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String paise = plain.substring(plain.indexOf('.') + 1);
        String text = RUPEE + group(whole) + ("00".equals(paise) ? "" : "." + paise);
        return negative ? "-" + text : text;
    }

    /** @param percent a percentage number: 10 for 10% */
    public static String percent(BigDecimal percent) {
        BigDecimal p = percent.stripTrailingZeros();
        return (p.scale() < 0 ? p.setScale(0) : p).toPlainString() + "%";
    }

    /** 1234567 -> 12,34,567 */
    static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            if (i > 0 && (rest.length() - i) % 2 == 0) {
                out.append(',');
            }
            out.append(rest.charAt(i));
        }
        return out + "," + last3;
    }
}
