package com.loanlens.repository;

import com.loanlens.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Soft-deleted customers are excluded automatically (@SQLRestriction on the entity). */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByCustomerCode(String customerCode);

    boolean existsByCustomerCodeAndIdNot(String customerCode, Long id);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    @Query("""
            select c from Customer c
            where (:q is null
                   or lower(c.fullName) like lower(concat('%', cast(:q as string), '%'))
                   or lower(c.customerCode) like lower(concat('%', cast(:q as string), '%'))
                   or lower(c.email) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Customer> search(@Param("q") String q, Pageable pageable);
}
