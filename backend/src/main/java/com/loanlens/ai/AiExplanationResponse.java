package com.loanlens.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the frontend receives. Three kinds of content are kept apart so the UI can label them honestly:
 * <ul>
 *   <li>{@code facts}: numbers calculated by the backend (authoritative);</li>
 *   <li>{@code summary}, {@code reasons[].explanation}: wording produced either by the AI model
 *       ({@code source = AI}) or by the deterministic backend template ({@code source = FALLBACK});</li>
 *   <li>{@code recommendations}: things for a HUMAN reviewer to examine. Never a decision.</li>
 * </ul>
 * The amount on every reason is supplied by the backend from {@code facts}; the AI never supplies amounts.
 */
public record AiExplanationResponse(
        String loanId,
        String reportingPeriod,
        Source source,
        /** Why the answer is not AI-generated; null when {@code source = AI}. */
        FallbackReason fallbackReason,
        /** Provider and model that wrote the wording; null for a fallback (no AI model was used). */
        String provider,
        String model,
        DataStatus dataStatus,
        String question,
        String summary,
        List<FactView> facts,
        List<Reason> reasons,
        List<String> recommendations,
        List<String> limitations,
        /** What is missing when {@code dataStatus = INSUFFICIENT}. */
        List<String> missingInputs,
        /** Always true: the explanation is informational and any decision needs an authorised person. */
        boolean humanReviewRequired,
        Instant generatedAt) {

    public enum Source { AI, FALLBACK }

    public enum FallbackReason {
        NOT_CONFIGURED, PROVIDER_ERROR,
        /** The provider rejected the server's credentials or permissions (401/403). */
        PROVIDER_AUTH,
        /** Billing, credit or payment problem on the provider account (402). */
        PROVIDER_BILLING,
        TIMEOUT, RATE_LIMITED, INVALID_AI_RESPONSE, INSUFFICIENT_DATA
    }

    public enum DataStatus { COMPLETE, INSUFFICIENT }

    public enum Impact { NEGATIVE, POSITIVE, NEUTRAL }

    public record FactView(String key, String label, BigDecimal value, String unit, String display) {
    }

    public record Reason(String factorKey, String factor, BigDecimal amount, String amountDisplay, Impact impact, String explanation) {
    }
}
