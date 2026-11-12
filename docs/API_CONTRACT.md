# LoanLens mock-mode wire format (historic Phase 2 contract)

> **Not the real backend contract.** This describes the JSON the in-browser **mock backend** produces in mock mode. It was written
> before the Spring Boot backend existed and differs from it (rates, periods, resources). The real backend is documented in
> `backend/README.md`; how the frontend maps it is in `docs/INTEGRATION.md`. The only part still shared is the planned
> `POST /ai/chat` shape at the end of this file, which the backend has not implemented yet.

# LoanLens REST API contract (frontend ⇄ Spring Boot)

No Spring Boot source or API documentation existed when Phase 2 was built, so this document **is the
contract the frontend was written against**. Nothing here was reverse-engineered from a backend. The
Spring Boot team can implement it as written, or change it and update the one place that owns the
wire format: `src/services/mappers.ts` (+ `src/types/api.ts`).

Everything below is implemented on the frontend and verified against a stub server that follows this
document. **No endpoint has been verified against a real Spring Boot service yet.**

## Conventions

| Topic | Rule |
|---|---|
| Base URL | `VITE_API_BASE_URL` (e.g. `http://localhost:8080`). No path prefix other than `/api`. |
| Format | JSON, UTF-8. `Accept: application/json`; `Content-Type: application/json` on bodies. |
| **Money** | JSON **number in rupees** (`BigDecimal`, max 2 decimals), e.g. `62000.00`. The frontend converts to integer paise. Costs/expenses are **positive** numbers; only `estimatedContribution`, `contributionBeforeEcl` and evidence `amount` are signed. |
| Percentages | Plain percent numbers: `10` means 10 %. |
| Dates | ISO `YYYY-MM-DD`. |
| Reporting period | Query param `period` = `12m` \| `6m` \| `3m` (trailing months of the demo fiscal year). Default `12m`. Unknown value → `400`. |
| Auth | Not handled by the frontend beyond a mock login. The backend must enforce authentication/authorisation (`401` / `403`). Set `VITE_API_WITH_CREDENTIALS=true` if you use cookie sessions. |
| CORS | The backend must allow the frontend origin (`http://localhost:5173` in dev), methods `GET, POST, PUT, OPTIONS`, headers `Content-Type, Accept, Authorization`. |

### Financial semantics (the backend is authoritative)

```
totalIncome            = interestIncome + feeIncome
totalExpenses          = fundingCost + operationalCost + expectedCreditLoss
contributionBeforeEcl  = totalIncome - fundingCost - operationalCost
estimatedContribution  = totalIncome - totalExpenses          (= contributionBeforeEcl - expectedCreditLoss)
expectedCreditLoss     = PD x LGD x EAD                        (an ESTIMATE, not a realised loss)
fundingCost            = averageOutstandingBalance x fundingRate x timeInYears
operationalCost        = sum(activity.allocatedCost)           (activity-based costing)
```

The frontend **refuses to display** a response whose figures do not satisfy these identities
(tolerance 1 paisa) and shows "unexpected format" instead of wrong numbers.
`contributionStatus`: `positive` \| `negative` \| `break-even` (demo rule: |contribution| ≤ ₹500).

### Error responses

| Status | Frontend behaviour |
|---|---|
| `400` / `409` / `422` | "Validation" error. Optional body `{ "message": "...", "fieldErrors": { "<requestField>": "..." } }` — `message` (≤ 300 chars) and `fieldErrors` are shown on the form. |
| `401` | "Session expired… Sign in again" (button logs out). |
| `403` | "You do not have permission…". |
| `404` | "Record not found" (loan screens show a *Loan not found* page). |
| `5xx` | Generic "server ran into a problem" + Try again. **Server message / stack traces are never shown.** |
| network failure / 15 s timeout | "Unable to reach the LoanLens server" + Try again. |
| `200` with non-JSON / wrong shape | "Unexpected format" + Try again. |

---

## Endpoints

