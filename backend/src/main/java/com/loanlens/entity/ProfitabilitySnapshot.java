package com.loanlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * An immutable record of one profitability calculation. Rows are inserted by the repository
 * ({@code INSERT ... ON CONFLICT DO NOTHING}) so the same inputs can never produce a duplicate.
 */
@Entity
@Immutable
@Table(name = "profitability_snapshots")
@Getter
@NoArgsConstructor
public class ProfitabilitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(name = "reporting_period", nullable = false, length = 4)
    private String reportingPeriod;

    @Column(name = "interest_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestIncome;

    @Column(name = "fee_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeIncome;

    @Column(name = "total_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalIncome;

    @Column(name = "funding_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal fundingCost;

    @Column(name = "operational_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal operationalCost;

    @Column(name = "contribution_before_ecl", nullable = false, precision = 19, scale = 2)
    private BigDecimal contributionBeforeEcl;

    @Column(name = "expected_credit_loss", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedCreditLoss;

    @Column(name = "contribution_after_ecl", nullable = false, precision = 19, scale = 2)
    private BigDecimal contributionAfterEcl;

    @Column(name = "calculation_version", nullable = false, length = 30)
    private String calculationVersion;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;
}
