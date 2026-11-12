package com.loanlens.mapper;

import com.loanlens.dto.CustomerRequest;
import com.loanlens.dto.CustomerResponse;
import com.loanlens.entity.Customer;

public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(c.getId(), c.getCustomerCode(), c.getFullName(), c.getEmail(), c.getPhone(),
                c.getEmploymentType(), c.getAnnualIncome(), c.getCreatedAt(), c.getUpdatedAt());
    }

    public static Customer toEntity(CustomerRequest r) {
        Customer c = new Customer();
        apply(r, c);
        return c;
    }

    /** Copies the editable fields. Names and emails are trimmed; the email is stored lower-case. */
    public static void apply(CustomerRequest r, Customer c) {
        c.setCustomerCode(r.customerCode().trim());
        c.setFullName(r.fullName().trim());
        c.setEmail(r.email().trim().toLowerCase());
        c.setPhone(r.phone() == null || r.phone().isBlank() ? null : r.phone().trim());
        c.setEmploymentType(r.employmentType());
        c.setAnnualIncome(r.annualIncome());
    }
}
