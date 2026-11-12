package com.loanlens.ai;

import com.loanlens.ai.AiAnswerDraft.ReasonDraft;
import com.loanlens.ai.AiExplanationResponse.Impact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The explanation used when no AI model can be used (no key configured, provider error, timeout, or an answer that
 * failed validation). It is NOT AI: it fills fixed sentence templates with the backend's own figures, and the response
 * says so ({@code source = FALLBACK}).
 *
 * <p>The question only selects which template is used (why negative / largest cost / ECL change / funding what-if /
 * which factors reduce contribution / comparison / plain language). Every number comes from {@link FinancialContext}.
 * Its output passes the same {@link AiResponseValidator} rules as a model answer (checked in tests).
 */
@Component
public class DeterministicExplainer {

    enum Topic { OVERVIEW, SIMPLE, LARGEST_COST, ECL_CHANGE, FUNDING_WHAT_IF, REDUCING_FACTORS, COMPARISON }

    public AiAnswerDraft explain(FinancialContext c, String question) {
        return switch (topicOf(question)) {
            case SIMPLE -> simple(c);
            case LARGEST_COST -> largestCost(c);
            case ECL_CHANGE -> eclChange(c);
            case FUNDING_WHAT_IF -> fundingWhatIf(c);
            case REDUCING_FACTORS -> reducingFactors(c);
            case COMPARISON -> comparison(c);
            default -> overview(c);
        };
    }

    static Topic topicOf(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean ecl = q.contains("ecl") || q.contains("credit loss") || q.contains("expected loss");
        if (q.contains("funding") && (q.contains("what if") || q.contains("if ") || q.contains("increase") || q.contains("rise")
                || q.contains("higher") || q.contains("rose") || q.contains("hike"))) {
            return Topic.FUNDING_WHAT_IF;
        }
        if (ecl && (q.contains("increas") || q.contains("chang") || q.contains("rise") || q.contains("rose") || q.contains("higher")
                || q.contains("went up") || q.contains("previous") || q.contains("last year") || q.contains("compared"))) {
            return Topic.ECL_CHANGE;
        }
        if (q.contains("compar") || q.contains(" than ") || q.contains("versus") || q.contains(" vs") || q.contains("another loan")
                || q.contains("other loan") || q.contains("more profitable")) {
            return Topic.COMPARISON;
        }
        if ((q.contains("largest") || q.contains("biggest") || q.contains("highest") || q.contains("main") || q.contains("most"))
                && (q.contains("cost") || q.contains("expense") || q.contains("deduction"))) {
            return Topic.LARGEST_COST;
        }
        if (q.contains("factor") || q.contains("reduce") || q.contains("reducing") || q.contains("lower") || q.contains("drag") || q.contains("hurt")) {
            return Topic.REDUCING_FACTORS;
        }
        if (q.contains("simple") || q.contains("simply") || q.contains("plain") || q.contains("layman") || q.contains("easy")
                || q.contains("eli5") || q.contains("non-technical")) {
            return Topic.SIMPLE;
        }
        return Topic.OVERVIEW;
    }

    /* ---------------- templates ---------------- */

    private AiAnswerDraft overview(FinancialContext c) {
        String summary = headline(c) + " " + bridge(c) + " " + eclCaveat(c);
        return new AiAnswerDraft(summary, coreReasons(c), recommendations(c), limitations(c));
    }

    private AiAnswerDraft simple(FinancialContext c) {
        String summary = "In simple terms: for " + c.reportingPeriod() + " this loan brings in " + d(c, "totalIncome")
                + ". Funding the loan and running it cost " + d(c, "totalCostsBeforeEcl") + ", which leaves " + d(c, "contributionBeforeEcl")
                + ". The bank also sets aside an estimate of possible future credit loss, " + d(c, "expectedCreditLoss")
                + ". After that estimate the figure is " + d(c, "contributionAfterEcl") + ". That last step is an estimate, not a loss that has happened.";
        return new AiAnswerDraft(summary, coreReasons(c), recommendations(c), limitations(c));
    }

