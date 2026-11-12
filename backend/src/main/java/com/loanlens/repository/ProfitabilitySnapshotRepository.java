package com.loanlens.repository;

import com.loanlens.entity.ProfitabilitySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfitabilitySnapshotRepository extends JpaRepository<ProfitabilitySnapshot, Long> {

    Optional<ProfitabilitySnapshot> findByLoanIdAndReportingPeriodAndCalculationVersionAndInputFingerprint(
            Long loanId, String reportingPeriod, String calculationVersion, String inputFingerprint);

    List<ProfitabilitySnapshot> findByLoanIdAndReportingPeriodOrderByCalculatedAtDescIdDesc(Long loanId, String reportingPeriod);

    /**
     * Stores a snapshot unless one with the same loan, period, calculation version and input fingerprint already
     * exists. Because the database decides, two concurrent identical requests cannot create duplicates and neither
     * fails. Returns 1 when a row was inserted, 0 when it already existed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO profitability_snapshots
                (loan_id, reporting_period, interest_income, fee_income, total_income, funding_cost, operational_cost,
                 contribution_before_ecl, expected_credit_loss, contribution_after_ecl,
                 calculation_version, input_fingerprint, calculated_at)
            VALUES
                (:loanId, :period, :interestIncome, :feeIncome, :totalIncome, :fundingCost, :operationalCost,
                 :before, :ecl, :after, :version, :fingerprint, :calculatedAt)
            ON CONFLICT (loan_id, reporting_period, calculation_version, input_fingerprint) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("loanId") Long loanId,
                       @Param("period") String period,
                       @Param("interestIncome") BigDecimal interestIncome,
                       @Param("feeIncome") BigDecimal feeIncome,
                       @Param("totalIncome") BigDecimal totalIncome,
                       @Param("fundingCost") BigDecimal fundingCost,
                       @Param("operationalCost") BigDecimal operationalCost,
                       @Param("before") BigDecimal before,
                       @Param("ecl") BigDecimal ecl,
                       @Param("after") BigDecimal after,
                       @Param("version") String version,
                       @Param("fingerprint") String fingerprint,
                       @Param("calculatedAt") Instant calculatedAt);
}
