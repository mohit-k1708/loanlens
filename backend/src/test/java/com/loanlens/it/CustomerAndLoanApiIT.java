package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerAndLoanApiIT extends AbstractPostgresIT {

    /* ------------------------------ customers ------------------------------ */

    @Test
    void customerLifecycle() throws Exception {
        postJson("/api/customers", """
                {"customerCode":"C2001","fullName":"  Rahul Sharma ","email":"Rahul.Sharma@Example.com","phone":"+91 9000000001",
                 "employmentType":"SALARIED","annualIncome":900000.50}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/customers/")))
                .andExpect(jsonPath("$.fullName").value("Rahul Sharma"))
                .andExpect(jsonPath("$.email").value("rahul.sharma@example.com"))
                .andExpect(jsonPath("$.annualIncome").value(900000.50))
                .andExpect(jsonPath("$.createdAt").exists());

        Number id = read(getJson("/api/customers?search=rahul"), "$.content[0].id");
        getJson("/api/customers/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.customerCode").value("C2001"));

        putJson("/api/customers/" + id, """
                {"customerCode":"C2001","fullName":"Rahul K Sharma","email":"rahul.sharma@example.com","phone":null,
                 "employmentType":"SELF_EMPLOYED","annualIncome":1200000.00}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Rahul K Sharma"))
                .andExpect(jsonPath("$.employmentType").value("SELF_EMPLOYED"));
        getJson("/api/customers/999").andExpect(status().isNotFound());
    }

    @Test
    void customerUniquenessAndValidation() throws Exception {
        createCustomer("C2001", "Rahul Sharma", "rahul@example.com");

        postJson("/api/customers", """
                {"customerCode":"C2001","fullName":"Someone Else","email":"other@example.com","employmentType":"SALARIED","annualIncome":1.00}""")
                .andExpect(status().isConflict());
        postJson("/api/customers", """
                {"customerCode":"C2002","fullName":"Someone Else","email":"RAHUL@example.com","employmentType":"SALARIED","annualIncome":1.00}""")
                .andExpect(status().isConflict()); // e-mail is unique case-insensitively
        postJson("/api/customers", """
                {"customerCode":"c2","fullName":"X","email":"not-an-email","phone":"abc","employmentType":"SALARIED","annualIncome":-5}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customerCode").exists())
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.annualIncome").exists());

        long second = createCustomer("C2002", "Priya Nair", "priya@example.com");
        putJson("/api/customers/" + second, """
                {"customerCode":"C2002","fullName":"Priya Nair","email":"rahul@example.com","employmentType":"SALARIED","annualIncome":1.00}""")
                .andExpect(status().isConflict());
        assertThat(count("customers")).isEqualTo(2);
    }

    @Test
    @DisplayName("customers with loans cannot be deleted; customers without loans are soft-deleted")
    void customerDeletionRules() throws Exception {
        long withLoan = createRahulAndLoan();
        long without = createCustomer("C2002", "Priya Nair", "priya@example.com");

        mvc.perform(delete("/api/customers/" + withLoan)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("has loans")));
        getJson("/api/customers/" + withLoan).andExpect(status().isOk());

        mvc.perform(delete("/api/customers/" + without)).andExpect(status().isNoContent());
        getJson("/api/customers/" + without).andExpect(status().isNotFound());
        getJson("/api/customers").andExpect(jsonPath("$.totalElements").value(1));
        // The row is retained (soft delete), only hidden.
        assertThat(count("customers")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE deleted_at IS NOT NULL", Long.class)).isEqualTo(1);

        // A soft-deleted customer no longer blocks reuse of its code / email.
        createCustomer("C2002", "Priya Nair (new)", "priya@example.com");
        mvc.perform(delete("/api/customers/" + without)).andExpect(status().isNotFound());
    }

    @Test
    void customerPaginationAndSearch() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createCustomer("C300" + i, "Customer Number " + i, "c" + i + "@example.com");
        }
        createCustomer("C4000", "Zed Unique", "zed@example.com");

        getJson("/api/customers?size=4&page=0").andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.totalElements").value(6)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(false));
        getJson("/api/customers?size=4&page=1").andExpect(jsonPath("$.content", hasSize(2))).andExpect(jsonPath("$.last").value(true));
        getJson("/api/customers?search=zed").andExpect(jsonPath("$.totalElements").value(1));
        getJson("/api/customers?search=C300").andExpect(jsonPath("$.totalElements").value(5));
        getJson("/api/customers?size=1000").andExpect(jsonPath("$.size").value(100)); // capped
    }

    /* -------------------------------- loans -------------------------------- */

    @Test
    void createAndReadLoan() throws Exception {
        long customerId = createCustomer("C2001", "Rahul Sharma", "rahul@example.com");

        postJson("/api/loans", """
                {"loanCode":"L1001","customerId":%d,"loanType":"PERSONAL","originalPrincipal":500000.00,"outstandingBalance":500000.00,
                 "interestRate":0.10,"tenureMonths":60,"startDate":"2025-04-01"}""".formatted(customerId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/loans/L1001"))
                .andExpect(jsonPath("$.loanCode").value("L1001"))
                .andExpect(jsonPath("$.loanStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.maturityDate").value("2030-04-01"))
                .andExpect(jsonPath("$.customer.fullName").value("Rahul Sharma"))
                .andExpect(jsonPath("$.latestRiskRating").doesNotExist());
        getJson("/api/loans/L1001").andExpect(jsonPath("$.interestRate").value(0.1));
        getJson("/api/loans/L9999").andExpect(status().isNotFound());
    }

    @Test
    void loanBusinessRules() throws Exception {
        long customerId = createCustomer("C2001", "Rahul Sharma", "rahul@example.com");
        String ok = """
                {"loanCode":"L1001","customerId":%d,"loanType":"PERSONAL","originalPrincipal":500000.00,"outstandingBalance":500000.00,
                 "interestRate":0.10,"tenureMonths":60,"startDate":"2025-04-01"}""";

        // a loan cannot reference a customer that does not exist
        postJson("/api/loans", ok.formatted(9999)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.customerId").value(containsString("does not exist")));
        assertThat(count("loans")).isZero();

        postJson("/api/loans", ok.formatted(customerId)).andExpect(status().isCreated());
        postJson("/api/loans", ok.formatted(customerId)).andExpect(status().isConflict()); // duplicate code

        // outstanding above principal, negative values, percent-style rate
        postJson("/api/loans", ok.formatted(customerId).replace("L1001", "L1002").replace("\"outstandingBalance\":500000.00", "\"outstandingBalance\":500000.01"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.outstandingBalance").exists());
        postJson("/api/loans", ok.formatted(customerId).replace("L1001", "L1002").replace("\"originalPrincipal\":500000.00", "\"originalPrincipal\":-500000.00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.originalPrincipal").exists());
        postJson("/api/loans", ok.formatted(customerId).replace("L1001", "L1002").replace("\"interestRate\":0.10", "\"interestRate\":10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.interestRate").value(containsString("decimal fraction")));
        assertThat(count("loans")).isEqualTo(1);

        // a closed loan must have a zero balance
        putJson("/api/loans/L1001", """
                {"loanType":"PERSONAL","originalPrincipal":500000.00,"outstandingBalance":100.00,"interestRate":0.10,
                 "tenureMonths":60,"startDate":"2025-04-01","loanStatus":"CLOSED"}""").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateLoanKeepsCodeAndCustomer() throws Exception {
        createRahulAndLoan();

        putJson("/api/loans/L1001", """
                {"loanType":"AUTO","originalPrincipal":600000.00,"outstandingBalance":550000.00,"interestRate":0.095,
                 "tenureMonths":36,"startDate":"2025-01-01","loanStatus":"UNDER_WATCH"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanCode").value("L1001"))
                .andExpect(jsonPath("$.customer.customerCode").value("C2001"))
                .andExpect(jsonPath("$.loanType").value("AUTO"))
                .andExpect(jsonPath("$.loanStatus").value("UNDER_WATCH"))
                .andExpect(jsonPath("$.maturityDate").value("2028-01-01"))
                .andExpect(jsonPath("$.updatedAt").exists());

        // the code is not changeable: sending it is an error, not silently ignored
        putJson("/api/loans/L1001", """
                {"loanCode":"L9999","loanType":"AUTO","originalPrincipal":600000.00,"outstandingBalance":550000.00,"interestRate":0.095,
                 "tenureMonths":36,"startDate":"2025-01-01","loanStatus":"ACTIVE"}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Unknown field 'loanCode'")));
    }

    @Test
    @DisplayName("loan list: pagination, sorting, search and combinable filters")
    void loanListFilters() throws Exception {
        long a = createCustomer("C2001", "Rahul Sharma", "a@example.com");
        long b = createCustomer("C2002", "Priya Nair", "b@example.com");
        createLoan("L1001", a, "PERSONAL", "500000.00", "500000.00", "0.10", 60, "2025-04-01");
        createLoan("L1002", b, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");
        createLoan("L1003", a, "AUTO", "900000.00", "610000.00", "0.095", 60, "2024-02-10");
        createLoan("L1004", b, "HOME", "7500000.00", "7150000.00", "0.085", 300, "2025-02-01");
        putJson("/api/loans/L1003", """
                {"loanType":"AUTO","originalPrincipal":900000.00,"outstandingBalance":610000.00,"interestRate":0.095,
                 "tenureMonths":60,"startDate":"2024-02-10","loanStatus":"UNDER_WATCH"}""").andExpect(status().isOk());
        // L1001 = HIGH, L1002 = LOW (PD 0.012)
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());
        riskAssessment("L1002", "2026", "0.012", "0.25", "4120000.00").andExpect(status().isCreated());

        getJson("/api/loans").andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.content[0].loanCode").value("L1001"));
        getJson("/api/loans?size=3&page=1").andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.totalPages").value(2));
        getJson("/api/loans?sort=outstandingBalance,desc").andExpect(jsonPath("$.content[0].loanCode").value("L1004"));

        getJson("/api/loans?loanCode=l100").andExpect(jsonPath("$.totalElements").value(4));       // case-insensitive "contains"
        getJson("/api/loans?loanCode=1002").andExpect(jsonPath("$.totalElements").value(1));
        getJson("/api/loans?customerName=rahul").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/loans?customerName=NAIR").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/loans?loanType=HOME").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/loans?loanStatus=UNDER_WATCH").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].loanCode").value("L1003"));
        getJson("/api/loans?riskRating=HIGH").andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].loanCode").value("L1001"));
        getJson("/api/loans?riskRating=LOW").andExpect(jsonPath("$.content[0].loanCode").value("L1002"));
        getJson("/api/loans?riskRating=MEDIUM").andExpect(jsonPath("$.totalElements").value(0));
        // combined filters narrow the result
        getJson("/api/loans?customerName=priya&loanType=HOME&riskRating=LOW").andExpect(jsonPath("$.totalElements").value(1));
        getJson("/api/loans?customerName=rahul&loanType=HOME").andExpect(jsonPath("$.totalElements").value(0));
        // latest rating is exposed on each row, and null when there is no assessment
        getJson("/api/loans?loanCode=L1001").andExpect(jsonPath("$.content[0].latestRiskRating").value("HIGH"));
        getJson("/api/loans?loanCode=L1004").andExpect(jsonPath("$.content[0].latestRiskRating").doesNotExist());
    }

    @Test
    void latestRiskRatingUsesTheMostRecentPeriod() throws Exception {
        long a = createCustomer("C2001", "Rahul Sharma", "a@example.com");
        createLoan("L1001", a, "PERSONAL", "500000.00", "500000.00", "0.10", 60, "2025-04-01");
        riskAssessment("L1001", "2026", "0.10", "0.60", "500000.00").andExpect(status().isCreated());   // HIGH
        riskAssessment("L1001", "2027", "0.01", "0.60", "400000.00").andExpect(status().isCreated());   // LOW (newer)

        getJson("/api/loans/L1001").andExpect(jsonPath("$.latestRiskRating").value("LOW"));
        getJson("/api/loans?riskRating=LOW").andExpect(jsonPath("$.totalElements").value(1));
        getJson("/api/loans?riskRating=HIGH").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchTreatsWildcardsAsText() throws Exception {
        long a = createCustomer("C2001", "Rahul Sharma", "a@example.com");
        createLoan("L1001", a, "PERSONAL", "500000.00", "500000.00", "0.10", 60, "2025-04-01");

        getJson("/api/loans?loanCode=%25").andExpect(jsonPath("$.totalElements").value(0));      // "%"
        getJson("/api/loans?customerName=_").andExpect(jsonPath("$.totalElements").value(0));    // "_"
    }

    @Test
    void badQueryParametersAreClientErrorsNotServerErrors() throws Exception {
        getJson("/api/loans?sort=notAProperty,asc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        getJson("/api/loans?loanType=YACHT").andExpect(status().isBadRequest());
        // An unparseable page number falls back to the first page (Spring's documented behaviour); it must not be a 500.
        getJson("/api/loans?page=abc").andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void errorBodiesNeverContainInternals() throws Exception {
        String body = body(getJson("/api/loans/NOPE").andExpect(status().isNotFound()));
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.springframework").doesNotContain("jdbc:");
    }
}
