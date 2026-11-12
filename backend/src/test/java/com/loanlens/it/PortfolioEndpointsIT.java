package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dashboard, list-with-period and reporting-period endpoints, on small hand-built data sets. */
class PortfolioEndpointsIT extends AbstractPostgresIT {

    private void rahulWithInputs() throws Exception {
        createRahulAndLoan();
        addRahulsInputs2026();
    }

    @Test
    @DisplayName("a loan without inputs is reported as unavailable, never as zero")
    void missingDataIsNotZero() throws Exception {
        rahulWithInputs();
        long other = createCustomer("C2002", "Priya Nair", "priya@example.com");
        createLoan("L1002", other, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");

        getJson("/api/loans?period=2026&sort=loanCode,asc")
                .andExpect(jsonPath("$.content[0].profitability.available").value(true))
                .andExpect(jsonPath("$.content[0].profitability.contributionAfterEcl").value(-10000.00))
                .andExpect(jsonPath("$.content[0].profitability.contributionStatus").value("NEGATIVE"))
                .andExpect(jsonPath("$.content[1].profitability.available").value(false))
                .andExpect(jsonPath("$.content[1].profitability.missingInputs", contains("FINANCIAL_RECORD", "RISK_ASSESSMENT")))
                .andExpect(jsonPath("$.content[1].profitability.contributionAfterEcl").doesNotExist())
                .andExpect(jsonPath("$.content[1].profitability.totalIncome").doesNotExist());

        // risk assessment only -> the rating is known, the financial record is what is missing
        riskAssessment("L1002", "2026", "0.012", "0.25", "4120000.00").andExpect(status().isCreated());
        getJson("/api/loans?period=2026&loanCode=L1002")
                .andExpect(jsonPath("$.content[0].profitability.missingInputs", contains("FINANCIAL_RECORD")))
                .andExpect(jsonPath("$.content[0].profitability.riskRating").value("LOW"));

        getJson("/api/loans").andExpect(jsonPath("$.content[0].profitability").doesNotExist()); // only with ?period=
    }

    @Test
    void dashboardCountsMissingDataSeparately() throws Exception {
        rahulWithInputs();
        long other = createCustomer("C2002", "Priya Nair", "priya@example.com");
        createLoan("L1002", other, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");

        getJson("/api/dashboard/summary?period=2026")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportingPeriod").value("2026"))
                .andExpect(jsonPath("$.totalActiveLoans").value(2))
                .andExpect(jsonPath("$.totalOutstandingBalance").value(4620000.00))   // both loans, data or not
                .andExpect(jsonPath("$.loansWithData").value(1))
                .andExpect(jsonPath("$.loansWithoutData").value(1))
                .andExpect(jsonPath("$.contributionBeforeEcl").value(20000.00))
                .andExpect(jsonPath("$.expectedCreditLoss").value(30000.00))
                .andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00))
                .andExpect(jsonPath("$.totalIncome").value(62000.00))
                .andExpect(jsonPath("$.negativeContributionLoans").value(1))
                .andExpect(jsonPath("$.positiveContributionLoans").value(0))
                .andExpect(jsonPath("$.highRiskLoans").value(1))
                .andExpect(jsonPath("$.attentionLoans", hasSize(1)))
                .andExpect(jsonPath("$.attentionLoans[0].loanCode").value("L1001"))
                .andExpect(jsonPath("$.attentionLoans[0].reasons", contains("Negative estimated contribution", "High risk rating")));
    }

