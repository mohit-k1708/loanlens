package com.loanlens.service;

import com.loanlens.dto.CustomerRequest;
import com.loanlens.dto.CustomerResponse;
import com.loanlens.dto.PageResponse;
import com.loanlens.entity.Customer;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.mapper.CustomerMapper;
import com.loanlens.repository.CustomerRepository;
import com.loanlens.repository.LoanRepository;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;
    private final LoanRepository loans;

    public CustomerService(CustomerRepository customers, LoanRepository loans) {
        this.customers = customers;
        this.loans = loans;
    }

    public PageResponse<CustomerResponse> search(String q, Pageable pageable) {
        String term = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.from(customers.search(term, pageable), CustomerMapper::toResponse);
    }

    public CustomerResponse get(Long id) {
        return CustomerMapper.toResponse(require(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        String code = request.customerCode().trim();
        String email = request.email().trim();
        if (customers.existsByCustomerCode(code)) {
            throw new ConflictException("A customer with code " + code + " already exists.");
        }
        if (customers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A customer with email " + email + " already exists.");
        }
        return CustomerMapper.toResponse(customers.saveAndFlush(CustomerMapper.toEntity(request)));
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = require(id);
        String code = request.customerCode().trim();
        String email = request.email().trim();
        if (customers.existsByCustomerCodeAndIdNot(code, id)) {
            throw new ConflictException("A customer with code " + code + " already exists.");
        }
        if (customers.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw new ConflictException("A customer with email " + email + " already exists.");
        }
        CustomerMapper.apply(request, customer);
        return CustomerMapper.toResponse(customers.saveAndFlush(customer));
    }

    /**
     * Business rule: a customer who has (or ever had) a loan cannot be deleted, because loans and their financial
     * history must stay traceable. A customer with no loans is soft-deleted: hidden everywhere, row retained.
     */
    @Transactional
    public void delete(Long id) {
        Customer customer = require(id);
        if (loans.existsByCustomerId(id)) {
            throw new ConflictException("Customer " + customer.getCustomerCode()
                    + " has loans and cannot be deleted. Close their loans instead; loan history is retained.");
        }
        customer.setDeletedAt(Instant.now());
        customers.saveAndFlush(customer);
    }

    private Customer require(Long id) {
        return customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " was not found."));
    }
}
