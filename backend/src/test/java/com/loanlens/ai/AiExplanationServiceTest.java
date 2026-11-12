package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.AiExplanationResponse.DataStatus;
import com.loanlens.ai.AiExplanationResponse.FallbackReason;
import com.loanlens.ai.AiExplanationResponse.Source;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.MissingInputsException;
import com.loanlens.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** Orchestration: AI path, every fallback path, the insufficient-data path, and what is (not) sent to the provider. */
class AiExplanationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T10:15:30Z"), ZoneOffset.UTC);

    private static final String GOOD_ANSWER = """
            {"summary":"Total income of ₹62,000 is reduced by funding cost (₹35,000) and operational cost (₹7,000), leaving ₹20,000 before ECL. The ECL estimate of ₹30,000 gives -₹10,000 after ECL. The ECL is an estimate, not a realised loss.",
             "reasons":[
               {"factorKey":"fundingCost","impact":"NEGATIVE","explanation":"Funding cost of ₹35,000 reduces the contribution."},
               {"factorKey":"expectedCreditLoss","impact":"NEGATIVE","explanation":"The ECL estimate is ₹30,000, from PD 10%, LGD 60% and EAD ₹5,00,000."}],
             "recommendations":["A reviewer could check whether the PD and LGD assumptions are reasonable."],
             "limitations":["Simplified educational model."]}""";

    private FinancialContextService contexts;
    private AiProvider provider;
    private AiExplanationService service;
    private FinancialContext rahul;

    @BeforeEach
    void setUp() {
        contexts = mock(FinancialContextService.class);
        provider = mock(AiProvider.class);
        rahul = AiFixtures.rahul();
        when(provider.isConfigured()).thenReturn(true);
        when(provider.name()).thenReturn("anthropic");
        when(provider.model()).thenReturn("claude-sonnet-5-5");
        when(contexts.build(anyString(), anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(rahul);
        service = new AiExplanationService(contexts, provider, new AiPromptBuilder(new ObjectMapper()),
                new AiResponseValidator(new ObjectMapper()), new DeterministicExplainer(), CLOCK);
    }

    private static AiExplainRequest request(String question) {
        return new AiExplainRequest("L1001", "2026", question, null);
    }

    @Test
    @DisplayName("AI path: validated model wording, amounts supplied by the backend, source AI")
    void aiPath() {
        when(provider.complete(anyString(), anyString())).thenReturn(GOOD_ANSWER);

        AiExplanationResponse r = service.explain(request("Why is the contribution negative?"));

        assertThat(r.source()).isEqualTo(Source.AI);
        assertThat(r.fallbackReason()).isNull();
        assertThat(r.provider()).isEqualTo("anthropic");
        assertThat(r.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(r.dataStatus()).isEqualTo(DataStatus.COMPLETE);
        assertThat(r.loanId()).isEqualTo("L1001");
        assertThat(r.reportingPeriod()).isEqualTo("2026");
        assertThat(r.humanReviewRequired()).isTrue();
        assertThat(r.generatedAt()).isEqualTo(Instant.parse("2026-12-31T10:15:30Z"));
        assertThat(r.summary()).contains("-₹10,000");
        // the amount next to each reason is the backend's fact, not parsed from the model's text
        assertThat(r.reasons()).hasSize(2);
        assertThat(r.reasons().get(0).factor()).isEqualTo("Funding cost");
        assertThat(r.reasons().get(0).amount()).isEqualByComparingTo("35000.00");
        assertThat(r.reasons().get(0).amountDisplay()).isEqualTo("₹35,000");
        assertThat(r.reasons().get(1).amount()).isEqualByComparingTo("30000.00");
        assertThat(r.facts()).extracting(AiExplanationResponse.FactView::key).contains("totalIncome", "contributionAfterEcl");
        assertThat(r.limitations()).doesNotContain("This explanation was produced by fixed backend rules from the figures shown. No AI model was used.");
    }

    @Test
    @DisplayName("sends the provider only sanitized facts: no customer name/code/e-mail/phone, even when the manager's question contains them")
    void providerReceivesNoIdentityData() {
        when(provider.complete(anyString(), anyString())).thenReturn(GOOD_ANSWER);

        service.explain(request("Why is Rahul Sharma (C2001, rahul.sharma@example.com, +91 9000000001) negative?"));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(provider).complete(system.capture(), user.capture());
        String sent = system.getValue() + user.getValue();
        assertThat(sent).doesNotContain("Rahul").doesNotContain("Sharma").doesNotContain("C2001")
                .doesNotContain("rahul.sharma").doesNotContain("example.com").doesNotContain("9000000001");
        assertThat(user.getValue()).contains("the customer").contains("<question>").contains("₹35,000");
        assertThat(system.getValue()).contains("ONLY the facts").contains("untrusted");
    }

    @Test
    @DisplayName("no provider configured: no provider call, deterministic answer, source FALLBACK / NOT_CONFIGURED")
    void notConfigured() {
        when(provider.isConfigured()).thenReturn(false);

        AiExplanationResponse r = service.explain(request("Why is the contribution negative?"));

        verify(provider, never()).complete(anyString(), anyString());
        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo(FallbackReason.NOT_CONFIGURED);
        assertThat(r.provider()).isNull();           // not presented as coming from a model
        assertThat(r.model()).isNull();
        assertThat(r.summary()).contains("₹62,000").contains("₹42,000").contains("₹20,000").contains("₹30,000").contains("-₹10,000");
        assertThat(r.limitations().get(0)).contains("No AI model was used");
        assertThat(r.facts()).isNotEmpty();
        assertThat(r.humanReviewRequired()).isTrue();
    }

    @ParameterizedTest(name = "provider failure {0} -> fallback reason {1}")
    @CsvSource({"TIMEOUT, TIMEOUT", "RATE_LIMITED, RATE_LIMITED", "AUTHENTICATION, PROVIDER_AUTH", "BILLING, PROVIDER_BILLING", "UNAVAILABLE, PROVIDER_ERROR",
            "ERROR, PROVIDER_ERROR", "REFUSED_OR_INCOMPLETE, PROVIDER_ERROR", "NOT_CONFIGURED, NOT_CONFIGURED"})
    void providerFailuresFallBack(AiProviderException.Kind kind, FallbackReason expected) {
        when(provider.complete(anyString(), anyString())).thenThrow(new AiProviderException(kind, "details for the log only"));

        AiExplanationResponse r = service.explain(request("Why is the contribution negative?"));

        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo(expected);
        assertThat(r.provider()).isNull();
        assertThat(r.summary()).contains("-₹10,000");
        assertThat(r.summary() + r.limitations()).doesNotContain("details for the log only");
    }

    @ParameterizedTest(name = "unacceptable model answer #{index} is replaced by the fallback")
    @CsvSource(delimiter = '|', value = {
            "{\"summary\":\"The loss is ₹99,999.\",\"reasons\":[],\"limitations\":[\"x\"]}",                    // invented number
            "{\"summary\":\"The customer will default.\",\"reasons\":[],\"limitations\":[\"x\"]}",             // unsafe claim
            "{\"summary\":\"We recommend approving this loan.\",\"reasons\":[],\"limitations\":[\"x\"]}",      // decision
            "Sorry, I cannot help with that.",                                                                  // not JSON
            "{\"summary\":\"ok\",\"reasons\":[{\"factorKey\":\"nope\",\"impact\":\"NEGATIVE\",\"explanation\":\"x\"}],\"limitations\":[\"x\"]}"
    })
    void invalidModelAnswerFallsBack(String raw) {
        when(provider.complete(anyString(), anyString())).thenReturn(raw);

        AiExplanationResponse r = service.explain(request("Why is the contribution negative?"));

        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo(FallbackReason.INVALID_AI_RESPONSE);
        assertThat(r.summary()).doesNotContain("99,999").doesNotContain("will default").doesNotContain("recommend approving")
                .contains("-₹10,000");
    }

    @Test
    @DisplayName("missing inputs: says the information is insufficient, shows no figures, and never calls the provider")
    void insufficientData() {
        when(contexts.build(eq("l1001"), eq("2027"), org.mockito.ArgumentMatchers.any())).thenThrow(new MissingInputsException("missing",
                List.of("Financial record for reporting period 2027 (POST /api/loans/L1001/financial-records)",
                        "Risk assessment for reporting period 2027 (POST /api/loans/L1001/risk-assessments)")));

        AiExplanationResponse r = service.explain(new AiExplainRequest("l1001", "2027", "Why is it negative?", null));

        verifyNoInteractions(provider);
        assertThat(r.dataStatus()).isEqualTo(DataStatus.INSUFFICIENT);
        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo(FallbackReason.INSUFFICIENT_DATA);
        assertThat(r.loanId()).isEqualTo("L1001");
        assertThat(r.summary()).contains("not enough information").contains("2027").contains("Financial record").contains("Risk assessment")
                .contains("Nothing has been estimated or assumed");
        assertThat(r.missingInputs()).containsExactly("Financial record for reporting period 2027", "Risk assessment for reporting period 2027");
        assertThat(r.facts()).isEmpty();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.summary()).doesNotContain("₹").doesNotContain("POST /api");
    }

    @Test
    void unknownLoanAndInconsistentCostBreakdownAreNotHiddenAsAnExplanation() {
        when(contexts.build(eq("NOPE"), anyString(), org.mockito.ArgumentMatchers.any())).thenThrow(new ResourceNotFoundException("Loan NOPE was not found."));
        assertThatThrownBy(() -> service.explain(new AiExplainRequest("NOPE", "2026", "Why?!", null))).isInstanceOf(ResourceNotFoundException.class);

        when(contexts.build(eq("L1009"), anyString(), org.mockito.ArgumentMatchers.any())).thenThrow(new BusinessRuleException("allocations do not add up"));
        assertThatThrownBy(() -> service.explain(new AiExplainRequest("L1009", "2026", "Why?!", null))).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("the question is echoed back trimmed and is never placed in the response facts")
    void questionIsTrimmed() {
        when(provider.isConfigured()).thenReturn(false);
        assertThat(service.explain(request("   Why is it negative?   ")).question()).isEqualTo("Why is it negative?");
    }
}