    private AiAnswerDraft largestCost(FinancialContext c) {
        record Item(String key, String name, BigDecimal amount) {
        }
        List<Item> items = new ArrayList<>(List.of(
                new Item("fundingCost", "funding cost", c.fact("fundingCost").value()),
                new Item("operationalCost", "operational cost", c.fact("operationalCost").value()),
                new Item("expectedCreditLoss", "Expected Credit Loss (ECL) estimate", c.fact("expectedCreditLoss").value())));
        items.sort(Comparator.comparing(Item::amount).reversed());
        Item top = items.get(0);
        boolean tie = items.get(1).amount().compareTo(top.amount()) == 0;
        String summary = tie
                ? "In " + c.reportingPeriod() + " the largest deductions are tied at " + d(c, top.key()) + " ("
                  + top.name() + " and " + items.get(1).name() + "). " + eclCaveat(c)
                : "The largest deduction from income for this loan in " + c.reportingPeriod() + " is the " + top.name() + " at "
                  + d(c, top.key()) + ". The others are " + items.get(1).name() + " at " + d(c, items.get(1).key()) + " and "
                  + items.get(2).name() + " at " + d(c, items.get(2).key()) + ". " + eclCaveat(c);
        List<ReasonDraft> reasons = new ArrayList<>();
        for (Item it : items) {
            reasons.add(new ReasonDraft(it.key(), Impact.NEGATIVE, "The " + it.name() + " is " + d(c, it.key()) + "."));
        }
        return new AiAnswerDraft(summary, reasons, recommendations(c), limitations(c));
    }

    private AiAnswerDraft eclChange(FinancialContext c) {
        if (c.previousPeriod() == null) {
            String summary = "There is not enough information to explain a change in ECL: " + (Integer.parseInt(c.reportingPeriod()) - 1)
                    + " has no complete financial record and risk assessment for this loan, so nothing earlier can be compared. "
                    + "The ECL estimate for " + c.reportingPeriod() + " is " + d(c, "expectedCreditLoss") + " (PD " + d(c, "probabilityOfDefault")
                    + " x LGD " + d(c, "lossGivenDefault") + " x EAD " + d(c, "exposureAtDefault") + "). " + eclCaveat(c);
            List<String> limits = new ArrayList<>(limitations(c));
            limits.add(0, "Information is insufficient to explain a change: there is no earlier period with complete data.");
            return new AiAnswerDraft(summary, List.of(new ReasonDraft("expectedCreditLoss", Impact.NEGATIVE,
                    "The ECL estimate is " + d(c, "expectedCreditLoss") + " for " + c.reportingPeriod() + ".")), recommendations(c), limits);
        }
        String prev = c.previousPeriod();
        StringBuilder changed = new StringBuilder();
        List<ReasonDraft> reasons = new ArrayList<>();
        reasons.add(new ReasonDraft("expectedCreditLoss", Impact.NEGATIVE, "The ECL estimate is " + d(c, "expectedCreditLoss")
                + " in " + c.reportingPeriod() + " compared with " + d(c, "previous.expectedCreditLoss") + " in " + prev + "."));
        for (String[] in : new String[][] {{"probabilityOfDefault", "PD"}, {"lossGivenDefault", "LGD"}, {"exposureAtDefault", "EAD"}}) {
            Fact now = c.fact(in[0]);
            Fact before = c.fact("previous." + in[0]);
            if (now.value().compareTo(before.value()) == 0) {
                continue;
            }
            if (!changed.isEmpty()) {
                changed.append("; ");
            }
            changed.append(in[1]).append(" moved from ").append(before.display()).append(" to ").append(now.display());
            reasons.add(new ReasonDraft(in[0], Impact.NEUTRAL, in[1] + " is " + now.display() + " in " + c.reportingPeriod()
                    + " compared with " + before.display() + " in " + prev + "."));
        }
        String direction = c.fact("expectedCreditLoss").value().compareTo(c.fact("previous.expectedCreditLoss").value()) > 0 ? "higher"
                : c.fact("expectedCreditLoss").value().compareTo(c.fact("previous.expectedCreditLoss").value()) < 0 ? "lower" : "unchanged";
        String summary = "The ECL estimate is " + d(c, "expectedCreditLoss") + " in " + c.reportingPeriod() + " versus "
                + d(c, "previous.expectedCreditLoss") + " in " + prev + ("unchanged".equals(direction) ? " (unchanged)" : " (" + direction + ")")
                + ". ECL = PD x LGD x EAD, so a change comes only from those three inputs. "
                + (changed.isEmpty() ? "None of them changed between the two periods. " : "Inputs that changed: " + changed + ". ")
                + "Contribution after ECL went from " + d(c, "previous.contributionAfterEcl") + " to " + d(c, "contributionAfterEcl") + ". "
                + eclCaveat(c);
        return new AiAnswerDraft(summary, reasons, recommendations(c), limitations(c));
    }

