# LoanLens backend (Phase 3)

Spring Boot 3.5 · Java 21 · Spring Data JPA / Hibernate · PostgreSQL · Flyway · Maven.
A layered monolith: `controller → service → repository → database`, DTOs at the edge.

> **Educational portfolio project.** It models realistic banking concepts but is not a production banking system:
> there is no authentication or authorisation, and the credit-risk and profitability models are deliberately simplified.

## Run

Requirements: JDK 21, Maven 3.9+, a PostgreSQL server.

```bash
# 1. create the database (once). Pick your own password; it is never stored in the repo.
psql -U postgres -f create-database.sql          # creates loanlens_db

# 2. configure through environment variables (nothing secret is committed)
export DB_URL=jdbc:postgresql://localhost:5432/loanlens_db     # default
export DB_USERNAME=postgres                                      # default
export DB_PASSWORD=your-password                                 # no default
export LOANLENS_SEED_ENABLED=true     # optional: load the 13 fictitious demo loans (period 2026)
export AI_API_KEY=your-key            # optional: AI-written explanations. Without it /api/ai/explain still works (labelled fallback)
export AI_MODEL=claude-sonnet-5-5     # optional; also AI_PROVIDER, AI_TIMEOUT_SECONDS: see ../docs/AI_EXPLANATIONS.md

# 3. start
mvn spring-boot:run                    # http://localhost:8080
mvn test                               # 429 tests, includes a real embedded PostgreSQL: no setup needed
mvn package                            # target/loanlens-backend-0.3.0.jar
```

PowerShell: `$env:DB_PASSWORD='your-password'`. Other settings: `PORT`, `CORS_ALLOWED_ORIGINS`
(default `http://localhost:5173,http://localhost:4173`). Health: `GET /actuator/health` (only `health` and `info` are exposed).

## Conventions that matter

| Topic | Rule |
|---|---|
| **Money** | `BigDecimal`, rupees, exactly 2 decimals, `NUMERIC(19,2)` in the database. Rounded **once**, `HALF_UP`, on final results (`MoneyRules`). `double`/`float` are never used. JSON shows `62000.00`, never `6.2E4`. More than 2 decimals in a request is rejected, not rounded. |
| **Rates and probabilities** | **Decimal fractions everywhere**: `0.10` = 10 %. This covers interest rate, funding rate, PD and LGD. One rule avoids "is 10 a percent or a fraction?" bugs, and sending `10` gets an explicit message. (The frontend uses percentages, see *Frontend alignment*.) |
| **Reporting period** | A four-digit year string, e.g. `2026`. Always compared for exact equality, so periods can never be mixed. |
| **Loan id in URLs** | `{loanId}` is the numeric id **or** the loan code (`L1001`), matching how the frontend identifies loans. |
| **Errors** | `{ timestamp, status, error, message, path, fieldErrors?, missingInputs?, errorId? }`. `message` + `fieldErrors` match what the React frontend already understands. Stack traces and internals are never returned; unexpected errors get an `errorId` that is also in the server log. |

## API

| | Endpoint | Notes |
|---|---|---|
| Customers | `POST/GET /api/customers`, `GET/PUT/DELETE /api/customers/{id}` | `?search=` (name, code, e-mail), paging |
| Loans | `POST/GET /api/loans`, `GET/PUT /api/loans/{loanId}` | `?loanCode=` `?customerName=` (contains, case-insensitive) `?loanType=` `?loanStatus=` `?riskRating=` `?page=&size=&sort=` |
| Financial records | `POST/GET /api/loans/{loanId}/financial-records`, `GET/PUT …/{period}` | one per loan per period |
| Risk assessments | `POST/GET /api/loans/{loanId}/risk-assessments`, `GET/PUT …/{period}` | returns the calculated ECL and the derived rating |
| Cost allocations | `POST/GET /api/loans/{loanId}/operational-cost-allocations?period=` | activity-based cost lines |
| **Profitability** | `GET /api/loans/{loanId}/profitability?period=2026` | calculates and stores a snapshot; `…/history?period=` lists stored results |

`PUT` on financial records and risk assessments and the allocation endpoints go beyond the brief's list. A record
cannot be corrected without `PUT`, and the cost breakdown needs a way in.

### The worked example (Rahul Sharma, L1001, 2026)

Verified over real HTTP against PostgreSQL:

