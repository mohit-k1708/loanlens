package com.loanlens.repository;

import com.loanlens.entity.Loan;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

public interface LoanRepository extends JpaRepository<Loan, Long>, JpaSpecificationExecutor<Loan> {

    boolean existsByLoanCode(String loanCode);

    boolean existsByCustomerId(Long customerId);

    @EntityGraph(attributePaths = "customer")
    Optional<Loan> findByLoanCode(String loanCode);

    @EntityGraph(attributePaths = "customer")
    Optional<Loan> findWithCustomerById(Long id);

    @EntityGraph(attributePaths = "customer")
    java.util.List<Loan> findAllByOrderByLoanCodeAsc();

    /** Unpaged variant used when a filter can only be applied after computing contributions. */
    @Override
    @EntityGraph(attributePaths = "customer")
    java.util.List<Loan> findAll(Specification<Loan> spec, org.springframework.data.domain.Sort sort);

    /** Loads the customer in the same query so listing a page of loans does not trigger one query per row. */
    @Override
    @EntityGraph(attributePaths = "customer")
    Page<Loan> findAll(Specification<Loan> spec, Pageable pageable);
}
