package com.loanlens.ai;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loanlens.exception.GlobalExceptionHandler;
import com.loanlens.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP contract of the AI endpoints (the service is mocked). */
@WebMvcTest(AiController.class)
@Import({GlobalExceptionHandler.class, AiRequestSizeFilter.class})
class AiControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AiExplanationService service;
    @MockitoBean AiRateLimiter rateLimiter;
    @MockitoBean AiProvider provider;
    @MockitoBean AiProperties properties;

    private static AiExplanationResponse answer() {
        return new AiExplanationResponse("L1001", "2026", AiExplanationResponse.Source.AI, null, "anthropic", "claude-sonnet-5-5",
                AiExplanationResponse.DataStatus.COMPLETE, "Why?", "summary", List.of(), List.of(), List.of(), List.of("limit"),
                List.of(), true, Instant.parse("2026-12-31T10:15:30Z"));
    }

    private ResultActions explain(String json) throws Exception {
        return mvc.perform(post("/api/ai/explain").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("the brief's request shape works, including reportingPeriod sent as a number")
    void acceptsTheBriefsRequest() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);
        when(service.explain(any())).thenReturn(answer());

        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":2026,\"question\":\"Why is this loan showing a negative contribution?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value("L1001"))
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.summary").value("summary"))
                .andExpect(jsonPath("$.humanReviewRequired").value(true));

        ArgumentCaptor<AiExplainRequest> captured = ArgumentCaptor.forClass(AiExplainRequest.class);
        verify(service).explain(captured.capture());
        org.assertj.core.api.Assertions.assertThat(captured.getValue().reportingPeriod()).isEqualTo("2026");
        verify(rateLimiter).check(any());
    }

    @Test
    void periodAsStringAndOptionalComparisonLoan() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);
        when(service.explain(any())).thenReturn(answer());

        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"Compare with the other loan\",\"compareToLoanId\":\"L1002\"}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("invalid requests are rejected with field errors and never reach the service")
    void validation() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);

        explain("{\"reportingPeriod\":\"2026\",\"question\":\"Why?!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.loanId").exists());
        explain("{\"loanId\":\"L1001\",\"question\":\"Why?!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reportingPeriod").exists());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"26\",\"question\":\"Why?!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reportingPeriod").exists());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.question").exists());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"  \"}").andExpect(status().isBadRequest());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"ab\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.question").exists());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.question").exists());
        explain("{\"loanId\":\"L1001; DROP TABLE loans\",\"reportingPeriod\":\"2026\",\"question\":\"Why?!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.loanId").exists());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"Why?!\",\"compareToLoanId\":\"../etc\"}").andExpect(status().isBadRequest());
        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"Why?!\",\"apiKey\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.apiKey").exists());   // unknown fields are errors
        explain("not json").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a body over the size cap is refused with 413 before it is read")
    void requestSizeLimit() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(200);

        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"" + "x".repeat(300) + "\"}")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value(containsString("too large")));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("rate limit exceeded -> 429 with Retry-After and a clear message")
    void rateLimited() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);
        doThrow(new RateLimitExceededException(42)).when(rateLimiter).check(any());

        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"Why is it negative?\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.message").value(containsString("Too many AI requests")));
        verifyNoInteractions(service);
    }

    @Test
    void unknownLoanIs404() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);
        when(service.explain(any())).thenThrow(new ResourceNotFoundException("Loan L9999 was not found."));

        explain("{\"loanId\":\"L9999\",\"reportingPeriod\":\"2026\",\"question\":\"Why is it negative?\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Loan L9999 was not found."));
    }

    @Test
    @DisplayName("an unexpected failure is a generic 500 with an errorId and no internal detail")
    void unexpectedFailureIsSafe() throws Exception {
        when(properties.maxRequestBytes()).thenReturn(8192);
        when(service.explain(any())).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2"));

        explain("{\"loanId\":\"L1001\",\"reportingPeriod\":\"2026\",\"question\":\"Why is it negative?\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorId").exists())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("hunter2"))));
    }

    @Test
    @DisplayName("GET /api/ai/status: configured provider, and the unconfigured fallback-only mode; no secrets either way")
    void statusEndpoint() throws Exception {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.name()).thenReturn("anthropic");
        when(provider.model()).thenReturn("claude-sonnet-5-5");
        String configured = mvc.perform(get("/api/ai/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(true))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.mode").value("AI")).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(configured).doesNotContainIgnoringCase("key").doesNotContain("http");

        when(provider.isConfigured()).thenReturn(false);
        mvc.perform(get("/api/ai/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(false))
                .andExpect(jsonPath("$.provider").doesNotExist() )
                .andExpect(jsonPath("$.mode").value("FALLBACK_ONLY"))
                .andExpect(jsonPath("$.message").value(containsString("labelled")));
    }
}