```
Total income            62,000.00   (60,000 interest + 2,000 fees; interest is a supplied input, not principal x rate)
Total costs before ECL  42,000.00   (35,000 funding + 7,000 operational)
Contribution before ECL 20,000.00
Expected credit loss    30,000.00   (PD 0.10 x LGD 0.60 x EAD 500,000.00)
Contribution after ECL -10,000.00   NEGATIVE: an ESTIMATE; not a default and not a realised loss
```

## Profitability rules (`ProfitabilityService`)

1. Every input is looked up by `(loan, exact period)`. Records from other periods are never used.
2. **Missing inputs are reported, never zeroed**: HTTP 422 with `missingInputs` naming what to create. Nothing is stored.
3. ECL comes from the risk assessment **of the same period** (`PD × LGD × EAD`).
4. If cost-allocation lines exist they must add up to the record's operational cost, otherwise 422.
5. If the average outstanding balance was supplied, funding cost must equal `balance × funding rate` (±₹1.00).
6. A snapshot is stored with `calculation_version` (`SIMPLIFIED-1.0`) and a timestamp, **once per distinct set of inputs**:
   repeating the request returns the stored snapshot (`newlyCreated: false`); changing an input adds a new row and keeps the
   old one as history. Uniqueness is decided by the database with `INSERT … ON CONFLICT DO NOTHING`, so concurrent identical
   requests cannot create duplicates (tested with 8 parallel requests).
7. The `GET` therefore records data on first use. It is idempotent, but not "safe" in the strict HTTP sense. The brief asked for this.

## Database design decisions

Schema: `src/main/resources/db/migration/V1__create_schema.sql`, owned by **Flyway**. Hibernate runs with
`ddl-auto=validate`, so a mapping that drifts from the real schema fails at startup instead of silently diverging.

| Table | Purpose | Key decisions |
|---|---|---|
| `customers` | people | `customer_code`/`email` unique **only among live rows** (partial unique indexes), so a soft-deleted customer does not block reuse. e-mail is unique case-insensitively. |
| `loans` | one per loan | `loan_code` unique; FK to customer `ON DELETE RESTRICT`; `maturity_date` stored (start + tenure) per the brief; CHECKs: principal > 0, `0 ≤ outstanding ≤ principal`, `0 < rate ≤ 0.60`, tenure 1–480. Never physically deleted: a loan ends via `loan_status` (ACTIVE, UNDER_WATCH, CLOSED, DEFAULTED). |
| `financial_records` | supplied income/cost inputs | `UNIQUE (loan_id, reporting_period)`. **Not versioned**: one record per period, corrected with `PUT`; the history of what each correction produced lives in the snapshots. Optional `average_outstanding_balance` enables the funding-cost check. |
| `risk_assessments` | supplied PD / LGD / EAD | `UNIQUE (loan_id, reporting_period)`; PD, LGD in [0,1]; EAD ≥ 0. **ECL is not stored**: it is derived on read, so it can never disagree with its inputs. `risk_rating` is stored, derived from PD (<3 % LOW, <8 % MEDIUM, else HIGH). |
| `operational_cost_allocations` | activity-based cost lines | `UNIQUE (loan_id, reporting_period, cost_category)`. The financial record keeps the *total*; lines are the breakdown and must reconcile. |
| `profitability_snapshots` | immutable results | `UNIQUE (loan_id, period, calculation_version, input_fingerprint)` where the fingerprint is a SHA-256 of every input value. CHECK constraints enforce the arithmetic (`total_income = interest + fee`, `before = income − costs`, `after = before − ECL`), so even a buggy write cannot store inconsistent figures. |

Cross-cutting choices:

* **Deleting:** a customer with any loan cannot be deleted (409). A customer with no loans is **soft-deleted** (`deleted_at`),
  hidden everywhere by `@SQLRestriction`. All child foreign keys are `ON DELETE RESTRICT`, so history cannot disappear by accident.
* **Integrity in two places:** Bean Validation and the services give friendly 400/409/422 messages; the same rules exist as
  database CHECK/UNIQUE/FK constraints as a safety net, and `DatabaseConstraintsIT` proves the database refuses bad rows on its own.
* **Types:** enums are stored as text with `CHECK … IN (…)` (readable, safe to extend with a migration); timestamps are `TIMESTAMPTZ` (UTC).
* **Indexes:** every foreign key, `loan_type`, `loan_status`, lower-cased loan code and customer name, and
  `(loan_id, reporting_period, calculated_at DESC)` for snapshot history.
* **Listing without N+1:** the customer is fetched with the loan page (`@EntityGraph`) and the latest risk ratings for a whole page
  are fetched in one query.

