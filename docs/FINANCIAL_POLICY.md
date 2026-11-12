# Financial calculation policy

LoanLens is an **educational, simplified** model. It is not a bank accounting, regulatory capital or IFRS 9 system.
This document is the single place that states the formulas, the assumptions behind them and the rounding policy.
The code that implements it is in `backend/src/main/java/com/loanlens/service/`.

## Formulas

| Quantity | Formula | Class |
|---|---|---|
| Funding cost | Average outstanding balance × annual funding rate × (months ÷ 12) | `FundingCostService` |
| Expected Credit Loss (ECL) | PD × LGD × EAD | `EclCalculator` (period lookup: `EclCalculationService`) |
| Total income | Interest income + fee income | `ProfitabilityCalculator` |
| Contribution **before** ECL | Total income − funding cost − operational cost | `ProfitabilityCalculator` |
| Contribution **after** ECL | Contribution before ECL − ECL | `ProfitabilityCalculator` |

Before-ECL and after-ECL contribution are always separate figures. A negative contribution after ECL is an **estimate**,
never described as a loss that has already happened.

Reference scenario (Rahul, loan L1001, period 2026), verified by tests at unit, API and browser level:
principal ₹5,00,000; average balance ₹5,00,000; interest ₹60,000; fee ₹2,000; funding rate 7 % → funding cost ₹35,000;
operational ₹7,000; PD 10 %, LGD 60 %, EAD ₹5,00,000 → total income ₹62,000, before ECL ₹20,000, ECL ₹30,000, **after ECL −₹10,000**.

## Assumptions

* **Reporting period** = one calendar year (`"2026"`). All inputs for a calculation are looked up by the exact (loan, period) pair;
  another period's record or assessment is never substituted, and a missing one is reported, never treated as zero.
* **Funding cost uses the *average outstanding balance* over the period, never the original principal.** A part-repaid loan
  therefore costs less to fund than its principal suggests. Interest and fee income, funding cost and operational cost are
  *supplied inputs*. When an average balance is supplied with a record, the funding cost must agree with
  balance × rate to within ₹1.00, and the average balance cannot exceed the loan's original principal.
* **Part-year periods:** `FundingCostService` supports 1–12 months with the same single-rounding rule, but the stored records
  are annual, so the system does not currently create part-year records.
* **Rates and probabilities are decimal fractions** (0.07 = 7 %). Values like `7` or `10` are rejected (400), because they are
  almost certainly percentages typed as whole numbers.
* **ECL inputs:** PD and LGD in [0, 1]; EAD ≥ 0. EAD is a supplied figure; it is not derived from the outstanding balance.
* **Operational cost** may be broken down by activity. If a breakdown exists it must add up to the operational cost: a
  breakdown that exceeds it is refused when recorded, the operational cost cannot be lowered below it, and a breakdown that
  does not add up makes the loan "unavailable" in the list, dashboard and AI explanation exactly as the detail endpoint
  answers 422.
* **Break-even** means exactly 0.00 in the backend.

## Rounding policy

1. Money is `BigDecimal` rupees with **scale 2**, rounding mode **HALF_UP**. `float` and `double` are never used for money.
2. Rounding happens **once, on a final result**, never on intermediate values: ECL rounds PD × LGD × EAD once; funding cost
   rounds balance × rate × months ÷ 12 once (the division is the rounding step). Summing twelve rounded monthly funding costs
   would not equal the annual cost (₹2,916.67 × 12 = ₹35,000.04 vs ₹35,000.00); a test pins this.
3. Totals and contributions are exact sums/differences of already-rounded 2-decimal components, so they need no further rounding.
4. Half-paisa cases round up (0.005 → 0.01), not to even (a test pins this).
5. JSON carries plain decimals (`62000.00`, never `6.2E+4`; `write-bigdecimal-as-plain`). The UI converts rupees to integer
   paise for display and does not calculate.
6. Indian digit grouping (₹5,00,000) is formatting only, in the UI and in the AI explanation text; it never changes a value.
7. The same calculator classes serve the detail endpoint, the loan list, the dashboard and the AI explanation, so a loan shows the same numbers everywhere.

## What the Phase 5 review found and changed

| Finding | Change |
|---|---|
| The funding-cost formula lived inside `FinancialRecordService` (annual only, no reusable calculator, no way to test it alone). | Extracted `FundingCostService` with validation, part-year support and the single-rounding rule. Behaviour for stored records is unchanged (same ±₹1.00 check). |
| The loan list and dashboard did not apply the cost-breakdown reconciliation that the detail endpoint applies, so a loan could show figures in the list while its detail page returned 422. | `ProfitabilityOverviewService` now applies the same rule (code `COST_ALLOCATION_MISMATCH`). Regression test: `FinancialConsistencyIT`. |
| The operational cost of a record could be lowered below the breakdown already recorded, silently making the loan uncalculable. | `FinancialRecordService` now refuses it (422). |
| A supplied average balance could exceed the loan's principal. | Now refused (422). |
| Profitability could only be computed together with writing a snapshot. | Added read-only `ProfitabilityService.evaluate`; `calculate` reuses it. The AI explanation uses `evaluate`, so explaining never writes data. |

Verified, with no change needed: ECL and profitability arithmetic and rounding; PD/LGD > 1 and negative EAD/rate are rejected;
periods are never mixed; recalculating identical inputs creates no duplicate snapshot; earlier periods' snapshots are not
altered when a new period is added; dashboard totals equal the sum of the per-loan results; missing inputs are never zero;
the dashboard, list and detail run a constant number of SQL statements regardless of how many loans exist (no N+1).

## Not modelled

Multiple economic scenarios, discounting, staging (12-month vs lifetime ECL), exposure that changes over time, taxes, capital
cost, cross-selling, monthly history, prepayments, and any regulatory treatment.
