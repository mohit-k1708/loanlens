package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.AiExplanationResponse.Impact;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeterministicExplainerTest {

    private final DeterministicExplainer explainer = new DeterministicExplainer();
    private final AiResponseValidator validator = new AiResponseValidator(new ObjectMapper());

    @Test
    @DisplayName("Rahul: the explanation states the backend's figures and calls the ECL an estimate")
    void rahulExplanation() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(), "Why is Rahul's contribution negative?");

        assertThat(d.summary())
                .contains("L1001").contains("2026")
                .contains("₹62,000")        // total income
                .contains("₹35,000").contains("₹7,000")   // funding + operational
                .contains("₹42,000")        // together reduce income by
                .contains("₹20,000")        // before ECL
                .contains("₹30,000")        // ECL estimate
                .contains("-₹10,000")       // after ECL
                .contains("PD 10%").contains("LGD 60%").contains("EAD ₹5,00,000")
                .contains("which is negative")
                .contains("estimate").contains("not a loss that has already occurred").contains("does not mean the borrower has defaulted");
        assertThat(d.reasons()).extracting(AiAnswerDraft.ReasonDraft::factorKey)
                .containsExactly("totalIncome", "fundingCost", "operationalCost", "expectedCreditLoss");
        assertThat(d.reasons()).extracting(AiAnswerDraft.ReasonDraft::impact)
                .containsExactly(Impact.POSITIVE, Impact.NEGATIVE, Impact.NEGATIVE, Impact.NEGATIVE);
        assertThat(String.join(" ", d.recommendations())).contains("human reviewer").contains("PD (10%)").contains("authorised staff");
        // never a decision
        assertThat(String.join(" ", d.recommendations()).toLowerCase()).doesNotContain("approve").doesNotContain("reject").doesNotContain("reprice");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Why is this loan showing a negative contribution?",
            "What is the largest cost for this loan?",
            "Why did ECL increase?",
            "What if funding costs increased?",
            "Which factors are reducing contribution?",
            "Why is this loan more profitable than another loan?",
            "Explain this in simple language.",
            "Tell me something unrelated"
    })
    @DisplayName("every suggested question produces text that passes the same safety/number validation as an AI answer")
    void everyTopicPassesTheValidator(String question) {
        for (boolean previous : new boolean[] {false, true}) {
            for (boolean comparison : new boolean[] {false, true}) {
                FinancialContext ctx = AiFixtures.rahul(previous, comparison);
                AiAnswerDraft d = explainer.explain(ctx, question);

                String asJson = toJson(d);
                AiAnswerDraft checked = validator.validate(asJson, ctx);   // throws if a number is invented or a rule is broken
                assertThat(checked.summary()).isEqualTo(d.summary());
                assertThat(d.limitations()).isNotEmpty();
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Why is this loan showing a negative contribution? | OVERVIEW",
            "What is the largest cost? | LARGEST_COST",
            "what is the biggest expense | LARGEST_COST",
            "Why did ECL increase? | ECL_CHANGE",
            "Why has the expected credit loss changed since last year? | ECL_CHANGE",
            "What if funding costs increased? | FUNDING_WHAT_IF",
            "what if the funding rate rises | FUNDING_WHAT_IF",
            "Which factors are reducing contribution? | REDUCING_FACTORS",
            "Why is this loan more profitable than another loan? | COMPARISON",
            "compare with the other loan | COMPARISON",
            "Explain this in simple language | SIMPLE",
            "hello | OVERVIEW"
    })
    void topicSelection(String question, DeterministicExplainer.Topic expected) {
        assertThat(DeterministicExplainer.topicOf(question)).isEqualTo(expected);
    }

    @Test
    @DisplayName("ECL change with a previous period: names the inputs that actually changed")
    void eclChangeWithPreviousPeriod() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(true, false), "Why did ECL increase?");

        assertThat(d.summary()).contains("₹30,000 in 2026").contains("₹15,000 in 2025").contains("higher")
                .contains("PD moved from 5% to 10%")
                .doesNotContain("LGD moved").doesNotContain("EAD moved");   // 60% and ₹5,00,000 did not change
        assertThat(d.summary()).contains("-₹6,000").contains("-₹10,000");
    }

    @Test
    @DisplayName("ECL change WITHOUT an earlier period says the information is insufficient and does not invent a comparison")
    void eclChangeWithoutPreviousPeriod() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(false, false), "Why did ECL increase?");

        assertThat(d.summary()).contains("not enough information").contains("2025").doesNotContain("higher").doesNotContain("lower");
        assertThat(d.limitations().get(0)).contains("insufficient");
    }

    @Test
    @DisplayName("funding what-if: backend-computed figures for +10%, +25%, +50%, labelled hypothetical")
    void fundingWhatIf() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(), "What if funding costs increased?");

        assertThat(d.summary()).contains("10% increase would make it ₹38,500").contains("-₹13,500")
                .contains("25% increase would make it ₹43,750").contains("-₹18,750")
                .contains("50% increase would make it ₹52,500").contains("-₹27,500")
                .contains("hypothetical").contains("not forecasts");
        assertThat(String.join(" ", d.limitations())).contains("10%, 25% and 50%");
    }

    @Test
    @DisplayName("largest cost for Rahul: funding cost ₹35,000 (larger than the ₹30,000 ECL estimate and the ₹7,000 operational cost)")
    void largestCostForRahulIsFundingCost() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(), "What is the largest cost?");

        assertThat(d.summary()).contains("is the funding cost at ₹35,000")
                .contains("Expected Credit Loss (ECL) estimate at ₹30,000").contains("operational cost at ₹7,000");
        assertThat(d.reasons()).extracting(AiAnswerDraft.ReasonDraft::factorKey)
                .containsExactly("fundingCost", "expectedCreditLoss", "operationalCost");
    }

    @Test
    void comparisonUsesBothLoansBackendFigures() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(false, true), "Why is Priya's loan more profitable than this one?");

        assertThat(d.summary()).contains("L1001 has a contribution after ECL of -₹10,000").contains("L1002 has ₹60,390")
                .contains("L1002 has the higher estimated contribution after ECL");
        assertThat(d.summary()).doesNotContain("Priya");
    }

    @Test
    void comparisonWithoutASecondLoanSaysInformationIsInsufficient() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(false, false), "Why is one loan more profitable than another?");

        assertThat(d.summary()).contains("not enough information to compare");
        assertThat(d.limitations().get(0)).contains("insufficient");
    }

    @Test
    void simpleLanguageStillUsesBackendNumbersAndStatesTheEstimate() {
        AiAnswerDraft d = explainer.explain(AiFixtures.rahul(), "Explain this in simple language");

        assertThat(d.summary()).startsWith("In simple terms:").contains("₹62,000").contains("₹42,000").contains("₹20,000")
                .contains("₹30,000").contains("-₹10,000").contains("estimate, not a loss that has happened");
    }

    private static String toJson(AiAnswerDraft d) {
        try {
            ObjectMapper m = new ObjectMapper();
            var root = m.createObjectNode();
            root.put("summary", d.summary());
            var reasons = root.putArray("reasons");
            d.reasons().forEach(r -> reasons.addObject().put("factorKey", r.factorKey()).put("impact", r.impact().name()).put("explanation", r.explanation()));
            var recs = root.putArray("recommendations");
            d.recommendations().forEach(recs::add);
            var lims = root.putArray("limitations");
            List<String> limitations = d.limitations();
            limitations.forEach(lims::add);
            return m.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
