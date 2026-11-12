package com.loanlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One activity-based-costing line behind a loan's operational cost for a reporting period. */
@Entity
@Table(name = "operational_cost_allocations")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class OperationalCostAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false, updatable = false)
    private Loan loan;

    @Column(name = "reporting_period", nullable = false, length = 4, updatable = false)
    private String reportingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_category", nullable = false, length = 30)
    private CostCategory costCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_method", nullable = false, length = 30)
    private AllocationMethod allocationMethod;

    /** What drives the cost, e.g. "Customer service contacts". */
    @Column(name = "allocation_driver", nullable = false, length = 150)
    private String allocationDriver;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "source_description", length = 500)
    private String sourceDescription;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
