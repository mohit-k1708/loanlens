package com.loanlens.repository;

import com.loanlens.entity.Customer;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Optional, combinable filters for the loan list. A null argument means "do not filter on this". */
public final class LoanSpecifications {

    private LoanSpecifications() {
    }

    /**
     * @param search one search box: matches loan code OR customer name OR customer code (case-insensitive "contains")
     */
    public static Specification<Loan> filter(String loanCode, String customerName, String search, LoanType loanType,
                                             LoanStatus loanStatus, RiskRating riskRating) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (loanCode != null && !loanCode.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("loanCode")), like(loanCode), '\\'));
            }
            if (customerName != null && !customerName.isBlank()) {
                Join<Loan, Customer> customer = root.join("customer");
                predicates.add(cb.like(cb.lower(customer.get("fullName")), like(customerName), '\\'));
            }
            if (search != null && !search.isBlank()) {
                Join<Loan, Customer> customer = root.join("customer");
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("loanCode")), like(search), '\\'),
                        cb.like(cb.lower(customer.get("fullName")), like(search), '\\'),
                        cb.like(cb.lower(customer.get("customerCode")), like(search), '\\')));
            }
            if (loanType != null) {
                predicates.add(cb.equal(root.get("loanType"), loanType));
            }
            if (loanStatus != null) {
                predicates.add(cb.equal(root.get("loanStatus"), loanStatus));
            }
            if (riskRating != null) {
                // Rating of the loan's MOST RECENT assessment (highest reporting period).
                Subquery<String> latestPeriod = query.subquery(String.class);
                var older = latestPeriod.from(RiskAssessment.class);
                latestPeriod.select(cb.greatest(older.<String>get("reportingPeriod")))
                        .where(cb.equal(older.get("loan"), root));

                Subquery<Long> match = query.subquery(Long.class);
                var ra = match.from(RiskAssessment.class);
                match.select(cb.literal(1L)).where(
                        cb.equal(ra.get("loan"), root),
                        cb.equal(ra.get("reportingPeriod"), latestPeriod),
                        cb.equal(ra.get("riskRating"), riskRating));
                predicates.add(cb.exists(match));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String like(String term) {
        return "%" + escapeLike(term.trim().toLowerCase()) + "%";
    }

    /** Escapes LIKE wildcards so a search for "50%" matches the text, not everything. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
