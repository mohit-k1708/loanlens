package com.loanlens.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated, sanitized financial facts for one loan and one reporting period: the ONLY financial information an AI
 * provider ever receives.
 *
 * <p>It is built by the backend from the authoritative profitability calculation. It contains no customer name, code,
 * phone, e-mail or any other identity data. {@code sensitiveTerms} (the customer's own identifiers) are kept only so
 * the backend can scrub them out of the manager's free-text question; they are never serialised into a prompt.
 *
 * @param facts            every number the explanation may quote, in presentation order
 * @param assumptions      plain-language statements about how the figures were produced
 * @param previousPeriod   the earlier period used for the comparison facts, or null if there is none with complete data
 * @param comparisonLoan   loan code of the optional second loan whose facts are included, or null
 * @param sensitiveTerms   customer identifiers to redact from the question. NEVER sent to the provider
 */
public record FinancialContext(String loanCode, String reportingPeriod, String loanType, String loanStatus,
                               String riskRating, String contributionStatus, List<Fact> facts, List<String> assumptions,
                               String previousPeriod, String comparisonLoan, List<String> sensitiveTerms) {

    public FinancialContext {
        facts = List.copyOf(facts);
        assumptions = List.copyOf(assumptions);
        sensitiveTerms = List.copyOf(sensitiveTerms);
    }

    public Map<String, Fact> factsByKey() {
        Map<String, Fact> map = new LinkedHashMap<>();
        facts.forEach(f -> map.put(f.key(), f));
        return map;
    }

    public Fact fact(String key) {
        return factsByKey().get(key);
    }

    public boolean has(String key) {
        return factsByKey().containsKey(key);
    }
}
