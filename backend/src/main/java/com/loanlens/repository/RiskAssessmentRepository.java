package com.loanlens.repository;

import com.loanlens.entity.RiskAssessment;
import com.loanlens.entity.RiskRating;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    interface LoanRating {
        Long getLoanId();

        RiskRating getRiskRating();
    }

    boolean existsByLoanIdAndReportingPeriod(Long loanId, String reportingPeriod);

    Optional<RiskAssessment> findByLoanIdAndReportingPeriod(Long loanId, String reportingPeriod);

    List<RiskAssessment> findByLoanIdOrderByReportingPeriodDesc(Long loanId);

    @Query("select a from RiskAssessment a join fetch a.loan where a.reportingPeriod = :period and a.loan.id in :loanIds")
    List<RiskAssessment> findForLoansInPeriod(@Param("period") String period, @Param("loanIds") Collection<Long> loanIds);

    @Query("select distinct a.reportingPeriod from RiskAssessment a")
    List<String> findDistinctPeriods();

    /** Rating from each loan's most recent reporting period, fetched for a whole page of loans in one query. */
    @Query("""
            select ra.loan.id as loanId, ra.riskRating as riskRating
            from RiskAssessment ra
            where ra.loan.id in :loanIds
              and ra.reportingPeriod = (select max(r2.reportingPeriod) from RiskAssessment r2 where r2.loan.id = ra.loan.id)
            """)
    List<LoanRating> findLatestRatings(@Param("loanIds") Collection<Long> loanIds);
}
