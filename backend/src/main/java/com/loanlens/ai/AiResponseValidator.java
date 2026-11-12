package com.loanlens.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanlens.ai.AiAnswerDraft.ReasonDraft;
import com.loanlens.ai.AiExplanationResponse.Impact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses and checks the model's reply BEFORE anything is shown. The model is not trusted:
 *
 * <ul>
 *   <li>the reply must be the agreed JSON shape, with bounded lengths and counts;</li>
 *   <li>every reason must name a real fact key; the amount shown is the backend's, never the model's;</li>
 *   <li><b>no invented numbers</b>: every rupee amount, percentage and larger number in the text must be one of the
 *       backend facts (or the reporting years); small counts (0-12) are allowed;</li>
 *   <li>the safety rules are enforced in code, not only requested in the prompt: no claim that a customer will default,
 *       no realised-loss claim, no approve/reject/repricing recommendation (a nearby negation, such as
 *       "does not mean the borrower has defaulted", is allowed).</li>
 * </ul>
 * Anything that fails throws {@link InvalidAiResponseException}; the caller then answers with the deterministic fallback.
 */
@Component
public class AiResponseValidator {

    static final int MAX_SUMMARY = 1400;
    static final int MAX_TEXT = 600;
    static final int MAX_REASONS = 8;
    static final int MAX_RECOMMENDATIONS = 6;
    static final int MAX_LIMITATIONS = 6;

