package com.loanlens.ai;

import com.loanlens.ai.AiExplanationResponse.Impact;
import java.util.List;

/**
 * The wording of an explanation, from either the AI model (after validation) or the deterministic template. A reason
 * names a fact by key only; the backend attaches the label and the amount, so wording can never carry a made-up figure.
 */
public record AiAnswerDraft(String summary, List<ReasonDraft> reasons, List<String> recommendations, List<String> limitations) {

    public record ReasonDraft(String factorKey, Impact impact, String explanation) {
    }

    public AiAnswerDraft {
        reasons = List.copyOf(reasons);
        recommendations = List.copyOf(recommendations);
        limitations = List.copyOf(limitations);
    }
}
