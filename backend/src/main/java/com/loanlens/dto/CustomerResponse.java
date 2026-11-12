package com.loanlens.dto;

import com.loanlens.entity.EmploymentType;
import java.math.BigDecimal;
import java.time.Instant;

public record CustomerResponse(
        Long id,
        String customerCode,
        String fullName,
        String email,
        String phone,
        EmploymentType employmentType,
        BigDecimal annualIncome,
        Instant createdAt,
        Instant updatedAt) {
}
