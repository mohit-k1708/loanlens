package com.loanlens.service;

import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * The simplified profitability formula. Pure arithmetic on BigDecimal, with no database and no clock,
 * so it can be tested exhaustively.
 *
 * <pre>
 * Total Income          = Interest Income + Fee Income
 * Total Costs Before ECL = Funding Cost + Operational Cost
 * Contribution Before ECL = Total Income - Total Costs Before ECL
 * Contribution After ECL  = Contribution Before ECL - Expected Credit Loss
 * </pre>
 *
 * <p>Interest and fee income, funding cost and operational cost are supplied inputs; the ECL is supplied by the
 * credit-risk calculation. A negative result is an ESTIMATED contribution, not a confirmed loss.
 */
@Component
public class ProfitabilityCalculator {

    /** Bump when the formula changes, so older stored snapshots stay interpretable. */
    public static final String VERSION = "SIMPLIFIED-1.0";

    public record Inputs(BigDecimal interestIncome, BigDecimal feeIncome, BigDecimal fundingCost,
                         BigDecimal operationalCost, BigDecimal expectedCreditLoss) {
    }

    public record Result(BigDecimal interestIncome, BigDecimal feeIncome, BigDecimal totalIncome,
                         BigDecimal fundingCost, BigDecimal operationalCost, BigDecimal totalCostsBeforeEcl,
                         BigDecimal totalExpenses, BigDecimal contributionBeforeEcl, BigDecimal expectedCreditLoss,
                         BigDecimal contributionAfterEcl, ContributionStatus status) {
    }

    public Result calculate(Inputs in) {
        BigDecimal interest = MoneyRules.round(in.interestIncome());
        BigDecimal fee = MoneyRules.round(in.feeIncome());
        BigDecimal funding = MoneyRules.round(in.fundingCost());
        BigDecimal operational = MoneyRules.round(in.operationalCost());
        BigDecimal ecl = MoneyRules.round(in.expectedCreditLoss());

        BigDecimal totalIncome = interest.add(fee);
        BigDecimal costsBeforeEcl = funding.add(operational);
        BigDecimal before = totalIncome.subtract(costsBeforeEcl);
        BigDecimal after = before.subtract(ecl);

        return new Result(interest, fee, totalIncome, funding, operational, costsBeforeEcl, costsBeforeEcl.add(ecl), before, ecl, after, classify(after));
    }

    static ContributionStatus classify(BigDecimal contributionAfterEcl) {
        return switch (contributionAfterEcl.signum()) {
            case 1 -> ContributionStatus.POSITIVE;
            case -1 -> ContributionStatus.NEGATIVE;
            default -> ContributionStatus.BREAK_EVEN;
        };
    }
}
