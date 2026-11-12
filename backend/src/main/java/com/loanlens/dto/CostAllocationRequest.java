package com.loanlens.dto;

import com.loanlens.entity.AllocationMethod;
import com.loanlens.entity.CostCategory;
import com.loanlens.validation.ReportingPeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CostAllocationRequest(
        @NotNull(message = "Reporting period is required.")
        @ReportingPeriod
        String reportingPeriod,

        @NotNull(message = "Cost category is required.")
        CostCategory costCategory,

        @NotNull(message = "Allocation method is required.")
        AllocationMethod allocationMethod,

        @NotBlank(message = "Allocation driver is required (what drives this cost?).")
        @Size(max = 150, message = "Allocation driver must be at most 150 characters.")
        String allocationDriver,

        @NotNull(message = "Allocated amount is required.")
        @DecimalMin(value = "0.00", message = "Allocated amount cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Allocated amount allows at most 2 decimal places.")
        BigDecimal allocatedAmount,

        @Size(max = 500, message = "Source description must be at most 500 characters.")
        String sourceDescription) {
}