    private AiAnswerDraft fundingWhatIf(FinancialContext c) {
        StringBuilder sb = new StringBuilder("Funding cost is " + d(c, "fundingCost") + " in " + c.reportingPeriod()
                + " and contribution after ECL is " + d(c, "contributionAfterEcl") + ". If funding cost were higher, with everything else unchanged: ");
        List<ReasonDraft> reasons = new ArrayList<>();
        reasons.add(new ReasonDraft("fundingCost", Impact.NEGATIVE, "Funding cost is " + d(c, "fundingCost") + " today."));
        List<Integer> steps = FinancialContextService.FUNDING_STRESS_PERCENTS;
        for (int i = 0; i < steps.size(); i++) {
            int pct = steps.get(i);
            String tag = "whatIfFundingPlus" + pct + ".";
            sb.append(i == 0 ? "" : i == steps.size() - 1 ? "; and " : "; ").append("a ").append(pct).append("% increase would make it ")
                    .append(d(c, tag + "fundingCost")).append(" and contribution after ECL ").append(d(c, tag + "contributionAfterEcl"));
            reasons.add(new ReasonDraft(tag + "contributionAfterEcl", Impact.NEUTRAL,
                    "With funding cost " + pct + "% higher, contribution after ECL would be " + d(c, tag + "contributionAfterEcl") + "."));
        }
        sb.append(". These are hypothetical calculations, not forecasts.");
        List<String> limits = new ArrayList<>(limitations(c));
        limits.add("Only increases of 10%, 25% and 50% are calculated; other percentages are not available here.");
        return new AiAnswerDraft(sb.toString(), reasons, recommendations(c), limits);
    }

    private AiAnswerDraft reducingFactors(FinancialContext c) {
        String summary = "Three factors reduce the estimated contribution for " + c.reportingPeriod() + ": funding cost (" + d(c, "fundingCost")
                + "), operational cost (" + d(c, "operationalCost") + ") and the ECL estimate (" + d(c, "expectedCreditLoss")
                + "). Total income of " + d(c, "totalIncome") + " offsets them, leaving " + d(c, "contributionAfterEcl") + " after ECL. "
                + eclCaveat(c);
        return new AiAnswerDraft(summary, coreReasons(c), recommendations(c), limitations(c));
    }

    private AiAnswerDraft comparison(FinancialContext c) {
        if (c.comparisonLoan() == null) {
            String summary = "There is not enough information to compare: no second loan with complete data for " + c.reportingPeriod()
                    + " was selected. Choose another loan in the comparison selector and ask again. "
                    + "For " + c.loanCode() + ", the estimated contribution after ECL is " + d(c, "contributionAfterEcl") + ".";
            List<String> limits = new ArrayList<>(limitations(c));
            limits.add(0, "Information is insufficient for a comparison: no second loan with complete data was provided.");
            return new AiAnswerDraft(summary, coreReasons(c), recommendations(c), limits);
        }
        String other = c.comparisonLoan();
        int cmp = c.fact("contributionAfterEcl").value().compareTo(c.fact("other.contributionAfterEcl").value());
        String verdict = cmp > 0 ? c.loanCode() + " has the higher estimated contribution after ECL"
                : cmp < 0 ? other + " has the higher estimated contribution after ECL" : "both loans have the same estimated contribution after ECL";
        String summary = "For " + c.reportingPeriod() + ", " + c.loanCode() + " has a contribution after ECL of " + d(c, "contributionAfterEcl")
                + " and " + other + " has " + d(c, "other.contributionAfterEcl") + "; " + verdict + ". "
                + "Total income is " + d(c, "totalIncome") + " versus " + d(c, "other.totalIncome") + ", funding cost " + d(c, "fundingCost")
                + " versus " + d(c, "other.fundingCost") + ", operational cost " + d(c, "operationalCost") + " versus " + d(c, "other.operationalCost")
                + ", and the ECL estimate " + d(c, "expectedCreditLoss") + " versus " + d(c, "other.expectedCreditLoss")
                + " (this loan versus " + other + "). " + eclCaveat(c);
        List<ReasonDraft> reasons = List.of(
                new ReasonDraft("totalIncome", Impact.POSITIVE, "Total income: " + d(c, "totalIncome") + " here versus " + d(c, "other.totalIncome") + " for " + other + "."),
                new ReasonDraft("fundingCost", Impact.NEGATIVE, "Funding cost: " + d(c, "fundingCost") + " here versus " + d(c, "other.fundingCost") + " for " + other + "."),
                new ReasonDraft("operationalCost", Impact.NEGATIVE, "Operational cost: " + d(c, "operationalCost") + " here versus " + d(c, "other.operationalCost") + " for " + other + "."),
                new ReasonDraft("expectedCreditLoss", Impact.NEGATIVE, "ECL estimate: " + d(c, "expectedCreditLoss") + " here versus " + d(c, "other.expectedCreditLoss") + " for " + other + "."));
        List<String> limits = new ArrayList<>(limitations(c));
        limits.add("The loans differ in size, term and risk; the comparison shows each loan's own figures and does not judge which is better to hold.");
        return new AiAnswerDraft(summary, reasons, recommendations(c), limits);
    }

