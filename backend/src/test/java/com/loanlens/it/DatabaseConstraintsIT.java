package com.loanlens.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database itself refuses invalid data, independent of the application code. These tests bypass the API and
 * write SQL directly, to prove the constraints in V1__create_schema.sql hold.
 */
class DatabaseConstraintsIT extends AbstractPostgresIT {

    private long customerId;
    private long loanId;

    @BeforeEach
    void rows() {
        customerId = jdbc.queryForObject("""
                INSERT INTO customers (customer_code, full_name, email, employment_type, annual_income)
                VALUES ('C1', 'Rahul Sharma', 'rahul@example.com', 'SALARIED', 900000) RETURNING id""", Long.class);
        loanId = insertLoan("L1", "500000", "500000", "0.10");
    }

    private long insertLoan(String code, String principal, String outstanding, String rate) {
        return jdbc.queryForObject("""
                INSERT INTO loans (loan_code, customer_id, loan_type, original_principal, outstanding_balance, interest_rate,
                                   tenure_months, start_date, maturity_date, loan_status)
                VALUES (?, ?, 'PERSONAL', ?::numeric, ?::numeric, ?::numeric, 60, DATE '2025-04-01', DATE '2030-04-01', 'ACTIVE')
                RETURNING id""", Long.class, code, customerId, principal, outstanding, rate);
    }

    private void insertFinancialRecord(String period, String interest, String fundingRate) {
        jdbc.update("""
                INSERT INTO financial_records (loan_id, reporting_period, interest_income, fee_income, funding_rate, funding_cost, operational_cost)
                VALUES (?, ?, ?::numeric, 0, ?::numeric, 0, 0)""", loanId, period, interest, fundingRate);
    }

    private void insertRisk(String period, String pd, String lgd, String ead, String rating) {
        jdbc.update("""
                INSERT INTO risk_assessments (loan_id, reporting_period, probability_of_default, loss_given_default, exposure_at_default, risk_rating)
                VALUES (?, ?, ?::numeric, ?::numeric, ?::numeric, ?)""", loanId, period, pd, lgd, ead, rating);
    }

    @Test
    void flywayAppliedTheSchemaAndHibernateValidatedIt() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class))
                .contains("customers", "loans", "financial_records", "risk_assessments", "operational_cost_allocations", "profitability_snapshots");
    }

    @Test
    @DisplayName("loan values the database refuses")
    void invalidLoans() {
        assertThatThrownBy(() -> insertLoan("L2", "-1", "0", "0.10")).isInstanceOf(DataIntegrityViolationException.class);          // negative principal
        assertThatThrownBy(() -> insertLoan("L3", "0", "0", "0.10")).isInstanceOf(DataIntegrityViolationException.class);           // zero principal
        assertThatThrownBy(() -> insertLoan("L4", "100", "-1", "0.10")).isInstanceOf(DataIntegrityViolationException.class);         // negative balance
        assertThatThrownBy(() -> insertLoan("L5", "100", "101", "0.10")).isInstanceOf(DataIntegrityViolationException.class);        // balance above principal
        assertThatThrownBy(() -> insertLoan("L6", "100", "100", "0")).isInstanceOf(DataIntegrityViolationException.class);          // zero rate
        assertThatThrownBy(() -> insertLoan("L7", "100", "100", "0.61")).isInstanceOf(DataIntegrityViolationException.class);       // rate above 60%
        assertThatThrownBy(() -> insertLoan("L1", "100", "100", "0.10")).isInstanceOf(DataIntegrityViolationException.class);       // duplicate code
        assertThatThrownBy(() -> jdbc.update("UPDATE loans SET loan_status = 'WHATEVER' WHERE id = ?", loanId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE loans SET customer_id = 424242 WHERE id = ?", loanId)).isInstanceOf(DataIntegrityViolationException.class); // FK
        assertThat(count("loans")).isEqualTo(1);
    }

    @Test
    @DisplayName("no duplicate financial record or risk assessment for the same loan and period")
    void uniquePerLoanAndPeriod() {
        insertFinancialRecord("2026", "60000", "0.07");
        assertThatThrownBy(() -> insertFinancialRecord("2026", "1", "0.07")).isInstanceOf(DataIntegrityViolationException.class);
        insertFinancialRecord("2027", "1", "0.07"); // a different period is fine

        insertRisk("2026", "0.1", "0.6", "500000", "HIGH");
        assertThatThrownBy(() -> insertRisk("2026", "0.2", "0.2", "1", "HIGH")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void financialAndRiskValueRanges() {
        assertThatThrownBy(() -> insertFinancialRecord("2026", "-1", "0.07")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFinancialRecord("2026", "1", "1.5")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFinancialRecord("FY26", "1", "0.07")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRisk("2026", "1.5", "0.5", "1", "HIGH")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRisk("2026", "0.5", "-0.1", "1", "HIGH")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRisk("2026", "0.5", "0.5", "-1", "HIGH")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRisk("2026", "0.5", "0.5", "1", "EXTREME")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("financial_records")).isZero();
        assertThat(count("risk_assessments")).isZero();
    }

    @Test
    @DisplayName("snapshots: the stored arithmetic must be consistent, and identical inputs cannot be stored twice")
    void snapshotConstraints() {
        String insert = """
                INSERT INTO profitability_snapshots (loan_id, reporting_period, interest_income, fee_income, total_income, funding_cost,
                    operational_cost, contribution_before_ecl, expected_credit_loss, contribution_after_ecl, calculation_version, input_fingerprint)
                VALUES (?, '2026', 60000, 2000, ?::numeric, 35000, 7000, ?::numeric, 30000, ?::numeric, 'v1', ?)""";

        jdbc.update(insert, loanId, "62000", "20000", "-10000", "fp-1");                                           // consistent: accepted
        assertThatThrownBy(() -> jdbc.update(insert, loanId, "62000", "20000", "-10000", "fp-1"))                   // same inputs again
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, loanId, "99999", "20000", "-10000", "fp-2"))                   // total income wrong
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, loanId, "62000", "21000", "-10000", "fp-3"))                   // before-ECL wrong
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, loanId, "62000", "20000", "-9999", "fp-4"))                    // after-ECL wrong
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("profitability_snapshots")).isEqualTo(1);
    }

    @Test
    @DisplayName("history is protected: a customer with a loan cannot be physically deleted")
    void referentialIntegrity() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM customers WHERE id = ?", customerId)).isInstanceOf(DataIntegrityViolationException.class);
        insertFinancialRecord("2026", "1", "0.07");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM loans WHERE id = ?", loanId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moneyIsStoredAsExactDecimals() {
        jdbc.update("UPDATE loans SET original_principal = 12345678901234.56, outstanding_balance = 0.10 WHERE id = ?", loanId);
        assertThat(jdbc.queryForObject("SELECT original_principal::text FROM loans WHERE id = ?", String.class, loanId)).isEqualTo("12345678901234.56");
        assertThat(jdbc.queryForObject("SELECT (outstanding_balance + 0.20)::text FROM loans WHERE id = ?", String.class, loanId)).isEqualTo("0.30");
    }
}
