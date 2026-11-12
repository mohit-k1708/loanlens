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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Supplied income and cost inputs for one loan in one reporting period.
 * Interest income is an INPUT in this prototype; it is never derived from principal x rate.
 */
@Entity
@Table(name = "financial_records")
@Getter
@Setter
@NoArgsConstructor
public class FinancialRecord extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false, updatable = false)
    private Loan loan;

    @Column(name = "reporting_period", nullable = false, length = 4, updatable = false)
    private String reportingPeriod;

    @Column(name = "interest_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestIncome;

    @Column(name = "fee_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeIncome;

    /** Optional. When present, the funding cost is checked against balance x rate. */
    @Column(name = "average_outstanding_balance", precision = 19, scale = 2)
    private BigDecimal averageOutstandingBalance;

    /** Annual funding rate as a decimal fraction (0.07 = 7%). */
    @Column(name = "funding_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal fundingRate;

    @Column(name = "funding_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal fundingCost;

    @Column(name = "operational_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal operationalCost;
}
