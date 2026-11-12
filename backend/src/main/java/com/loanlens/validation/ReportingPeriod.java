package com.loanlens.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A reporting period: a four-digit calendar year such as {@code 2026}. */
@Documented
@Constraint(validatedBy = ReportingPeriodValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReportingPeriod {

    String message() default "Reporting period must be a four-digit year between 2000 and 2100, e.g. 2026.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
