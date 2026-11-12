package com.loanlens.service;

import com.loanlens.dto.FinancialRecordRequest;
import com.loanlens.dto.FinancialRecordResponse;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.mapper.FinancialRecordMapper;
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
public class FinancialRecordService {

    private final FinancialRecordRepository records;
    private final OperationalCostAllocationRepository allocations;
    private final LoanService loanService;
    private final FundingCostService fundingCost;

    public FinancialRecordService(FinancialRecordRepository records, OperationalCostAllocationRepository allocations,
                                  LoanService loanService, FundingCostService fundingCost) {
        this.records = records;
        this.allocations = allocations;
        this.loanService = loanService;
        this.fundingCost = fundingCost;
    }

    @Transactional
    public FinancialRecordResponse create(String loanIdOrCode, FinancialRecordRequest request) {
        Loan loan = loanService.requireLoan(loanIdOrCode);
        String period = request.reportingPeriod();
        LoanPeriodRules.requireWithinLoanLife(loan, period);

        // One record per loan per period (not versioned). A correction is an update, not a second record.
        if (records.existsByLoanIdAndReportingPeriod(loan.getId(), period)) {
            throw new ConflictException("Loan " + loan.getLoanCode() + " already has a financial record for reporting period "
                    + period + ". Update it instead of creating another.");
        }
        checkFundingCost(loan, request);
        checkAllocationsFit(loan, period, request);

        FinancialRecord record = new FinancialRecord();
        record.setLoan(loan);
        record.setReportingPeriod(period);
        FinancialRecordMapper.apply(request, record);
        return FinancialRecordMapper.toResponse(records.saveAndFlush(record), loan.getLoanCode());
    }

    @Transactional
    public FinancialRecordResponse update(String loanIdOrCode, String period, FinancialRecordRequest request) {
        ReportingPeriods.require(period);
        if (!period.equals(request.reportingPeriod())) {
            throw new BadRequestException("The reporting period in the body must match the URL.",
                    Map.of("reportingPeriod", "Must be " + period + "."));
        }
        Loan loan = loanService.requireLoan(loanIdOrCode);
        FinancialRecord record = findRecord(loan, period);
        checkFundingCost(loan, request);
        checkAllocationsFit(loan, period, request);
        FinancialRecordMapper.apply(request, record);
        return FinancialRecordMapper.toResponse(records.saveAndFlush(record), loan.getLoanCode());
    }

    public List<FinancialRecordResponse> list(String loanIdOrCode) {
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return records.findByLoanIdOrderByReportingPeriodDesc(loan.getId()).stream()
                .map(r -> FinancialRecordMapper.toResponse(r, loan.getLoanCode()))
                .toList();
    }

    public FinancialRecordResponse get(String loanIdOrCode, String period) {
        ReportingPeriods.require(period);
        Loan loan = loanService.requireLoan(loanIdOrCode);
        return FinancialRecordMapper.toResponse(findRecord(loan, period), loan.getLoanCode());
    }

    private FinancialRecord findRecord(Loan loan, String period) {
        return records.findByLoanIdAndReportingPeriod(loan.getId(), period).orElseThrow(() -> new ResourceNotFoundException(
                "Loan " + loan.getLoanCode() + " has no financial record for reporting period " + period + "."));
    }

    /**
     * Funding cost is a supplied input. If the caller also supplies the average outstanding balance we can verify it
     * with {@link FundingCostService} (balance x annual rate, 12-month period). The average balance is the balance
     * over the period, so it can never exceed the loan's original principal.
     */
    private void checkFundingCost(Loan loan, FinancialRecordRequest request) {
        BigDecimal balance = request.averageOutstandingBalance();
        if (balance == null) {
            return;
        }
        if (loan.getOriginalPrincipal() != null && balance.compareTo(loan.getOriginalPrincipal()) > 0) {
            throw new BusinessRuleException(
                    "The average outstanding balance cannot exceed the loan's original principal of "
                            + loan.getOriginalPrincipal().toPlainString() + ".",
                    Map.of("averageOutstandingBalance", "Cannot exceed the original principal."));
        }
        if (!fundingCost.isConsistent(request.fundingCost(), balance, request.fundingRate())) {
            BigDecimal expected = fundingCost.calculate(balance, request.fundingRate());
            throw new BusinessRuleException(
                    "Funding cost does not match average outstanding balance x funding rate (expected " + expected.toPlainString() + ").",
                    Map.of("fundingCost", "Expected about " + expected.toPlainString() + " for the supplied balance and rate."));
        }
    }

    /** An operational cost breakdown that already exists for this period may not be larger than the new total. */
    private void checkAllocationsFit(Loan loan, String period, FinancialRecordRequest request) {
        BigDecimal allocated = allocations.findByLoanIdAndReportingPeriodOrderByIdAsc(loan.getId(), period).stream()
                .map(a -> a.getAllocatedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(request.operationalCost()) > 0) {
            throw new BusinessRuleException(
                    "The recorded operational cost allocations already total " + allocated.toPlainString()
                            + ", more than the operational cost of " + request.operationalCost().toPlainString() + ".",
                    Map.of("operationalCost", "Cannot be lower than the allocations already recorded (" + allocated.toPlainString() + ")."));
        }
    }
}