### `GET /api/dashboard/summary?period=12m`
```json
{
  "reportingPeriod": "12m",
  "totalActiveLoans": 13,
  "totalOutstandingAmount": 28700000.00,
  "totalIncome": 0, "totalExpenses": 0,
  "contributionBeforeEcl": 0, "expectedCreditLoss": 0, "estimatedContribution": 0,
  "positiveContributionLoans": 8, "negativeContributionLoans": 4, "breakEvenLoans": 1,
  "underWatchLoans": 2, "highRiskLoans": 4,
  "monthlyTrend": [
    { "month": "Apr 2025", "contributionBeforeEcl": 0, "expectedCreditLoss": 0, "estimatedContribution": 0 }
  ],
  "attentionLoans": [ { "...LoanSummary fields...": "", "reasons": ["Negative estimated contribution", "High risk rating"] } ]
}
```
`monthlyTrend` covers exactly the months of `period` (12 / 6 / 3 entries, label `"Mon YYYY"`), and its
`estimatedContribution` values must sum to the top-level `estimatedContribution`.
`attentionLoans` = negative contribution **or** High risk **or** under watch, most negative first.
Counts must add up: `positive + negative + breakEven = totalActiveLoans`.

### `GET /api/loans?period=12m` → `LoanSummary[]`
```json
{
  "loanId": "L1001", "customerId": "C2001", "customerName": "Rahul Sharma",
  "loanType": "Personal Loan",
  "originalAmount": 500000.00, "outstandingBalance": 500000.00,
  "interestRate": 10, "durationMonths": 60, "startDate": "2025-04-01",
  "status": "Active", "riskRating": "High",
  "totalIncome": 62000.00, "totalExpenses": 72000.00,
  "contributionBeforeEcl": 20000.00, "expectedCreditLoss": 30000.00,
  "estimatedContribution": -10000.00, "contributionStatus": "negative"
}
```
Enums — `loanType`: `Personal Loan, Home Loan, Auto Loan, Business Loan, Education Loan, Gold Loan`;
`status`: `Active, Under Watch`; `riskRating`: `Low, Medium, High`.
Search and filtering are done **client-side** over this list (fine for hundreds of loans). When the
portfolio outgrows that, add server-side `q`, `type`, `risk`, `status`, `page`, `size` parameters.

### `GET /api/loans/{loanId}?period=12m` → `LoanDetail`
`LoanSummary` plus the editable 12-month inputs:
```json
{ "...LoanSummary": "",
  "reportingInputs": {
    "interestIncome": 60000.00, "feeIncome": 2000.00, "averageOutstandingBalance": 500000.00,
    "fundingRatePercent": 7, "supportContacts": 3,
    "probabilityOfDefaultPercent": 10, "lossGivenDefaultPercent": 60 } }
```
Unknown id → `404`.

### `POST /api/loans` → `201` + `LoanDetail`  ·  `PUT /api/loans/{loanId}` → `200` + `LoanDetail`
Request body (`customerId` optional on create — generate if absent; ignored on update):
```json
{ "customerName": "Test Person", "customerId": "C2014", "loanType": "Personal Loan",
  "originalAmount": 1000000, "outstandingBalance": 900000, "interestRate": 10,
  "durationMonths": 60, "startDate": "2025-01-15",
  "interestIncome": 90000, "feeIncome": 0, "fundingRatePercent": 7, "supportContacts": 4,
  "probabilityOfDefaultPercent": 5, "lossGivenDefaultPercent": 50 }
```
Validate server-side (suggested): name ≥ 2 chars; amounts > 0; `outstandingBalance ≤ originalAmount`;
`0 < interestRate ≤ 60`; `durationMonths ≥ 1`; PD/LGD in `[0, 100]`. Return `400` with `fieldErrors`
keyed by the request field names above. Risk rating is derived from PD (`< 3` Low, `< 8` Medium, else High).
EAD = outstanding balance; average outstanding balance defaults to the outstanding balance.
After a successful write every screen refetches, so figures stay consistent.

