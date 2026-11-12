package com.loanlens.ai;

import com.loanlens.ai.AiAnswerDraft.ReasonDraft;
import com.loanlens.ai.AiExplanationResponse.DataStatus;
import com.loanlens.ai.AiExplanationResponse.FactView;
import com.loanlens.ai.AiExplanationResponse.FallbackReason;
import com.loanlens.ai.AiExplanationResponse.Reason;
import com.loanlens.ai.AiExplanationResponse.Source;
import com.loanlens.exception.MissingInputsException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one explanation:
 * <pre>
 *   request -> validated financial context (backend calculation, no identity data)
 *           -> AI provider (if configured) -> validated answer   => source AI
 *           -> otherwise deterministic template                  => source FALLBACK (+ reason)
 * </pre>
 * The AI never reads the database and never calculates: it receives the sanitized facts and only words them. Its
 * answer is validated; on any failure the deterministic explanation is returned instead and labelled as such. Missing
 * data never reaches the provider: the answer says the information is insufficient.
 *
 * <p>Nothing sensitive is logged: not the question, the prompt, the answer or the API key.
 */
@Service
public class AiExplanationService {

    private static final Logger log = LoggerFactory.getLogger(AiExplanationService.class);

    private final FinancialContextService contexts;
    private final AiProvider provider;
    private final AiPromptBuilder prompts;
    private final AiResponseValidator validator;
    private final DeterministicExplainer fallbackExplainer;
    private final Clock clock;

    public AiExplanationService(FinancialContextService contexts, AiProvider provider, AiPromptBuilder prompts,
                                AiResponseValidator validator, DeterministicExplainer fallbackExplainer, Clock clock) {
        this.contexts = contexts;
        this.provider = provider;
        this.prompts = prompts;
        this.validator = validator;
        this.fallbackExplainer = fallbackExplainer;
        this.clock = clock;
    }

    public AiExplanationResponse explain(AiExplainRequest request) {
        long start = System.nanoTime();
        String question = request.question().trim();
        FinancialContext ctx;
        try {
            ctx = contexts.build(request.loanId().trim(), request.reportingPeriod(), request.compareToLoanId());
        } catch (MissingInputsException e) {
            log.info("AI explain: insufficient data for loan {} period {}", request.loanId(), request.reportingPeriod());
            return insufficient(request, question, e);
        }

        FallbackReason reason;
        if (!provider.isConfigured()) {
            reason = FallbackReason.NOT_CONFIGURED;
        } else {
            try {
                String scrubbed = QuestionSanitizer.scrub(question, ctx.sensitiveTerms());
                String raw = provider.complete(prompts.systemPrompt(), prompts.userMessage(ctx, scrubbed));
                AiAnswerDraft draft = validator.validate(raw, ctx);
                log.info("AI explain: loan {} period {} source=AI model={} ms={}", ctx.loanCode(), ctx.reportingPeriod(), provider.model(), millis(start));
                return respond(request, question, ctx, Source.AI, null, provider.name(), provider.model(), draft, List.of());
            } catch (AiProviderException e) {
                reason = switch (e.kind()) {
                    case TIMEOUT -> FallbackReason.TIMEOUT;
                    case RATE_LIMITED -> FallbackReason.RATE_LIMITED;
                    case NOT_CONFIGURED -> FallbackReason.NOT_CONFIGURED;
                    case AUTHENTICATION -> FallbackReason.PROVIDER_AUTH;
                    case BILLING -> FallbackReason.PROVIDER_BILLING;
                    default -> FallbackReason.PROVIDER_ERROR;
                };
                // The message is a fixed description (status code / kind); it never contains the key, prompt or answer.
                log.warn("AI explain: provider call failed ({}): {}", e.kind(), e.getMessage());
            } catch (InvalidAiResponseException e) {
                reason = FallbackReason.INVALID_AI_RESPONSE;
                log.warn("AI explain: model answer rejected by validation: {}", e.getMessage());
            }
        }

        AiAnswerDraft draft = fallbackExplainer.explain(ctx, question);
        log.info("AI explain: loan {} period {} source=FALLBACK reason={} ms={}", ctx.loanCode(), ctx.reportingPeriod(), reason, millis(start));
        return respond(request, question, ctx, Source.FALLBACK, reason, null, null, draft,
                List.of("This explanation was produced by fixed backend rules from the figures shown. No AI model was used."));
    }

    /** A statement of what is missing; nothing is estimated and the provider is not called. */
    private AiExplanationResponse insufficient(AiExplainRequest request, String question, MissingInputsException e) {
        List<String> missing = e.getMissingInputs().stream().map(m -> m.replaceAll("\\s*\\(.*\\)\\s*$", "")).toList();
        String summary = "There is not enough information to explain this loan for reporting period " + request.reportingPeriod()
                + ". Missing: " + String.join("; ", missing) + ". Nothing has been estimated or assumed in their place.";
        return new AiExplanationResponse(request.loanId().trim().toUpperCase(), request.reportingPeriod(), Source.FALLBACK,
                FallbackReason.INSUFFICIENT_DATA, null, null, DataStatus.INSUFFICIENT, question, summary, List.of(), List.of(),
                List.of("Add the missing records for this reporting period, then ask again."),
                List.of("No figures are shown because they cannot be calculated without the missing information."),
                missing, true, clock.instant());
    }

    private AiExplanationResponse respond(AiExplainRequest request, String question, FinancialContext ctx, Source source,
                                          FallbackReason reason, String providerName, String model, AiAnswerDraft draft,
                                          List<String> extraLimitations) {
        Map<String, Fact> byKey = ctx.factsByKey();
        List<Reason> reasons = new ArrayList<>();
        for (ReasonDraft r : draft.reasons()) {
            Fact f = byKey.get(r.factorKey());
            // The amount comes from the backend fact, never from the model's text.
            reasons.add(new Reason(r.factorKey(), f.label(), f.value(), f.display(), r.impact(), r.explanation()));
        }
        List<FactView> facts = ctx.facts().stream()
                .map(f -> new FactView(f.key(), f.label(), f.value(), f.unit().name(), f.display()))
                .toList();
        List<String> limitations = new ArrayList<>(extraLimitations);
        limitations.addAll(draft.limitations());
        return new AiExplanationResponse(ctx.loanCode(), ctx.reportingPeriod(), source, reason, providerName, model,
                DataStatus.COMPLETE, question, draft.summary(), facts, reasons, draft.recommendations(), limitations, List.of(),
                true, clock.instant());
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
