package com.loanlens.repository;

import com.loanlens.entity.CostCategory;
import com.loanlens.entity.OperationalCostAllocation;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationalCostAllocationRepository extends JpaRepository<OperationalCostAllocation, Long> {

    boolean existsByLoanIdAndReportingPeriodAndCostCategory(Long loanId, String reportingPeriod, CostCategory costCategory);

    List<OperationalCostAllocation> findByLoanIdAndReportingPeriodOrderByIdAsc(Long loanId, String reportingPeriod);

    interface LoanAllocationTotal {
        Long getLoanId();

        java.math.BigDecimal getTotal();
    }

    /** Sum of the allocation lines of each loan for a period, for a whole set of loans in one query. */
    @Query("select a.loan.id as loanId, sum(a.allocatedAmount) as total from OperationalCostAllocation a "
            + "where a.reportingPeriod = :period and a.loan.id in :loanIds group by a.loan.id")
    List<LoanAllocationTotal> totalsForLoansInPeriod(@Param("period") String period, @Param("loanIds") Collection<Long> loanIds);
}
