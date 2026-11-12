package com.loanlens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loanlens.dto.CustomerRequest;
import com.loanlens.dto.CustomerResponse;
import com.loanlens.entity.Customer;
import com.loanlens.entity.EmploymentType;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.repository.CustomerRepository;
import com.loanlens.repository.LoanRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock CustomerRepository customers;
    @Mock LoanRepository loans;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(customers, loans);
    }

    private static CustomerRequest request() {
        return new CustomerRequest("C2001", "  Rahul Sharma ", " Rahul.Sharma@Example.com ", "+91 9000000001",
                EmploymentType.SALARIED, new BigDecimal("900000.00"));
    }

    private static Customer existing() {
        Customer c = new Customer();
        c.setId(5L);
        c.setCustomerCode("C2001");
        c.setFullName("Rahul Sharma");
        return c;
    }

    @Test
    void createTrimsAndLowercasesEmail() {
        when(customers.existsByCustomerCode("C2001")).thenReturn(false);
        when(customers.existsByEmailIgnoreCase("Rahul.Sharma@Example.com")).thenReturn(false);
        when(customers.saveAndFlush(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

        CustomerResponse response = service.create(request());

        assertThat(response.fullName()).isEqualTo("Rahul Sharma");
        assertThat(response.email()).isEqualTo("rahul.sharma@example.com");
    }

    @Test
    void duplicateCodeAndEmailAreConflicts() {
        when(customers.existsByCustomerCode("C2001")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ConflictException.class).hasMessageContaining("C2001");

        when(customers.existsByCustomerCode("C2001")).thenReturn(false);
        when(customers.existsByEmailIgnoreCase("Rahul.Sharma@Example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ConflictException.class).hasMessageContaining("email");
        verify(customers, never()).saveAndFlush(any());
    }

    @Test
    void customerWithLoansCannotBeDeleted() {
        when(customers.findById(5L)).thenReturn(Optional.of(existing()));
        when(loans.existsByCustomerId(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(ConflictException.class).hasMessageContaining("has loans");
        verify(customers, never()).saveAndFlush(any());
    }

    @Test
    void customerWithoutLoansIsSoftDeleted() {
        Customer c = existing();
        when(customers.findById(5L)).thenReturn(Optional.of(c));
        when(loans.existsByCustomerId(5L)).thenReturn(false);

        service.delete(5L);

        assertThat(c.getDeletedAt()).isNotNull();
        verify(customers).saveAndFlush(c);
    }

    @Test
    void unknownCustomerIsNotFound() {
        when(customers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