    /** Keys whose effect on contribution is fixed by arithmetic: the backend, not the model, decides their impact. */
    private static final Set<String> COST_KEYS = Set.of("fundingCost", "operationalCost", "totalCostsBeforeEcl", "expectedCreditLoss");
    private static final Set<String> INCOME_KEYS = Set.of("interestIncome", "feeIncome", "totalIncome");

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_.])[-−]?(₹\\s?)?(\\d[\\d,]*(?:\\.\\d+)?)\\s?(%)?");

    /** Phrases that are never acceptable unless clearly negated ("does not mean ..."). */
    private static final List<Pattern> FORBIDDEN = List.of(
            p("\\b(will|is going to|is likely to|is certain to|is bound to|are going to)\\s+(default|fail to repay|stop paying|not repay|go into default)\\b"),
            p("\\bwill\\s+(certainly\\s+|definitely\\s+|eventually\\s+)?(default|defaults)\\b"),
            p("\\b(should|must|recommend|recommended|advise|advised|suggest|suggested|urge)\\b[^.!?]{0,40}\\b(approve|approving|reject|rejecting|decline|declining|deny|denying|foreclose|foreclosing|write[- ]?off|writing off|recall|recalling)\\b"),
            p("\\b(approve|reject|decline|deny|foreclose on|write off|recall)\\s+(the|this|that|their)\\s+loan\\b"),
            p("\\b(increase|raise|hike|lower|reduce|cut|change|adjust|set|reprice|re-price)\\s+(the\\s+|its\\s+|this\\s+|their\\s+)?(customer\\s+|lending\\s+)?(interest\\s+rate|pricing|price)\\b"),
            p("\\b(actual|realised|realized|incurred|booked)\\s+(credit\\s+)?loss(es)?\\b"),
            p("\\b(has|have|had)\\s+(already\\s+)?(lost|incurred|suffered|written off)\\b"),
            p("\\balready\\s+(lost|incurred|suffered)\\b"),
            p("\\b(has|have)\\s+defaulted\\b"),
            p("\\b(is|are)\\s+in\\s+default\\b"));

    private static final Pattern NEGATION = p("\\b(not|no|never|isn't|aren't|doesn't|don't|cannot|can't|nor|without|rather than|instead of|neither)\\b");

    private final ObjectMapper mapper;

    public AiResponseValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AiAnswerDraft validate(String raw, FinancialContext ctx) {
        JsonNode root = parse(raw);
        Map<String, Fact> facts = ctx.factsByKey();

        String summary = requiredText(root, "summary", MAX_SUMMARY);
        List<ReasonDraft> reasons = new ArrayList<>();
        JsonNode reasonNodes = root.get("reasons");
        if (reasonNodes != null && !reasonNodes.isNull()) {
            if (!reasonNodes.isArray() || reasonNodes.size() > MAX_REASONS) {
                throw new InvalidAiResponseException("reasons must be an array of at most " + MAX_REASONS + " items");
            }
            for (JsonNode r : reasonNodes) {
                String key = requiredText(r, "factorKey", 80);
                if (!facts.containsKey(key)) {
                    throw new InvalidAiResponseException("reason refers to an unknown fact key");
                }
                String text = requiredText(r, "explanation", MAX_TEXT);
                reasons.add(new ReasonDraft(key, impactFor(key, r.path("impact").asText("")), text));
            }
        }
        List<String> recommendations = textList(root, "recommendations", MAX_RECOMMENDATIONS, false);
        List<String> limitations = textList(root, "limitations", MAX_LIMITATIONS, true);

        List<String> allText = new ArrayList<>();
        allText.add(summary);
        reasons.forEach(r -> allText.add(r.explanation()));
        allText.addAll(recommendations);
        allText.addAll(limitations);
        for (String t : allText) {
            checkNumbers(t, ctx);
            checkForbiddenPhrases(t, ctx);
        }
        return new AiAnswerDraft(summary, reasons, recommendations, limitations);
    }

    /* ---------------- parsing ---------------- */

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAiResponseException("empty reply");
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidAiResponseException("reply is not a JSON object");
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw new InvalidAiResponseException("reply is not a JSON object");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new InvalidAiResponseException("reply is not valid JSON");
        }
    }

    private static String requiredText(JsonNode node, String field, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new InvalidAiResponseException("missing text field: " + field);
        }
        String text = v.asText().trim();
        if (text.length() > max) {
            throw new InvalidAiResponseException("field too long: " + field);
        }
        return text;
    }

    private static List<String> textList(JsonNode root, String field, int maxItems, boolean required) {
        JsonNode arr = root.get(field);
        if (arr == null || arr.isNull()) {
            if (required) {
                throw new InvalidAiResponseException("missing list: " + field);
            }
            return List.of();
        }
        if (!arr.isArray() || arr.size() > maxItems || (required && arr.isEmpty())) {
            throw new InvalidAiResponseException(field + " must be a list of at most " + maxItems + " items");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isTextual() || n.asText().isBlank() || n.asText().length() > MAX_TEXT) {
                throw new InvalidAiResponseException(field + " contains an invalid item");
            }
            out.add(n.asText().trim());
        }
        return out;
    }

    private static Impact impactFor(String key, String claimed) {
        if (COST_KEYS.contains(key) || key.startsWith("allocation.")) {
            return Impact.NEGATIVE;
        }
        if (INCOME_KEYS.contains(key)) {
            return Impact.POSITIVE;
        }
        try {
            return Impact.valueOf(claimed.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidAiResponseException("impact must be NEGATIVE, POSITIVE or NEUTRAL");
        }
    }

    /* ---------------- no invented numbers ---------------- */

    static void checkNumbers(String text, FinancialContext ctx) {
        Set<String> rupees = new HashSet<>();
        Set<String> percents = new HashSet<>();
        Set<String> any = new HashSet<>();
        for (Fact f : ctx.facts()) {
            String v = canonical(f.value().abs());
            any.add(v);
            (f.unit() == FactUnit.RUPEES ? rupees : percents).add(v);
        }
        for (int pct : FinancialContextService.FUNDING_STRESS_PERCENTS) {
            percents.add(String.valueOf(pct));
            any.add(String.valueOf(pct));
        }
        Set<String> years = new HashSet<>();
        years.add(ctx.reportingPeriod());
        years.add(String.valueOf(Integer.parseInt(ctx.reportingPeriod()) - 1));   // "no data for 2025" is a legitimate statement

        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            boolean rupee = m.group(1) != null;
            boolean percent = m.group(3) != null;
            String digits = m.group(2).replace(",", "");
            String value = canonical(new BigDecimal(digits));
            boolean ok;
            if (rupee) {
                ok = rupees.contains(value);
            } else if (percent) {
                ok = percents.contains(value);
            } else {
                boolean small = !digits.contains(".") && digits.length() <= 2 && Integer.parseInt(digits) <= 12;
                ok = small || years.contains(digits) || any.contains(value);
            }
            if (!ok) {
                throw new InvalidAiResponseException("text contains a number that is not one of the supplied facts");
            }
        }
    }

    private static String canonical(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    /* ---------------- safety phrases ---------------- */

    static void checkForbiddenPhrases(String text, FinancialContext ctx) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern p : FORBIDDEN) {
            Matcher m = p.matcher(lower);
            while (m.find()) {
                if ("DEFAULTED".equals(ctx.loanStatus()) && m.group().contains("default")) {
                    continue;   // the loan's recorded status really is DEFAULTED
                }
                // Only a negation in the SAME sentence and shortly before the phrase counts ("... does not mean the borrower has defaulted").
                String before = lower.substring(Math.max(0, m.start() - 45), m.start());
                int sentenceEnd = Math.max(Math.max(before.lastIndexOf('.'), before.lastIndexOf('!')),
                        Math.max(before.lastIndexOf('?'), before.lastIndexOf(';')));
                if (sentenceEnd >= 0) {
                    before = before.substring(sentenceEnd + 1);
                }
                if (!NEGATION.matcher(before).find() && !NEGATION.matcher(m.group()).find()) {
                    throw new InvalidAiResponseException("text breaks a safety rule");
                }
            }
        }
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
