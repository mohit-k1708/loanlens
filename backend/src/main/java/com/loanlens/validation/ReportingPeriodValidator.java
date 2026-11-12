package com.loanlens.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ReportingPeriodValidator implements ConstraintValidator<ReportingPeriod, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is left to @NotNull so the error message says "required" rather than "invalid".
        return value == null || ReportingPeriods.isValid(value);
    }
}
