package com.loanlens.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyFormatAndSanitizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "62000.00     | ₹62,000",
            "500000.00    | ₹5,00,000",
            "-10000.00    | -₹10,000",
            "0.00         | ₹0",
            "999          | ₹999",
            "1234         | ₹1,234",
            "100000       | ₹1,00,000",
            "1234567.50   | ₹12,34,567.50",
            "12345678.9   | ₹1,23,45,678.90",
            "0.01         | ₹0.01",
            "-0.5         | -₹0.50"
    })
    void indianRupeeGrouping(String amount, String expected) {
        assertThat(MoneyFormat.rupees(new BigDecimal(amount))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"10, 10%", "1.2, 1.2%", "7.2500, 7.25%", "60.0, 60%", "100, 100%", "0, 0%"})
    void percentages(String value, String expected) {
        assertThat(MoneyFormat.percent(new BigDecimal(value))).isEqualTo(expected);
    }

    @Test
    void percentOfFractionKeepsTheValueExact() {
        Fact pd = Fact.percentOfFraction("pd", "PD", new BigDecimal("0.100000"));
        assertThat(pd.value()).isEqualByComparingTo("10");
        assertThat(pd.display()).isEqualTo("10%");
        assertThat(Fact.percentOfFraction("pd", "PD", new BigDecimal("0.012")).display()).isEqualTo("1.2%");
        assertThat(Fact.percentOfFraction("rate", "Rate", new BigDecimal("0.0875")).display()).isEqualTo("8.75%");
    }

    /* ---------- question scrubbing ---------- */

    private static final List<String> TERMS = List.of("Rahul Sharma", "Rahul", "Sharma", "C2001", "rahul.sharma@example.com", "+91 9000000001");

    @Test
    @DisplayName("the customer's name, code, e-mail and phone are removed from the question; the financial question stays")
    void removesCustomerIdentifiers() {
        String out = QuestionSanitizer.scrub("Why is Rahul Sharma (C2001) loss-making? Mail rahul.sharma@example.com or call +91 9000000001.", TERMS);

        assertThat(out).doesNotContain("Rahul").doesNotContain("Sharma").doesNotContain("C2001").doesNotContain("example.com")
                .doesNotContain("9000000001");
        assertThat(out).contains("the customer").contains("loss-making");
    }

    @Test
    void nameMatchingIsCaseInsensitiveAndWholeWord() {
        assertThat(QuestionSanitizer.scrub("why is RAHUL negative", TERMS)).isEqualTo("why is the customer negative");
        // "Rahulpur" is not the customer's name and is left alone
        assertThat(QuestionSanitizer.scrub("loans in Rahulpur", TERMS)).isEqualTo("loans in Rahulpur");
    }

    @Test
    @DisplayName("anything that looks like an e-mail or a long phone/account number is removed even if the backend does not know it")
    void removesUnknownEmailsAndLongNumbers() {
        String out = QuestionSanitizer.scrub("Contact someone.else@bank.org, phone 98765 43210, account 123456789012", List.of());
        assertThat(out).doesNotContain("someone.else").doesNotContain("98765").doesNotContain("123456789012");
        assertThat(out).contains("[email removed]").contains("[number removed]");
    }

    @Test
    void ordinaryAmountsAndYearsInTheQuestionSurvive() {
        String q = "What if funding cost rises by 10% in 2026 to ₹38,500?";
        assertThat(QuestionSanitizer.scrub(q, TERMS)).isEqualTo(q);
    }
}
