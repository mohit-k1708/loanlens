-- LoanLens schema, version 1.
-- Conventions
--   * Money:      NUMERIC(19,2)  (exact decimal, never floating point)
--   * Rates/probabilities: NUMERIC(8,6) DECIMAL FRACTIONS (0.100000 = 10%)
--   * Timestamps: TIMESTAMPTZ (stored in UTC)
--   * Reporting period: a 4-digit year as text ('2026'); compared by exact equality, never mixed.
--   * Enumerations are stored as text with CHECK constraints so the database rejects unknown values.

-- ---------------------------------------------------------------------------
-- customers  (soft-deleted via deleted_at; uniqueness only applies to live rows)
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id               BIGSERIAL PRIMARY KEY,
    customer_code    VARCHAR(20)    NOT NULL,
    full_name        VARCHAR(150)   NOT NULL,
    email            VARCHAR(254)   NOT NULL,
    phone            VARCHAR(20),
    employment_type  VARCHAR(20)    NOT NULL,
    annual_income    NUMERIC(19, 2) NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT ck_customers_employment_type CHECK (employment_type IN
        ('SALARIED', 'SELF_EMPLOYED', 'BUSINESS_OWNER', 'PROFESSIONAL', 'RETIRED', 'OTHER')),
    CONSTRAINT ck_customers_annual_income CHECK (annual_income >= 0),
    CONSTRAINT ck_customers_full_name CHECK (length(btrim(full_name)) >= 2)
);
CREATE UNIQUE INDEX ux_customers_code_live  ON customers (customer_code) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_customers_email_live ON customers (lower(email))  WHERE deleted_at IS NULL;
CREATE INDEX ix_customers_full_name ON customers (lower(full_name));

-- ---------------------------------------------------------------------------
-- loans  (never physically deleted: a loan ends by changing loan_status)
-- ---------------------------------------------------------------------------
CREATE TABLE loans (
    id                 BIGSERIAL PRIMARY KEY,
    loan_code          VARCHAR(20)    NOT NULL,
    customer_id        BIGINT         NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    loan_type          VARCHAR(20)    NOT NULL,
    original_principal NUMERIC(19, 2) NOT NULL,
    outstanding_balance NUMERIC(19, 2) NOT NULL,
    interest_rate      NUMERIC(8, 6)  NOT NULL,   -- annual, decimal fraction
    tenure_months      INTEGER        NOT NULL,
    start_date         DATE           NOT NULL,
    maturity_date      DATE           NOT NULL,
    loan_status        VARCHAR(20)    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_loans_loan_code UNIQUE (loan_code),
    CONSTRAINT ck_loans_loan_type CHECK (loan_type IN
        ('PERSONAL', 'HOME', 'AUTO', 'BUSINESS', 'EDUCATION', 'GOLD')),
    CONSTRAINT ck_loans_loan_status CHECK (loan_status IN
        ('ACTIVE', 'UNDER_WATCH', 'CLOSED', 'DEFAULTED')),
    CONSTRAINT ck_loans_principal CHECK (original_principal > 0),
    CONSTRAINT ck_loans_outstanding CHECK (outstanding_balance >= 0 AND outstanding_balance <= original_principal),
    CONSTRAINT ck_loans_interest_rate CHECK (interest_rate > 0 AND interest_rate <= 0.600000),
    CONSTRAINT ck_loans_tenure CHECK (tenure_months BETWEEN 1 AND 480),
    CONSTRAINT ck_loans_maturity CHECK (maturity_date > start_date)
);
CREATE INDEX ix_loans_customer_id ON loans (customer_id);
CREATE INDEX ix_loans_loan_type   ON loans (loan_type);
CREATE INDEX ix_loans_loan_status ON loans (loan_status);
CREATE INDEX ix_loans_loan_code_lower ON loans (lower(loan_code));

-- ---------------------------------------------------------------------------
-- financial_records: supplied income/cost inputs for ONE loan in ONE reporting period.
-- Not versioned: exactly one record per (loan, period). Corrections are updates.
-- ---------------------------------------------------------------------------
CREATE TABLE financial_records (
    id                          BIGSERIAL PRIMARY KEY,
    loan_id                     BIGINT         NOT NULL REFERENCES loans (id) ON DELETE RESTRICT,
    reporting_period            VARCHAR(4)     NOT NULL,
    interest_income             NUMERIC(19, 2) NOT NULL,
    fee_income                  NUMERIC(19, 2) NOT NULL,
    average_outstanding_balance NUMERIC(19, 2),            -- optional: enables the funding-cost consistency check
    funding_rate                NUMERIC(8, 6)  NOT NULL,   -- annual, decimal fraction
    funding_cost                NUMERIC(19, 2) NOT NULL,
    operational_cost            NUMERIC(19, 2) NOT NULL,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_financial_records_loan_period UNIQUE (loan_id, reporting_period),
    CONSTRAINT ck_financial_records_period CHECK (reporting_period ~ '^[0-9]{4}$'),
    CONSTRAINT ck_financial_records_interest CHECK (interest_income >= 0),
    CONSTRAINT ck_financial_records_fee CHECK (fee_income >= 0),
    CONSTRAINT ck_financial_records_avg_balance CHECK (average_outstanding_balance IS NULL OR average_outstanding_balance >= 0),
    CONSTRAINT ck_financial_records_funding_rate CHECK (funding_rate >= 0 AND funding_rate <= 1),
    CONSTRAINT ck_financial_records_funding_cost CHECK (funding_cost >= 0),
    CONSTRAINT ck_financial_records_operational CHECK (operational_cost >= 0)
);

