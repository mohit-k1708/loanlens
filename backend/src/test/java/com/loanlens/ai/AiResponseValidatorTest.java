package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.AiExplanationResponse.Impact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The model is not trusted: its reply is checked for shape, invented numbers and unsafe statements before it is shown. */
class AiResponseValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiResponseValidator validator = new AiResponseValidator(mapper);
    private final FinancialContext ctx = AiFixtures.rahul(true, false);

    private static String json(String summary) {
        return """
                {"summary": %s,
                 "reasons": [
                   {"factorKey":"fundingCost","impact":"NEGATIVE","explanation":"Funding cost of ₹35,000 reduces the contribution."},
                   {"factorKey":"expectedCreditLoss","impact":"NEGATIVE","explanation":"The ECL estimate is ₹30,000 (PD 10%% x LGD 60%% x EAD ₹5,00,000)."}],
                 "recommendations": ["A reviewer could check whether the PD of 10%% is reasonable."],
                 "limitations": ["Simplified educational model."]}""".formatted(quote(summary));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void accepts(String summary) {
        assertThat(validator.validate(json(summary), ctx).summary()).isEqualTo(summary);
    }

    private void rejects(String summary) {
        assertThatThrownBy(() -> validator.validate(json(summary), ctx)).isInstanceOf(InvalidAiResponseException.class);
    }

    @Test
    @DisplayName("a faithful explanation using only the supplied numbers is accepted")
    void acceptsFaithfulAnswer() {
        AiAnswerDraft draft = validator.validate(json("Total income is ₹62,000. Funding and operational costs of ₹42,000 leave ₹20,000 before ECL. "
                + "The ECL estimate of ₹30,000 gives -₹10,000 after ECL in 2026."), ctx);

        assertThat(draft.reasons()).hasSize(2);
        assertThat(draft.reasons().get(0).factorKey()).isEqualTo("fundingCost");
        assertThat(draft.recommendations()).hasSize(1);
        assertThat(draft.limitations()).hasSize(1);
    }

    @Test
    void toleratesCodeFencesAndSurroundingText() {
        String fenced = "Here is the answer:\n```json\n" + json("Contribution after ECL is -₹10,000.") + "\n```";
        assertThat(validator.validate(fenced, ctx).summary()).isEqualTo("Contribution after ECL is -₹10,000.");
    }

    /* ---------- invented numbers ---------- */

    @ParameterizedTest(name = "invented number rejected: {0}")
    @ValueSource(strings = {
            "Funding cost is ₹40,000.",                       // not a fact
            "Interest income is ₹61,000.",
            "The ECL is ₹31,000.",
            "The PD is 12%.",                                 // not a fact
            "Funding rate is 8%.",
            "Total income of ₹62,001 is reduced.",
            "The loan has an EAD of ₹6,00,000.",
            "Contribution is ₹1,23,456.",
            "Costs rose by 4500 rupees in 2019."              // large bare number that is no fact, nor a year in context
    })
    void rejectsInventedNumbers(String text) {
        rejects(text);
    }

    @ParameterizedTest(name = "supplied number accepted: {0}")
    @ValueSource(strings = {
            "Funding cost is ₹35,000.",
            "Funding cost is ₹35000.",
            "Funding cost is ₹35,000.00.",
            "The EAD is ₹5,00,000 and PD is 10%.",
            "Loss given default is 60%.",
            "Previous ECL was ₹15,000 in 2025 and is ₹30,000 in 2026.",
            "If funding cost rose 10% it would be ₹38,500; at 25% ₹43,750 and at 50% ₹52,500.",
            "There are 3 main factors over 12 months.",
            "Loan L1001 has a negative contribution.",         // the digits in a loan code are not a number
            "Contribution is -₹10,000.",
            "The negative is ₹10,000 in size."                  // sign is ignored when matching amounts
    })
    void acceptsSuppliedNumbers(String text) {
        accepts(text);
    }

    @Test
    @DisplayName("a rupee amount must match a rupee fact: 10% is not ₹10, and ₹10,000 is not a percentage")
    void unitsMustMatch() {
        rejects("The PD is 30,000%.");
        rejects("The amount is ₹10.");   // 10 is PD in percent, but no ₹10 fact exists
    }

    /* ---------- safety rules ---------- */

    @ParameterizedTest(name = "unsafe statement rejected: {0}")
    @ValueSource(strings = {
            "This customer will default on the loan.",
            "The borrower is going to default soon.",
            "The customer is likely to default.",
            "We recommend approving this loan.",
            "You should reject the loan.",
            "The bank should decline this application.",
            "Consider whether to write off the loan.",
            "Raise the interest rate to cover the cost.",
            "The bank should increase the interest rate.",
            "The bank has already lost ₹30,000 on this loan.",
            "The ₹30,000 is an actual loss.",
            "This is a realised loss for the bank.",
            "The borrower has defaulted.",
            "The loan is in default.",
            "Approve the loan because it is profitable."
    })
    void rejectsUnsafeStatements(String text) {
        rejects(text);
    }

    @ParameterizedTest(name = "negated statement allowed: {0}")
    @ValueSource(strings = {
            "This does not mean the borrower has defaulted.",
            "The ECL is not a realised loss.",
            "It is not an actual loss; it is an estimate.",
            "The negative figure does not mean the bank has already lost ₹30,000.",
            "This explanation does not recommend approving or rejecting the loan.",
            "No one can say the customer will default from these figures."
    })
    void allowsNegatedStatements(String text) {
        accepts(text);
    }

    @Test
    @DisplayName("a negation in an EARLIER sentence does not excuse a later unsafe claim")
    void negationDoesNotCarryAcrossSentences() {
        rejects("There is no missing data. The customer will default.");
    }

    /* ---------- shape ---------- */

    @ParameterizedTest(name = "malformed reply rejected: {0}")
    @ValueSource(strings = {
            "",
            "I cannot answer that.",
            "{not json}",
            "[1,2,3]",
            "{\"reasons\":[],\"limitations\":[\"x\"]}",                                                            // no summary
            "{\"summary\":\"\",\"reasons\":[],\"limitations\":[\"x\"]}",                                           // empty summary
            "{\"summary\":\"ok\",\"reasons\":[],\"recommendations\":[]}",                                          // limitations required
            "{\"summary\":\"ok\",\"reasons\":[],\"limitations\":[]}",                                              // limitations must not be empty
            "{\"summary\":\"ok\",\"reasons\":[{\"factorKey\":\"madeUp\",\"impact\":\"NEGATIVE\",\"explanation\":\"x\"}],\"limitations\":[\"x\"]}",   // unknown key
            "{\"summary\":\"ok\",\"reasons\":[{\"factorKey\":\"probabilityOfDefault\",\"impact\":\"BAD\",\"explanation\":\"x\"}],\"limitations\":[\"x\"]}",
            "{\"summary\":\"ok\",\"reasons\":{\"a\":1},\"limitations\":[\"x\"]}",
            "{\"summary\":\"ok\",\"reasons\":[],\"limitations\":[42]}"
    })
    void rejectsMalformedReplies(String raw) {
        assertThatThrownBy(() -> validator.validate(raw, ctx)).isInstanceOf(InvalidAiResponseException.class);
    }

    @Test
    void rejectsOversizedText() {
        rejects("x".repeat(AiResponseValidator.MAX_SUMMARY + 1));
    }

    @Test
    void rejectsTooManyReasons() {
        StringBuilder reasons = new StringBuilder();
        for (int i = 0; i <= AiResponseValidator.MAX_REASONS; i++) {
            reasons.append(i == 0 ? "" : ",").append("{\"factorKey\":\"fundingCost\",\"impact\":\"NEGATIVE\",\"explanation\":\"x\"}");
        }
        String raw = "{\"summary\":\"ok\",\"reasons\":[" + reasons + "],\"limitations\":[\"x\"]}";
        assertThatThrownBy(() -> validator.validate(raw, ctx)).isInstanceOf(InvalidAiResponseException.class);
    }

    @Test
    @DisplayName("the effect of income and costs is decided by arithmetic, not by the model: a wrong impact is corrected")
    void impactOfCostsAndIncomeIsFixed() {
        String raw = "{\"summary\":\"ok\",\"reasons\":["
                + "{\"factorKey\":\"fundingCost\",\"impact\":\"POSITIVE\",\"explanation\":\"x\"},"
                + "{\"factorKey\":\"totalIncome\",\"impact\":\"NEGATIVE\",\"explanation\":\"y\"},"
                + "{\"factorKey\":\"probabilityOfDefault\",\"impact\":\"neutral\",\"explanation\":\"z\"}],\"limitations\":[\"x\"]}";

        AiAnswerDraft draft = validator.validate(raw, ctx);

        assertThat(draft.reasons().get(0).impact()).isEqualTo(Impact.NEGATIVE);
        assertThat(draft.reasons().get(1).impact()).isEqualTo(Impact.POSITIVE);
        assertThat(draft.reasons().get(2).impact()).isEqualTo(Impact.NEUTRAL);
    }

    @Test
    @DisplayName("the error message never repeats the model's text")
    void errorDoesNotEchoTheAnswer() {
        assertThatThrownBy(() -> validator.validate(json("This customer will default with certainty SECRET-MARKER."), ctx))
                .isInstanceOf(InvalidAiResponseException.class)
                .hasMessageNotContaining("SECRET-MARKER").hasMessageNotContaining("default with certainty");
    }
}