## Simplified ECL, not IFRS 9

`ECL = PD × LGD × EAD` with one PD, one LGD and one EAD supplied per loan and year. **This is not an IFRS 9 implementation.** Real
expected-credit-loss models can involve multiple forward-looking economic scenarios, discounting at the effective interest rate,
12-month versus lifetime measurement depending on credit "stage", exposure that changes over time, and regulatory/accounting
review. None of that is modelled. Expected Credit Loss is an estimate of potential future loss; a negative estimated
contribution does not mean a borrower has defaulted or that money has been lost.

## Frontend alignment (the React app is unchanged)

Differences between the Phase 2 frontend contract (`../docs/API_CONTRACT.md`) and this backend, to be bridged in the next phase
(an adapter on one side, not a silent change):

| Frontend today | Backend |
|---|---|
| rates in **percent** (`10`) | **fractions** (`0.10`) |
| period ids `12m` / `6m` / `3m` | `2026` (a full year); no partial-year allocation |
| one `GET /api/loans/{id}` returning loan + reporting inputs | loan, financial record and risk assessment are separate resources |
| loan holds `customerName` / `customerId` inline | `Customer` entity; loan references `customerId` |
| status `Active` / `Under Watch`; contribution break-even = within ±₹500 | `ACTIVE` / `UNDER_WATCH` (+ `CLOSED`, `DEFAULTED`); break-even = exactly `0.00` |
| money as rupee JSON numbers | same (rupees, 2 decimals) |
| `/api/dashboard/summary`, `/api/ai/chat` | **not implemented** yet |
| `{message, fieldErrors}` error body | same (extended) |

The 13 seeded loans reproduce the frontend's per-loan contributions and its portfolio total (₹2,54,427.50) exactly
(`SeededDataIT`).

## Financial policy and AI

* Formulas, assumptions, the **rounding policy** and what the Phase 5 review changed: [`../docs/FINANCIAL_POLICY.md`](../docs/FINANCIAL_POLICY.md).
* `POST /api/ai/explain`, `GET /api/ai/status`, safety rules, configuration and limitations: [`../docs/AI_EXPLANATIONS.md`](../docs/AI_EXPLANATIONS.md).

## Tests (438 run, 5 skipped unless `AI_API_KEY` is set)

Run with `mvn test` (unit tests and the PostgreSQL integration tests run together; no database setup needed).

* **Unit (JUnit 5 + Mockito, parameterized):** `FundingCostServiceTest`, `EclCalculationServiceTest`, `ProfitabilityCalculatorTest`,
  `ProfitabilityServiceTest` (incl. a scenario table: Rahul, positive, break-even, high funding / operational / ECL, zero income, other balances),
  `ProfitabilityOverviewServiceTest`, `RiskAssessmentServiceTest`, `FinancialRecordServiceTest`, `LoanServiceTest`,
  `DashboardServiceTest`, `CustomerServiceTest`, `RiskRatingPolicyTest`.
* **Web layer (`@WebMvcTest`):** `ProfitabilityControllerTest`, `ApiErrorHandlingTest`, `AiControllerTest`.
* **Integration (real embedded PostgreSQL 17, full Spring context):** `FinancialConsistencyIT` (loan = profitability = list = dashboard,
  correct-period ECL, periods never mixed, history unchanged, no duplicate snapshots, missing never zero, regressions),
  `PerformanceIT` (SQL statements constant as loans grow; response times), `RahulProfitabilityIT`, `PortfolioEndpointsIT`,
  `CustomerAndLoanApiIT`, `DatabaseConstraintsIT`, `SeededDataIT`.
* **AI:** `AiResponseValidatorTest`, `DeterministicExplainerTest`, `AiExplanationServiceTest`, `FinancialContextServiceTest`,
  `MoneyFormatAndSanitizerTest`, `AiRateLimiterTest`, `AnthropicAiProviderTest` (against a **local stub** of the Anthropic API),
  `AiExplainIT` (no key), `AiExplainStubProviderIT` (real provider code + local stub), `AiRateLimitIT`, and `RealAnthropicLiveIT` (REAL Anthropic API; skipped unless `AI_API_KEY` is set; run it with `scripts/verify-real-ai.ps1`).

## Not included

Authentication/authorisation, partial-period (3/6-month) records, audit trail beyond snapshots, Docker/deployment, OpenAPI
documentation. Rate limiting exists only on `/api/ai/explain` and is in-memory per instance. A real AI provider has not been exercised (no key); see `../docs/AI_EXPLANATIONS.md`.