-- ---------------------------------------------------------------------------
-- risk_assessments: supplied PD / LGD / EAD for ONE loan in ONE reporting period.
-- ECL is derived (PD x LGD x EAD) and not stored here, so it can never disagree with its inputs.
-- ---------------------------------------------------------------------------
CREATE TABLE risk_assessments (
    id                     BIGSERIAL PRIMARY KEY,
    loan_id                BIGINT         NOT NULL REFERENCES loans (id) ON DELETE RESTRICT,
    reporting_period       VARCHAR(4)     NOT NULL,
    probability_of_default NUMERIC(8, 6)  NOT NULL,
    loss_given_default     NUMERIC(8, 6)  NOT NULL,
    exposure_at_default    NUMERIC(19, 2) NOT NULL,
    risk_rating            VARCHAR(10)    NOT NULL,
    assessed_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_risk_assessments_loan_period UNIQUE (loan_id, reporting_period),
    CONSTRAINT ck_risk_assessments_period CHECK (reporting_period ~ '^[0-9]{4}$'),
    CONSTRAINT ck_risk_assessments_pd CHECK (probability_of_default >= 0 AND probability_of_default <= 1),
    CONSTRAINT ck_risk_assessments_lgd CHECK (loss_given_default >= 0 AND loss_given_default <= 1),
    CONSTRAINT ck_risk_assessments_ead CHECK (exposure_at_default >= 0),
    CONSTRAINT ck_risk_assessments_rating CHECK (risk_rating IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- ---------------------------------------------------------------------------
-- operational_cost_allocations: the activity-based-costing lines behind a loan's operational cost.
-- At most one line per category per loan per period.
-- ---------------------------------------------------------------------------
CREATE TABLE operational_cost_allocations (
    id                 BIGSERIAL PRIMARY KEY,
    loan_id            BIGINT         NOT NULL REFERENCES loans (id) ON DELETE RESTRICT,
    reporting_period   VARCHAR(4)     NOT NULL,
    cost_category      VARCHAR(30)    NOT NULL,
    allocation_method  VARCHAR(30)    NOT NULL,
    allocation_driver  VARCHAR(150)   NOT NULL,
    allocated_amount   NUMERIC(19, 2) NOT NULL,
    source_description VARCHAR(500),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_allocations_loan_period_category UNIQUE (loan_id, reporting_period, cost_category),
    CONSTRAINT ck_cost_allocations_period CHECK (reporting_period ~ '^[0-9]{4}$'),
    CONSTRAINT ck_cost_allocations_category CHECK (cost_category IN
        ('LOAN_PROCESSING', 'CUSTOMER_SUPPORT', 'LOAN_SERVICING', 'COLLECTIONS', 'OTHER')),
    CONSTRAINT ck_cost_allocations_method CHECK (allocation_method IN
        ('ACTIVITY_BASED_COSTING', 'DIRECT_ASSIGNMENT', 'PROPORTIONAL_ALLOCATION')),
    CONSTRAINT ck_cost_allocations_amount CHECK (allocated_amount >= 0)
);

-- ---------------------------------------------------------------------------
-- profitability_snapshots: immutable history of calculated results.
-- A calculation is stored once per distinct set of inputs: the unique key includes a fingerprint of
-- every input value and the calculation version, so repeated requests never create duplicates,
-- while a genuine change to the inputs (or to the formula version) adds a new row.
-- ---------------------------------------------------------------------------
CREATE TABLE profitability_snapshots (
    id                      BIGSERIAL PRIMARY KEY,
    loan_id                 BIGINT         NOT NULL REFERENCES loans (id) ON DELETE RESTRICT,
    reporting_period        VARCHAR(4)     NOT NULL,
    interest_income         NUMERIC(19, 2) NOT NULL,
    fee_income              NUMERIC(19, 2) NOT NULL,
    total_income            NUMERIC(19, 2) NOT NULL,
    funding_cost            NUMERIC(19, 2) NOT NULL,
    operational_cost        NUMERIC(19, 2) NOT NULL,
    contribution_before_ecl NUMERIC(19, 2) NOT NULL,   -- may be negative
    expected_credit_loss    NUMERIC(19, 2) NOT NULL,
    contribution_after_ecl  NUMERIC(19, 2) NOT NULL,   -- may be negative
    calculation_version     VARCHAR(30)    NOT NULL,
    input_fingerprint       VARCHAR(64)    NOT NULL,
    calculated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_snapshots_loan_period_version_inputs
        UNIQUE (loan_id, reporting_period, calculation_version, input_fingerprint),
    CONSTRAINT ck_snapshots_period CHECK (reporting_period ~ '^[0-9]{4}$'),
    CONSTRAINT ck_snapshots_non_negative CHECK (
        interest_income >= 0 AND fee_income >= 0 AND funding_cost >= 0
        AND operational_cost >= 0 AND expected_credit_loss >= 0),
    CONSTRAINT ck_snapshots_total_income CHECK (total_income = interest_income + fee_income),
    CONSTRAINT ck_snapshots_before_ecl CHECK (contribution_before_ecl = total_income - funding_cost - operational_cost),
    CONSTRAINT ck_snapshots_after_ecl CHECK (contribution_after_ecl = contribution_before_ecl - expected_credit_loss)
);
CREATE INDEX ix_snapshots_loan_period_time ON profitability_snapshots (loan_id, reporting_period, calculated_at DESC);
