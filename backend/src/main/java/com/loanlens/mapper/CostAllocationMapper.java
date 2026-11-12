package com.loanlens.mapper;

import com.loanlens.dto.CostAllocationResponse;
import com.loanlens.dto.ProfitabilityResponse;
import com.loanlens.entity.OperationalCostAllocation;

public final class CostAllocationMapper {

    private CostAllocationMapper() {
    }

    public static CostAllocationResponse toResponse(OperationalCostAllocation a, String loanCode) {
        return new CostAllocationResponse(a.getId(), loanCode, a.getReportingPeriod(), a.getCostCategory(), a.getAllocationMethod(),
                a.getAllocationDriver(), a.getAllocatedAmount(), a.getSourceDescription(), a.getCreatedAt());
    }

    public static ProfitabilityResponse.OperationalCostLine toLine(OperationalCostAllocation a) {
        return new ProfitabilityResponse.OperationalCostLine(a.getCostCategory(), a.getAllocationMethod(),
                a.getAllocationDriver(), a.getAllocatedAmount(), a.getSourceDescription());
    }
}