    /* ---------------- shared pieces ---------------- */

    private static String headline(FinancialContext c) {
        String status = switch (c.contributionStatus()) {
            case "NEGATIVE" -> "which is negative";
            case "POSITIVE" -> "which is positive";
            default -> "which is exactly break-even";
        };
        return "For reporting period " + c.reportingPeriod() + ", loan " + c.loanCode() + " has an estimated contribution after ECL of "
                + d(c, "contributionAfterEcl") + ", " + status + ".";
    }

    private static String bridge(FinancialContext c) {
        return "Total income of " + d(c, "totalIncome") + " is reduced by funding cost (" + d(c, "fundingCost") + ") and operational cost ("
                + d(c, "operationalCost") + "), which together are " + d(c, "totalCostsBeforeEcl") + ", leaving " + d(c, "contributionBeforeEcl")
                + " before ECL. The ECL estimate of " + d(c, "expectedCreditLoss") + " (PD " + d(c, "probabilityOfDefault") + " x LGD "
                + d(c, "lossGivenDefault") + " x EAD " + d(c, "exposureAtDefault") + ") is then deducted, giving " + d(c, "contributionAfterEcl") + ".";
    }

    private static String eclCaveat(FinancialContext c) {
        return "The ECL is an estimate of potential credit loss calculated from PD, LGD and EAD; it is not a loss that has already occurred, "
                + "and it does not mean the borrower has defaulted.";
    }

    private static List<ReasonDraft> coreReasons(FinancialContext c) {
        return List.of(
                new ReasonDraft("totalIncome", Impact.POSITIVE, "Interest income of " + d(c, "interestIncome") + " plus fee income of "
                        + d(c, "feeIncome") + " gives total income of " + d(c, "totalIncome") + "."),
                new ReasonDraft("fundingCost", Impact.NEGATIVE, "Funding cost of " + d(c, "fundingCost") + " reduces the contribution."),
                new ReasonDraft("operationalCost", Impact.NEGATIVE, "Operational cost of " + d(c, "operationalCost") + " reduces the contribution."),
                new ReasonDraft("expectedCreditLoss", Impact.NEGATIVE, "The ECL estimate of " + d(c, "expectedCreditLoss") + " (PD "
                        + d(c, "probabilityOfDefault") + " x LGD " + d(c, "lossGivenDefault") + " x EAD " + d(c, "exposureAtDefault")
                        + ") is deducted to reach the contribution after ECL."));
    }

    private static List<String> recommendations(FinancialContext c) {
        List<String> out = new ArrayList<>();
        BigDecimal before = c.fact("contributionBeforeEcl").value();
        if (c.fact("expectedCreditLoss").value().signum() > 0) {
            out.add("A human reviewer could check whether the PD (" + d(c, "probabilityOfDefault") + "), LGD (" + d(c, "lossGivenDefault")
                    + ") and exposure (" + d(c, "exposureAtDefault") + ") are appropriate for this loan, because they drive the ECL estimate of "
                    + d(c, "expectedCreditLoss") + ".");
        }
        if (before.signum() < 0) {
            out.add("Income does not cover funding and operational cost even before ECL; a reviewer could check how those costs are attributed to this loan.");
        } else if ("NEGATIVE".equals(c.contributionStatus())) {
            out.add("The contribution is negative only after the ECL estimate; a reviewer could examine the credit-risk inputs before drawing conclusions about profitability.");
        }
        out.add("This explanation is informational. Any decision about this loan rests with authorised staff.");
        return out;
    }

    private static List<String> limitations(FinancialContext c) {
        List<String> out = new ArrayList<>();
        out.add("Simplified educational model, not a complete bank accounting or regulatory calculation.");
        out.add("The ECL is an estimate from the supplied PD, LGD and EAD, not a prediction about any individual customer's behaviour.");
        if (!c.has("averageOutstandingBalance")) {
            out.add("No average outstanding balance was recorded for this period, so the funding cost could not be cross-checked.");
        }
        return out;
    }

    private static String d(FinancialContext c, String key) {
        Fact f = c.fact(key);
        if (f == null) {
            throw new IllegalStateException("Fact missing from context: " + key);
        }
        return f.display();
    }
}
