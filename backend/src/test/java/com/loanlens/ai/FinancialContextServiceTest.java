package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.exception.MissingInputsException;
import com.loanlens.service.LoanService;
import com.loanlens.service.ProfitabilityCalculator;
import com.loanlens.service.ProfitabilityService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinancialContextServiceTest {

    @Test
    @DisplayName("Rahul reference scenario: facts come from the authoritative calculation")
    void rahulFacts() {
        FinancialContext c = AiFixtures.rahul();

        assertThat(c.fact("totalIncome").display()).isEqualTo("₹62,000");
        assertThat(c.fact("interestIncome").display()).isEqualTo("₹60,000");
        assertThat(c.fact("feeIncome").display()).isEqualTo("₹2,000");
        assertThat(c.fact("fundingCost").display()).isEqualTo("₹35,000");
        assertThat(c.fact("operationalCost").display()).isEqualTo("₹7,000");
        assertThat(c.fact("totalCostsBeforeEcl").display()).isEqualTo("₹42,000");
        assertThat(c.fact("contributionBeforeEcl").display()).isEqualTo("₹20,000");
        assertThat(c.fact("expectedCreditLoss").display()).isEqualTo("₹30,000");
        assertThat(c.fact("contributionAfterEcl").display()).isEqualTo("-₹10,000");
        assertThat(c.fact("probabilityOfDefault").display()).isEqualTo("10%");
        assertThat(c.fact("lossGivenDefault").display()).isEqualTo("60%");
        assertThat(c.fact("exposureAtDefault").display()).isEqualTo("₹5,00,000");
        assertThat(c.fact("fundingRate").display()).isEqualTo("7%");
        assertThat(c.fact("averageOutstandingBalance").display()).isEqualTo("₹5,00,000");
        assertThat(c.contributionStatus()).isEqualTo("NEGATIVE");
        assertThat(c.riskRating()).isEqualTo("HIGH");
        assertThat(c.previousPeriod()).isNull();
        assertThat(c.comparisonLoan()).isNull();
    }

    @Test
    @DisplayName("funding-cost sensitivity is calculated by the backend (+10%, +25%, +50%) and is labelled hypothetical")
    void fundingSensitivity() {
        FinancialContext c = AiFixtures.rahul();

        assertThat(c.fact("whatIfFundingPlus10.fundingCost").display()).isEqualTo("₹38,500");
        assertThat(c.fact("whatIfFundingPlus10.contributionBeforeEcl").display()).isEqualTo("₹16,500");
        assertThat(c.fact("whatIfFundingPlus10.contributionAfterEcl").display()).isEqualTo("-₹13,500");
        assertThat(c.fact("whatIfFundingPlus25.fundingCost").display()).isEqualTo("₹43,750");
        assertThat(c.fact("whatIfFundingPlus25.contributionAfterEcl").display()).isEqualTo("-₹18,750");
        assertThat(c.fact("whatIfFundingPlus50.fundingCost").display()).isEqualTo("₹52,500");
        assertThat(c.fact("whatIfFundingPlus50.contributionBeforeEcl").display()).isEqualTo("₹2,500");
        assertThat(c.fact("whatIfFundingPlus50.contributionAfterEcl").display()).isEqualTo("-₹27,500");
        assertThat(c.fact("whatIfFundingPlus10.fundingCost").label()).contains("hypothetical");
    }

    @Test
    @DisplayName("previous period comparison uses only the earlier period's own record and assessment")
    void previousPeriod() {
        FinancialContext c = AiFixtures.rahul(true, false);

        assertThat(c.previousPeriod()).isEqualTo("2025");
        assertThat(c.fact("previous.expectedCreditLoss").display()).isEqualTo("₹15,000");
        assertThat(c.fact("previous.probabilityOfDefault").display()).isEqualTo("5%");
        assertThat(c.fact("previous.contributionAfterEcl").display()).isEqualTo("-₹6,000");
        assertThat(c.fact("change.expectedCreditLoss").display()).isEqualTo("₹15,000");        // 30,000 - 15,000
        assertThat(c.fact("change.contributionAfterEcl").display()).isEqualTo("-₹4,000");      // -10,000 - (-6,000)
    }

    @Test
    @DisplayName("a second loan adds only that loan's headline figures, and never its customer's identity")
    void comparisonLoan() {
        FinancialContext c = AiFixtures.rahul(false, true);

        assertThat(c.comparisonLoan()).isEqualTo("L1002");
        assertThat(c.fact("other.contributionAfterEcl").display()).isEqualTo("₹60,390");
        assertThat(c.fact("other.totalIncome").display()).isEqualTo("₹3,81,400");
        assertThat(c.facts().toString() + c.assumptions()).doesNotContain("Priya").doesNotContain("priya").doesNotContain("C2002");
    }

    @Test
    @DisplayName("the context a provider receives carries no customer name, code, e-mail or phone")
    void noIdentityDataInTheFacts() throws Exception {
        FinancialContext c = AiFixtures.rahul(true, true);
        String prompt = new AiPromptBuilder(new ObjectMapper()).userMessage(c, "Why is this loan negative?")
                + new AiPromptBuilder(new ObjectMapper()).systemPrompt();

        assertThat(prompt).doesNotContain("Rahul").doesNotContain("Sharma").doesNotContain(AiFixtures.RAHUL_CODE)
                .doesNotContain(AiFixtures.RAHUL_EMAIL).doesNotContain("9000000001").doesNotContain("example.com")
                .doesNotContain("Priya").doesNotContain("Nair");
        assertThat(prompt).contains("L1001").contains("\"key\" : \"fundingCost\"").contains("₹35,000").contains("<question>");
        // identifiers are kept ONLY for scrubbing the question, and are not part of the serialised prompt
        assertThat(c.sensitiveTerms()).contains(AiFixtures.RAHUL_NAME, "Rahul", "Sharma");
    }

    @Test
    @DisplayName("missing inputs are not papered over: the context cannot be built")
    void missingInputsPropagate() {
        ProfitabilityService profitability = mock(ProfitabilityService.class);
        when(profitability.evaluate("L1001", "2027")).thenThrow(new MissingInputsException("missing",
                List.of("Financial record for reporting period 2027", "Risk assessment for reporting period 2027")));
        FinancialContextService service = new FinancialContextService(profitability, mock(LoanService.class), new ProfitabilityCalculator());

        assertThatThrownBy(() -> service.build("L1001", "2027", null)).isInstanceOf(MissingInputsException.class);
    }

    @Test
    void invalidPeriodIsRejected() {
        FinancialContextService service = new FinancialContextService(mock(ProfitabilityService.class), mock(LoanService.class), new ProfitabilityCalculator());
        assertThatThrownBy(() -> service.build("L1001", "26", null)).isInstanceOf(com.loanlens.exception.BadRequestException.class);
    }
}
