package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Query-efficiency and response-time checks. The point is not a benchmark: it is that the number of SQL statements does
 * NOT grow with the number of loans (no N+1) and that the main read endpoints answer quickly on a realistic portfolio.
 * Time limits are deliberately generous so the test is not flaky on a slow machine.
 */
class PerformanceIT extends AbstractPostgresIT {

    @Autowired EntityManagerFactory entityManagerFactory;

    @FunctionalInterface
    interface Call {
        Object run() throws Exception;
    }

    private Statistics statistics() {
        Statistics s = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    private void loans(int from, int to) throws Exception {
        for (int i = from; i <= to; i++) {
            long customer = createCustomer("C" + (3000 + i), "Customer " + i, "customer" + i + "@example.com");
            String code = "LP" + (1000 + i);
            createLoan(code, customer, "PERSONAL", "500000.00", "450000.00", "0.10", 60, "2025-04-01");
            financialRecord(code, "2026", "60000.00", "2000.00", "480000.00", "0.07", "33600.00", "7000.00").andExpect(status().isCreated());
            riskAssessment(code, "2026", i % 2 == 0 ? "0.10" : "0.02", "0.60", "450000.00").andExpect(status().isCreated());
        }
    }

    private long statementsFor(Call call) throws Exception {
        Statistics s = statistics();
        s.clear();
        call.run();
        return s.getPrepareStatementCount();
    }

    private long millis(Call call) throws Exception {
        call.run();                       // warm-up (class loading, connection, query plans)
        long start = System.nanoTime();
        call.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    @DisplayName("dashboard, list and detail: SQL statement count is constant however many loans there are (no N+1)")
    void statementCountDoesNotGrowWithLoanCount() throws Exception {
        loans(1, 5);
        Call dashboard = () -> getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk());
        Call list = () -> getJson("/api/loans?period=2026&size=100").andExpect(status().isOk());
        Call filtered = () -> getJson("/api/loans?period=2026&contributionStatus=NEGATIVE&size=100").andExpect(status().isOk());
        // warm up lazily-initialised infrastructure so it is not counted
        dashboard.run();
        list.run();
        filtered.run();
        long dashboardSmall = statementsFor(dashboard);
        long listSmall = statementsFor(list);
        long filteredSmall = statementsFor(filtered);

        loans(6, 45);   // 9x more loans

        long dashboardLarge = statementsFor(dashboard);
        long listLarge = statementsFor(list);
        long filteredLarge = statementsFor(filtered);

        System.out.printf("SQL statements (5 loans -> 45 loans): dashboard %d -> %d, list %d -> %d, filtered list %d -> %d%n",
                dashboardSmall, dashboardLarge, listSmall, listLarge, filteredSmall, filteredLarge);
        assertThat(dashboardLarge).as("dashboard statements").isEqualTo(dashboardSmall);
        assertThat(listLarge).as("list statements").isEqualTo(listSmall);
        assertThat(filteredLarge).as("filtered list statements").isEqualTo(filteredSmall);
    }

    @Test
    @DisplayName("profitability detail uses a small, fixed number of statements and the read-only path writes nothing")
    void profitabilityDetailStatements() throws Exception {
        loans(1, 3);
        Call detail = () -> getJson("/api/loans/LP1001/profitability?period=2026").andExpect(status().isOk());
        detail.run();   // first call writes the snapshot
        long statements = statementsFor(detail);
        System.out.println("SQL statements for GET profitability (existing snapshot): " + statements);
        assertThat(statements).isLessThanOrEqualTo(12);
        assertThat(count("profitability_snapshots")).isEqualTo(1);
    }

    @Test
    @DisplayName("response time on a 60-loan portfolio is well inside interactive limits")
    void responseTimes() throws Exception {
        loans(1, 60);

        long dashboard = millis(() -> getJson("/api/dashboard/summary?period=2026").andExpect(status().isOk()));
        long list = millis(() -> getJson("/api/loans?period=2026&size=100").andExpect(status().isOk()));
        long detail = millis(() -> getJson("/api/loans/LP1001/profitability?period=2026").andExpect(status().isOk()));
        System.out.printf("Response time (60 loans, warm, in-process MockMvc + real PostgreSQL): dashboard %d ms, list %d ms, profitability %d ms%n",
                dashboard, list, detail);

        assertThat(dashboard).as("dashboard ms").isLessThan(2000);
        assertThat(list).as("list ms").isLessThan(2000);
        assertThat(detail).as("profitability ms").isLessThan(1000);
    }
}
