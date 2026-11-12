package com.loanlens.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A SHA-256 fingerprint of every value a profitability calculation depends on. Two requests with identical inputs
 * share a fingerprint, so they map to the same stored snapshot; any changed input produces a different one.
 */
final class InputFingerprint {

    private InputFingerprint() {
    }

    static String of(String calculationVersion, String loanCode, String period,
                     BigDecimal interestIncome, BigDecimal feeIncome, BigDecimal fundingCost, BigDecimal operationalCost,
                     BigDecimal pd, BigDecimal lgd, BigDecimal ead) {
        // Normalise scale so 1.5 and 1.50 hash the same.
        String canonical = String.join("|",
                calculationVersion, loanCode, period,
                money(interestIncome), money(feeIncome), money(fundingCost), money(operationalCost),
                rate(pd), rate(lgd), money(ead));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String rate(BigDecimal v) {
        return v.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
