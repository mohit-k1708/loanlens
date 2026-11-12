package com.loanlens.ai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI explanations. The browser calls ONLY these endpoints: provider keys, database access and the prompt live on the
 * server. Request size is limited by {@link AiRequestSizeFilter}; request rate by {@link AiRateLimiter}.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiExplanationService service;
    private final AiRateLimiter rateLimiter;
    private final AiProvider provider;

    public AiController(AiExplanationService service, AiRateLimiter rateLimiter, AiProvider provider) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.provider = provider;
    }

    /** Explain one loan's profitability for one reporting period. Never changes any data. */
    @PostMapping("/explain")
    public AiExplanationResponse explain(@Valid @RequestBody AiExplainRequest request, HttpServletRequest http) {
        rateLimiter.check(http.getRemoteAddr());
        return service.explain(request);
    }

    /** Whether a real AI provider is configured. Safe to call anytime; contains no secret. */
    @GetMapping("/status")
    public AiStatusResponse status() {
        if (provider.isConfigured()) {
            return new AiStatusResponse(true, provider.name(), provider.model(), AiStatusResponse.MODE_AI,
                    "An AI provider is configured. Explanations are written by the AI model from backend-calculated figures and validated.");
        }
        return new AiStatusResponse(false, null, null, AiStatusResponse.MODE_FALLBACK_ONLY,
                "No AI provider is configured. Explanations are produced by fixed backend rules from the calculated figures and are labelled as such.");
    }
}
