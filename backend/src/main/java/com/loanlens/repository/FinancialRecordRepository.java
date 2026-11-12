package com.loanlens.repository;

import com.loanlens.entity.FinancialRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialRecordRepository extends JpaRepository<FinancialRecord, Long> {

    boolean existsByLoanIdAndReportingPeriod(Long loanId, String reportingPeriod);

    Optional<FinancialRecord> findByLoanIdAndReportingPeriod(Long loanId, String reportingPeriod);

    List<FinancialRecord> findByLoanIdOrderByReportingPeriodDesc(Long loanId);

    /** All records of one period for a set of loans, in one query (list rows and dashboard). */
    @Query("select f from FinancialRecord f join fetch f.loan where f.reportingPeriod = :period and f.loan.id in :loanIds")
    List<FinancialRecord> findForLoansInPeriod(@Param("period") String period, @Param("loanIds") Collection<Long> loanIds);

    @Query("select distinct f.reportingPeriod from FinancialRecord f")
    List<String> findDistinctPeriods();
}