### `GET /api/loans/{loanId}/profitability?period=12m`
```json
{
  "loanId": "L1001", "reportingPeriod": "12m", "months": 12,
  "interestIncome": 60000.00, "feeIncome": 2000.00, "totalIncome": 62000.00,
  "fundingCost": 35000.00, "operationalCost": 7000.00, "expectedCreditLoss": 30000.00,
  "totalExpenses": 72000.00, "contributionBeforeEcl": 20000.00,
  "estimatedContribution": -10000.00, "contributionStatus": "negative",
  "funding": { "averageOutstandingBalance": 500000.00, "fundingRatePercent": 7, "timeInYears": 1 },
  "operationalActivities": [
    { "activity": "Loan Processing",  "costDriver": "Loan applications processed", "driverUnits": 1,   "costPerUnit": 500.00,  "allocatedCost": 500.00 },
    { "activity": "Customer Support", "costDriver": "Customer service contacts",   "driverUnits": 3,   "costPerUnit": 50.00,   "allocatedCost": 150.00 },
    { "activity": "Loan Servicing",   "costDriver": "₹1,000 of average outstanding balance", "driverUnits": 500, "costPerUnit": 12.70, "allocatedCost": 6350.00 }
  ],
  "creditRisk": {
    "probabilityOfDefaultPercent": 10, "lossGivenDefaultPercent": 60, "exposureAtDefault": 500000.00,
    "expectedCreditLoss12Month": 30000.00, "expectedCreditLossForPeriod": 30000.00 }
}
```
Required identities (see above) and `sum(allocatedCost) = operationalCost`, `expectedCreditLossForPeriod = expectedCreditLoss`.

### `GET /api/loans/{loanId}/risk`
```json
{ "loanId": "L1001", "riskRating": "High", "probabilityOfDefaultPercent": 10,
  "lossGivenDefaultPercent": 60, "exposureAtDefault": 500000.00, "expectedCreditLoss12Month": 30000.00 }
```
Requested only when the *Credit Risk* tab is opened.

### `POST /api/ai/chat`
Request (the browser never holds an LLM key; the backend retrieves the data and calls the model):
```json
{ "loanId": "L1001", "question": "Why is this loan showing negative contribution?",
  "reportingPeriod": "12m", "conversationId": "conv-lx3k9-0" }
```
`loanId` is `null` for portfolio-wide questions. `reportingPeriod` is `12m|6m|3m` (the original brief
used a year string such as `"2026"`; the period ids keep AI answers consistent with every other screen).
Question length ≤ 500 characters. Empty question → `400`.

Response:
```json
{
  "answer": "Rahul Sharma's loan (L1001) has a negative estimated contribution of ₹10,000 ...\n\nSecond paragraph ...",
  "source": "llm",
  "model": "claude-...",
  "evidence": [
    { "title": "Rahul Sharma · L1001", "loanId": "L1001", "periodLabel": "Apr 2025 – Mar 2026",
      "items": [
        { "label": "Interest income", "amount": 60000.00, "tone": "income" },
        { "label": "Funding cost", "amount": -35000.00, "tone": "expense" },
        { "label": "Expected credit loss (estimate)", "amount": -30000.00, "tone": "estimate" },
        { "label": "Estimated contribution", "amount": -10000.00, "tone": "total" },
        { "label": "Probability of Default (PD)", "text": "10%", "tone": "neutral" } ] } ],
  "relatedLoanIds": ["L1001"],
  "suggestions": ["What is the biggest expense in this loan?"]
}
```
* `answer`: paragraphs separated by a blank line. Plain text.
* `source`: `"llm"` (real model) or `"demo-scripted"`. The UI labels `demo-scripted` as "not generated by an AI model".
* `evidence`: **figures retrieved by the backend from the profitability/risk services — never produced by the model.**
  Items carry `amount` (signed rupees) **or** `text`; `tone` ∈ `income | expense | estimate | total | neutral`; an item `loanId` makes the row a link.
* Guardrails for the AI service (backend responsibility): ground every figure in the retrieved data; never
  present ECL as a realised loss or a confirmed default; say when the data cannot answer (e.g. *why* ECL changed).

---

## Status

| Endpoint | Frontend | Backend |
|---|---|---|
| `GET /api/dashboard/summary` | done | **to implement** |
| `GET /api/loans` | done | **to implement** |
| `GET /api/loans/{id}` | done | **to implement** |
| `POST /api/loans` | done | **to implement** |
| `PUT /api/loans/{id}` | done | **to implement** |
| `GET /api/loans/{id}/profitability` | done | **to implement** |
| `GET /api/loans/{id}/risk` | done | **to implement** |
| `POST /api/ai/chat` | done | **to implement** (incl. LLM call + data retrieval) |

Run against a real backend: set `VITE_USE_MOCK_DATA=false` and `VITE_API_BASE_URL=<url>` (see `.env.example`).
