package com.loanlens.dto;

import com.loanlens.entity.AllocationMethod;
import com.loanlens.entity.CostCategory;
import java.math.BigDecimal;
import java.time.Instant;

public record CostAllocationResponse(
        Long id,
        String loanCode,
        String reportingPeriod,
        CostCategory costCategory,
        AllocationMethod allocationMethod,
        String allocationDriver,
        BigDecimal allocatedAmount,
        String sourceDescription,
        Instant createdAt) {
}
