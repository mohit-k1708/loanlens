package com.loanlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.Setter;

/**
 * Supplied credit-risk inputs (PD, LGD, EAD) for one loan in one reporting period.
 * The Expected Credit Loss is derived from these and is intentionally not stored.
 */
@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false, updatable = false)
    private Loan loan;

    @Column(name = "reporting_period", nullable = false, length = 4, updatable = false)
    private String reportingPeriod;

    /** Probability of Default, a decimal fraction in [0, 1]. */
    @Column(name = "probability_of_default", nullable = false, precision = 8, scale = 6)
    private BigDecimal probabilityOfDefault;

    /** Loss Given Default, a decimal fraction in [0, 1]. */
    @Column(name = "loss_given_default", nullable = false, precision = 8, scale = 6)
    private BigDecimal lossGivenDefault;

    @Column(name = "exposure_at_default", nullable = false, precision = 19, scale = 2)
    private BigDecimal exposureAtDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", nullable = false, length = 10)
    private RiskRating riskRating;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;
}