    @Test
    @DisplayName("the dashboard total equals the sum of the loans' own results (list and detail agree)")
    void dashboardListAndDetailAgree() throws Exception {
        rahulWithInputs();
        long b = createCustomer("C2002", "Priya Nair", "priya@example.com");
        createLoan("L1002", b, "HOME", "4500000.00", "4120000.00", "0.0875", 240, "2023-07-15");
        financialRecord("L1002", "2026", "372400.00", "9000.00", "4250000.00", "0.07", "297500.00", "11150.00").andExpect(status().isCreated());
        riskAssessment("L1002", "2026", "0.012", "0.25", "4120000.00").andExpect(status().isCreated());

        Number dashboard = read(getJson("/api/dashboard/summary?period=2026"), "$.contributionAfterEcl");
        Number rahulList = read(getJson("/api/loans?period=2026&loanCode=L1001"), "$.content[0].profitability.contributionAfterEcl");
        Number rahulDetail = read(getJson("/api/loans/L1001/profitability?period=2026"), "$.contributionAfterEcl");
        Number priyaList = read(getJson("/api/loans?period=2026&loanCode=L1002"), "$.content[0].profitability.contributionAfterEcl");
        Number priyaDetail = read(getJson("/api/loans/L1002/profitability?period=2026"), "$.contributionAfterEcl");

        assertThat(rahulList.doubleValue()).isEqualTo(rahulDetail.doubleValue()).isEqualTo(-10000.00);
        assertThat(priyaList.doubleValue()).isEqualTo(priyaDetail.doubleValue()).isEqualTo(60390.00);
        assertThat(dashboard.doubleValue()).isEqualTo(50390.00); // -10,000 + 60,390
    }

