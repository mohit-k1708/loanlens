package com.loanlens.service;

import com.loanlens.entity.Loan;
import com.loanlens.exception.BusinessRuleException;
import java.util.Map;

/** A reporting period only makes sense while the loan exists: from its start year to its maturity year. */
final class LoanPeriodRules {

    private LoanPeriodRules() {
    }

    static void requireWithinLoanLife(Loan loan, String period) {
        int year = Integer.parseInt(period);
        int from = loan.getStartDate().getYear();
        int to = loan.getMaturityDate().getYear();
        if (year < from || year > to) {
            throw new BusinessRuleException(
                    "Reporting period " + period + " is outside the life of loan " + loan.getLoanCode() + " (" + from + " to " + to + ").",
                    Map.of("reportingPeriod", "Must be between " + from + " and " + to + "."));
        }
    }
}
