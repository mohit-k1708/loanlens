# Frontend ⇄ backend integration (Phase 4)

The React app can run in two modes. The UI is the same in both; only the data source changes.

| | Mock mode (default) | Backend mode |
|---|---|---|
| Switch | `VITE_USE_MOCK_DATA=true` | `VITE_USE_MOCK_DATA=false` |
| Data | in-memory demonstration data | Spring Boot API → PostgreSQL |
| Start | `npm run dev` | `npm run dev:live` (uses `.env.live`) |
| If the server fails | n/a | an error with **Try again**. There is **never** a silent fall-back to sample data |
| AI Insights | scripted responder, labelled "not an AI model" | `POST /ai/explain`: AI wording when the server has a key, otherwise a labelled deterministic explanation (see [AI_EXPLANATIONS.md](AI_EXPLANATIONS.md)) |

`VITE_API_BASE_URL` includes the `/api` prefix: `http://localhost:8080/api`. The backend must allow the frontend's origin
(`CORS_ALLOWED_ORIGINS`, default `http://localhost:5173,http://localhost:4173`).

## Endpoints used (all exist in `backend/`)

| UI | Endpoint |
|---|---|
| Reporting-period selector | `GET /reporting-periods` |
| Dashboard | `GET /dashboard/summary?period=` |
| Loan list: search, filters, paging (server-side) | `GET /loans?period=&search=&loanType=&loanStatus=&riskRating=&contributionStatus=&page=&size=&sort=` |
| Loan details | `GET /loans/{loanId}` |
| Add loan | `POST /customers` (new customer) → `POST /loans` |
| Edit loan | `PUT /loans/{loanId}` |
| Customer picker | `GET /customers` |
| Profitability / details figures | `GET /loans/{loanId}/profitability?period=` |
| Credit risk tab | `GET /loans/{loanId}/risk-assessments/{period}` |
| Add / correct risk assessment | `POST` / `PUT /loans/{loanId}/risk-assessments[/{period}]` |
| Financial records panel | `GET /loans/{loanId}/financial-records` |
| Add / correct financial record | `POST` / `PUT /loans/{loanId}/financial-records[/{period}]` |
| AI Insights: explanation | `POST /ai/explain` (`{loanId, reportingPeriod, question, compareToLoanId?}`) |
| AI Insights: is an AI model configured? | `GET /ai/status` |
| *(rollback of a failed add-loan)* | `DELETE /customers/{id}` |

`{loanId}` is the loan code (`L1001`), which the backend accepts as well as the numeric id.

## Backend additions made for this phase

The frontend needed these and they did not exist, so they were added to the backend (with tests in `PortfolioEndpointsIT` and `SeededDataIT`):

| Addition | Why |
|---|---|
| `GET /api/dashboard/summary?period=` | required by the brief. Built on the same calculator as everything else |
| `GET /api/reporting-periods` | the UI must offer only periods that have data |
| `period` + `contributionStatus` on `GET /api/loans` (each row gets a `profitability` summary) | the list shows each loan's contribution and filters on it server-side; **read-only, writes no snapshots** |
| `search` on `GET /api/loans` | one search box: loan code OR customer name OR customer code |
| `averageOutstandingBalance`, `totalExpenses` in the profitability response | so React never has to calculate a subtotal |

## How the two data models were reconciled

The Phase 2 mock wire format was written before the backend existed and differs from it. Rather than bend the backend, a **live adapter
layer** (`src/services/live/`) maps real responses onto the existing UI view models. The mock backend and mock-mode UI are untouched.

| Frontend (mock) | Backend | Adapter |
|---|---|---|
| rates in percent (`10`) | decimal fractions (`0.10`) | `fractionToPercent` / `percentToFraction` |
| money in rupees → paise | rupees (BigDecimal) | `readPaise` |
| periods `12m/6m/3m` | `2026` (a full year) | in backend mode the selector lists the backend's years |
| `Personal Loan`, `Active`, `High` | `PERSONAL`, `ACTIVE`, `HIGH` | enum tables in `liveMappers.ts` |
| loan id = code, customer id = customer code | `loanCode`, `customerCode` | |
| one loan record with reporting inputs | loan, financial record and risk assessment are separate | separate forms on the loan page |
| monthly trend | annual periods only | trend **per reporting period**; with fewer than two it shows an honest empty state |
| break-even within ±₹500 | exactly `0.00` | backend's rule is used in backend mode |

## Rules the integration follows

* **The backend is the only source of financial figures.** React formats numbers; it never calculates contribution, ECL or totals. The
  backend-mode forms have no browser-side preview.
* **Missing data is explained, never zero.** A loan without a financial record and/or risk assessment for the period shows "No data", the
  profitability page says what is missing, and the dashboard counts it separately and leaves it out of the totals.
* **Responses are validated** before display (shape, enums, and the accounting identities such as income − expenses = contribution).
* **Errors** are mapped to plain messages; server stack traces and messages for 5xx are never shown.
  `400/422` field errors appear on the matching form field; `409` shows the backend's conflict message; `422` with `missingInputs` is shown as a normal "no data" state.
* One loan, one cache: the loan page, profitability page and breadcrumb share a single request. Any save refetches every screen.

## Known limitations

* No monthly history (the backend stores annual periods).
* The backend stores one allocated amount per cost category, so the cost-driver units and unit rates in the ABC table show "—".
* The customer picker lists the first 100 customers.
* AI Insights in backend mode explains **one loan for the selected period** (a loan must be chosen; optionally a second loan to compare). Portfolio-wide questions are only answered by the scripted demonstration mode. The planned `POST /ai/chat` in `API_CONTRACT.md` was superseded by `/ai/explain`.
* No authentication yet (neither side); the sign-in screen is a demo.
* Mock-mode contract: `docs/API_CONTRACT.md` describes the mock wire format only; it is **not** the real backend contract (see `backend/README.md`).
