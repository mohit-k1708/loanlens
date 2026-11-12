package com.loanlens.dto;

import com.loanlens.entity.EmploymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CustomerRequest(
        @NotBlank(message = "Customer code is required.")
        @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,19}$", message = "Customer code must be 3-20 characters: uppercase letters, digits or hyphens.")
        String customerCode,

        @NotBlank(message = "Full name is required.")
        @Size(min = 2, max = 150, message = "Full name must be 2-150 characters.")
        String fullName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        @Size(max = 254, message = "Email is too long.")
        String email,

        @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "Phone must be 7-20 digits (spaces, brackets, hyphens and a leading + are allowed).")
        String phone,

        @NotNull(message = "Employment type is required.")
        EmploymentType employmentType,

        @NotNull(message = "Annual income is required.")
        @DecimalMin(value = "0.00", message = "Annual income cannot be negative.")
        @Digits(integer = 17, fraction = 2, message = "Annual income allows at most 2 decimal places.")
        BigDecimal annualIncome) {
}
