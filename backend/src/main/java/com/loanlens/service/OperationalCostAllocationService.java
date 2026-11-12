package com.loanlens.service;

import com.loanlens.dto.CostAllocationRequest;
import com.loanlens.dto.CostAllocationResponse;
import com.loanlens.entity.Loan;
import com.loanlens.entity.OperationalCostAllocation;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.mapper.CostAllocationMapper;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.validation.ReportingPeriods;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationalCostAllocationService {

    private final OperationalCostAllocationRepository allocations;
    private final FinancialRecordRepository records;
    private final LoanService loanService;

    public OperationalCostAllocationService(OperationalCostAllocationRepository allocations,
                                            FinancialRecordRepository records, LoanService loanService) {
        this.allocations = allocations;
        this.records = records;
        this.loanService = loanService;
    }

    @Transactional
    public CostAllocationResponse create(String loanIdOrCode, CostAllocationRequest request) {
        Loan loan = loanService.requireLoan(loanIdOrCode);
        String period = request.reportingPeriod();
        LoanPeriodRules.requireWithinLoanLife(loan, period);

        if (allocations.existsByLoanIdAndReportingPeriodAndCostCategory(loan.getId(), period, request.costCategory())) {
            throw new ConflictException("Loan " + loan.getLoanCode() + " already has a " + request.costCategory()
                    + " allocation for reporting period " + period + ".");
        }

        // Allocation lines break down the financial record's operational cost; together they may not exceed it.
        records.findByLoanIdAndReportingPeriod(loan.getId(), period).ifPresent(record -> {
            BigDecimal existing = allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(loan.getId(), period).stream()
                    .map(OperationalCostAllocation::getAllocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (existing.add(request.allocatedAmount()).compareTo(record.getOperationalCost()) > 0) {
                throw new BusinessRuleException(
                        "Allocations would total " + existing.add(request.allocatedAmount()).toPlainString()
                                + ", more than the operational cost of " + record.getOperationalCost().toPlainString()
                                + " in the financial record for " + period + ".",
                        Map.of("allocatedAmount", "Exceeds the remaining operational cost."));
            }
        });

        OperationalCostAllocation allocation = new OperationalCostAllocation();
        allocation.setLoan(loan);
        allocation.setReportingPeriod(period);
        allocation.setCostCategory(request.costCategory());
        allocation.setAllocationMethod(request.allocationMethod());
        allocation.setAllocationDriver(request.allocationDriver().trim());
        allocation.setAllocatedAmount(request.allocatedAmount());
        allocation.setSourceDescription(request.sourceDescription() == null || request.sourceDescription().isBlank()
                ? null : request.sourceDescription().trim());
        return CostAllocationMapper.toResponse(allocations.saveAndFlush(allocation), loan.getLoanCode());
    }

    public List<CostAllocationResponse> list(String loanIdOrCode, String period) {
        ReportingPeriods.require(period);
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(loan.getId(), period).stream()
                .map(a -> CostAllocationMapper.toResponse(a, loan.getLoanCode()))
                .toList();
    }
}
