package com.loanlens.validation;

import com.loanlens.exception.BadRequestException;
import java.util.Map;

/**
 * Reporting periods are exact four-digit-year strings. They are always compared for equality, so records from
 * different periods can never be mixed in a calculation.
 */
public final class ReportingPeriods {

    private ReportingPeriods() {
    }

    public static boolean isValid(String value) {
        if (value == null || !value.matches("\\d{4}")) {
            return false;
        }
        int year = Integer.parseInt(value);
        return year >= 2000 && year <= 2100;
    }

    /** For path/query parameters. Throws a 400 with a helpful message when invalid. */
    public static String require(String value) {
        if (!isValid(value)) {
            throw new BadRequestException("Reporting period must be a four-digit year between 2000 and 2100, e.g. 2026.",
                    Map.of("period", "Use a four-digit year such as 2026."));
        }
        return value;
    }
}
