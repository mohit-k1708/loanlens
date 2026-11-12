package com.loanlens.mapper;

import com.loanlens.dto.FinancialRecordRequest;
import com.loanlens.dto.FinancialRecordResponse;
import com.loanlens.entity.FinancialRecord;

public final class FinancialRecordMapper {

    private FinancialRecordMapper() {
    }

    public static FinancialRecordResponse toResponse(FinancialRecord r, String loanCode) {
        return new FinancialRecordResponse(r.getId(), loanCode, r.getReportingPeriod(), r.getInterestIncome(), r.getFeeIncome(),
                r.getInterestIncome().add(r.getFeeIncome()), r.getAverageOutstandingBalance(), r.getFundingRate(),
                r.getFundingCost(), r.getOperationalCost(), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** Copies the editable inputs (not the loan or the period, which identify the record). */
    public static void apply(FinancialRecordRequest req, FinancialRecord r) {
        r.setInterestIncome(req.interestIncome());
        r.setFeeIncome(req.feeIncome());
        r.setAverageOutstandingBalance(req.averageOutstandingBalance());
        r.setFundingRate(req.fundingRate());
        r.setFundingCost(req.fundingCost());
        r.setOperationalCost(req.operationalCost());
    }
}