    @Test
    @DisplayName("browsing the list and the dashboard never writes profitability snapshots")
    void readEndpointsDoNotWrite() throws Exception {
        rahulWithInputs();
        getJson("/api/loans?period=2026").andExpect(status().isOk());
        getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk());
        getJson("/api/reporting-periods").andExpect(status().isOk());
        assertThat(count("profitability_snapshots")).isZero();
    }

    @Test
    void contributionStatusFilterWithPaging() throws Exception {
        long c = createCustomer("C2001", "Customer One", "one@example.com");
        // three negative loans (income 100, costs 200) and one positive
        for (int i = 1; i <= 3; i++) {
            String code = "LN" + i;
            createLoan(code, c, "PERSONAL", "1000.00", "1000.00", "0.10", 24, "2025-02-01");
            financialRecord(code, "2026", "100.00", "0.00", "1000.00", "0.10", "100.00", "100.00").andExpect(status().isCreated());
            riskAssessment(code, "2026", "0.01", "0.5", "1000.00").andExpect(status().isCreated());
        }
        createLoan("LP1", c, "PERSONAL", "1000.00", "1000.00", "0.10", 24, "2025-02-01");
        financialRecord("LP1", "2026", "900.00", "0.00", "1000.00", "0.10", "100.00", "100.00").andExpect(status().isCreated());
        riskAssessment("LP1", "2026", "0.01", "0.5", "1000.00").andExpect(status().isCreated());

        getJson("/api/loans?period=2026&contributionStatus=NEGATIVE&size=2&sort=loanCode,asc")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].loanCode").value("LN1"));
        getJson("/api/loans?period=2026&contributionStatus=NEGATIVE&size=2&page=1&sort=loanCode,asc")
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.last").value(true));
        getJson("/api/loans?period=2026&contributionStatus=POSITIVE").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].loanCode").value("LP1"));
        getJson("/api/loans?period=2026&contributionStatus=BREAK_EVEN").andExpect(jsonPath("$.totalElements").value(0));
        // combined with another filter
        getJson("/api/loans?period=2026&contributionStatus=NEGATIVE&search=ln2").andExpect(jsonPath("$.totalElements").value(1));

        getJson("/api/loans?contributionStatus=NEGATIVE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.period").exists());
        getJson("/api/loans?period=26").andExpect(status().isBadRequest());
    }

    @Test
    void oneBoxSearchMatchesLoanCodeCustomerNameAndCustomerCode() throws Exception {
        long a = createCustomer("C2001", "Rahul Sharma", "a@example.com");
        long b = createCustomer("C2005", "Vikram Singh", "b@example.com");
        createLoan("L1001", a, "PERSONAL", "1000.00", "1000.00", "0.10", 24, "2025-02-01");
        createLoan("L1005", b, "BUSINESS", "1000.00", "1000.00", "0.10", 24, "2025-02-01");

        getJson("/api/loans?search=rahul").andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].loanCode").value("L1001"));
        getJson("/api/loans?search=l1005").andExpect(jsonPath("$.content[0].customer.fullName").value("Vikram Singh"));
        getJson("/api/loans?search=C2005").andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].loanCode").value("L1005"));
        getJson("/api/loans?search=L100").andExpect(jsonPath("$.totalElements").value(2));
        getJson("/api/loans?search=%25").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void closedLoansAreNotActive() throws Exception {
        long c = createCustomer("C2001", "Customer One", "one@example.com");
        createLoan("LA1", c, "PERSONAL", "1000.00", "1000.00", "0.10", 24, "2025-02-01");
        createLoan("LA2", c, "PERSONAL", "1000.00", "0.00", "0.10", 24, "2025-02-01");
        putJson("/api/loans/LA2", """
                {"loanType":"PERSONAL","originalPrincipal":1000.00,"outstandingBalance":0.00,"interestRate":0.10,
                 "tenureMonths":24,"startDate":"2025-02-01","loanStatus":"CLOSED"}""").andExpect(status().isOk());

        getJson("/api/dashboard/summary?period=2026")
                .andExpect(jsonPath("$.totalActiveLoans").value(1))
                .andExpect(jsonPath("$.totalOutstandingBalance").value(1000.00));
    }

    @Test
    @DisplayName("trend has one point per reporting period that has data, oldest first")
    void trendByPeriod() throws Exception {
        createRahulAndLoan();
        financialRecord("L1001", "2025", "50000.00", "1000.00", "500000.00", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2025", "0.05", "0.6", "500000.00").andExpect(status().isCreated());   // ECL 15,000 -> -6,000
        addRahulsInputs2026();                                                                           // -10,000

        getJson("/api/reporting-periods").andExpect(jsonPath("$", contains("2026", "2025")));
        getJson("/api/dashboard/summary?period=2026")
                .andExpect(jsonPath("$.contributionTrend", hasSize(2)))
                .andExpect(jsonPath("$.contributionTrend[0].reportingPeriod").value("2025"))
                .andExpect(jsonPath("$.contributionTrend[0].contributionAfterEcl").value(-6000.00))
                .andExpect(jsonPath("$.contributionTrend[1].reportingPeriod").value("2026"))
                .andExpect(jsonPath("$.contributionTrend[1].contributionAfterEcl").value(-10000.00));
        // a period with no data has no trend point and the totals are zero, not invented
        getJson("/api/dashboard/summary?period=2030").andExpect(jsonPath("$.loansWithData").value(0))
                .andExpect(jsonPath("$.loansWithoutData").value(1)).andExpect(jsonPath("$.contributionAfterEcl").value(0.00));
    }

    @Test
    void emptyDatabaseIsNotAnError() throws Exception {
        getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveLoans").value(0)).andExpect(jsonPath("$.attentionLoans", empty()))
                .andExpect(jsonPath("$.contributionTrend", empty()));
        getJson("/api/reporting-periods").andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        getJson("/api/dashboard/summary").andExpect(status().isBadRequest());
        getJson("/api/dashboard/summary?period=abc").andExpect(status().isBadRequest());
    }

    @Test
    void profitabilityResponseCarriesTheAverageBalance() throws Exception {
        rahulWithInputs();
        getJson("/api/loans/L1001/profitability?period=2026").andExpect(jsonPath("$.averageOutstandingBalance").value(500000.00));
        // When the average balance was not recorded it is null, never 0
        financialRecord("L1001", "2027", "100000.00", "0.00", "null", "0.07", "35000.00", "7000.00").andExpect(status().isCreated());
        riskAssessment("L1001", "2027", "0.05", "0.6", "500000.00").andExpect(status().isCreated());
        getJson("/api/loans/L1001/profitability?period=2027")
                .andExpect(jsonPath("$.averageOutstandingBalance").value(org.hamcrest.Matchers.nullValue()));
    }
}
