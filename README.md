# LoanLens – Smart Loan Profitability Analyzer

A banking analytics frontend that shows what each loan is **estimated** to contribute after interest and
fee income, funding cost, operational cost and Expected Credit Loss (ECL), with an AI Insights assistant
that explains the numbers.

> ECL is an *estimate*. A negative estimated contribution does not mean a borrower has defaulted or that
> the bank has lost money. The UI never presents it that way.

## Run

```bash
npm install
npm run dev        # http://localhost:5173  (mock mode by default, no backend needed)
npm run build      # type-check + production build
npm run lint
```

Demo login: `manager@loanlens.demo` / `Manager@123` (mock sign-in; **not** real authentication).

## Mock mode vs live mode

Configured with environment variables (see `.env.example`; only `VITE_*` values reach the browser,
so never put secrets in them):

| Variable | Default | Meaning |
|---|---|---|
| `VITE_USE_MOCK_DATA` | `true` | `true` = in-memory **mock backend** with demonstration data. `false` = **live** Spring Boot API. |
| `VITE_API_BASE_URL` | `http://localhost:8080/api` | Spring Boot API base URL **including `/api`** (backend mode). |
| `VITE_AI_ENABLED` | `true` | Backend mode: set `false` to switch AI Insights off. (No AI key ever goes in the frontend; see [docs/AI_EXPLANATIONS.md](docs/AI_EXPLANATIONS.md).) |
| `VITE_API_TIMEOUT_MS` | `15000` | Live request timeout. |
| `VITE_MOCK_LATENCY_MS` | `450` | Simulated latency in mock mode. |
| `VITE_API_WITH_CREDENTIALS` | `false` | Send cookies cross-origin (cookie-session backends). |

* **Mock mode** – the mock backend (`src/services/mock/`) answers with the *same JSON wire format* a Spring
  service would, so the real validators, mappers and UI run unchanged. Screens show a "Demonstration data"
  label; the sidebar says *Mock mode*. The AI assistant is a **scripted responder, not an AI model**, and is
  labelled so.
* **Live mode** – requests go to `VITE_API_BASE_URL`. There is **no silent fallback to sample data**: if the
  backend fails you get an error message and a *Try again* button. Demo labels are hidden and mock records are
  never mixed with live ones.

**Backend mode is now integrated with the real Spring Boot + PostgreSQL backend in [`backend/`](backend/README.md)** (Phase 3).
Endpoints used, backend additions, data-model reconciliation and limitations are in [`docs/INTEGRATION.md`](docs/INTEGRATION.md).
(`docs/API_CONTRACT.md` is the historic mock-mode wire format, not the real contract.)

### First time after cloning

```bash
npm install
cp .env.live.example .env.live     # only needed for `npm run dev:live` (backend mode); `.env*` files are git-ignored
```
Environment variable **names** used: frontend `VITE_USE_MOCK_DATA`, `VITE_API_BASE_URL`, `VITE_AI_ENABLED`, `VITE_API_TIMEOUT_MS`;
backend `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `LOANLENS_SEED_ENABLED`, `CORS_ALLOWED_ORIGINS`, `AI_API_KEY`, `AI_PROVIDER`, `AI_MODEL`,
`AI_TIMEOUT_SECONDS` (templates: `.env.example`, `.env.live.example`, `backend/.env.example`, `backend/application-example.properties`).

### Run the full stack

```bash
# 1. PostgreSQL: create the database once (see backend/create-database.sql), then
# 2. Spring Boot (in backend/): credentials come from the environment
export DB_PASSWORD=your-password LOANLENS_SEED_ENABLED=true      # seed = optional demo loans for 2026
mvn spring-boot:run                                               # http://localhost:8080
# 3. React in backend mode (this folder)
npm run dev:live                                                  # http://localhost:5173, VITE_USE_MOCK_DATA=false
```
`npm run dev` runs mock mode (no backend needed). Backend tests: `cd backend && mvn test` (438 tests, real embedded PostgreSQL; 5 live-API tests skip without a key).

Optional AI-written explanations: start the backend with `AI_API_KEY` set (PowerShell: `$env:AI_API_KEY='...'`; optional `AI_MODEL`,
`AI_TIMEOUT_SECONDS`). Without it everything works and AI Insights answers with a clearly labelled deterministic explanation.

## Architecture

```
React screens ──useApi(key)──▶ services/*Service.ts ──▶ mock backend (mock mode)
      ▲                              │             └──▶ apiClient → Spring Boot (live mode)
      │                              ▼
  view models (paise)  ◀── mappers + validators (DTO JSON in rupees → paise, integrity checks)
```

* One **shared data layer**: screens never keep their own copy of a loan. `useApi` + `services/queryCache.ts`
  de-duplicate requests (the loan page, profitability page and breadcrumb share one response), and any
  add/edit calls `invalidate()` so every screen refetches. The same loan therefore shows the same figures everywhere.
* Responses are **validated**: wrong shapes, unknown enum values, or figures that break the accounting
  identities (e.g. income − expenses ≠ contribution) are refused rather than displayed.
* Money is integer paise in the UI; the wire format is rupees. Calculation logic lives in `src/utils/profitability.ts`
  (used by the mock backend; the Spring service owns it in live mode), never in components.

```
src/
  config/env.ts            typed environment access
  services/                apiClient, apiError, mappers, validators, queryCache, keys,
                           loan/dashboard/profitability/aiService, mock/ (mockBackend, mockAi)
  context/                 AuthContext (mock), AppDataContext (period + data version), AiChatContext
  hooks/useApi.ts          loading / error / retry / refetch-on-change
  components/              reusable UI (LoanTable, LoanFormModal, ErrorState, ErrorBoundary, AiEvidenceCard, ...)
  pages/                   Login, Dashboard, LoanManagement, LoanDetails, Profitability,
                           ProfitabilityIndex, AiInsights
  types/                   loan.ts (view models), api.ts (wire DTOs), ai.ts
  utils/                   profitability.ts (all financial logic), formatCurrency.ts, loanFactory.ts
docs/API_CONTRACT.md
```

## AI Insights (`/ai-insights`)

Chat with suggested questions, a *loan in focus* picker, in-session conversation history, a typing indicator,
duplicate-send protection, error + retry, and a **Supporting figures** card under every explanation with links to
the loan and its profitability analysis. In **demonstration mode** answers come from a scripted responder (labelled, not an AI model).
In **backend mode** the page calls `POST /api/ai/explain` for the selected loan and period: the backend calculates the figures, sends
only sanitized facts (no customer name/phone/e-mail) to the AI provider, validates the answer and returns a structured explanation.
The UI separates *calculated by LoanLens* figures, the *explanation* (labelled AI or "deterministic, no AI model was used") and
*recommendations for human review*. The LLM key, data retrieval and guardrails belong to the Spring Boot service
([docs/AI_EXPLANATIONS.md](docs/AI_EXPLANATIONS.md)). Conversations are kept in memory only (they can
contain customer names and figures, so they are not written to browser storage); "Clear conversation" / "Clear all history" discard them.

Formulas, assumptions and the rounding policy: [docs/FINANCIAL_POLICY.md](docs/FINANCIAL_POLICY.md).

## Financial model (simplified, educational)

```
Total Income           = Interest Income + Fee Income
Total Expenses         = Funding Cost + Operational Cost + Expected Credit Loss
Estimated Contribution = Total Income − Total Expenses
Funding Cost           = Average Outstanding Balance × Funding Rate × Time
ECL                    = PD × LGD × EAD
Operational Cost       = Σ (activity driver units × cost per unit)   [Activity-Based Costing]
```

Interest/fee income are supplied inputs (not derived from principal × rate). 6- and 3-month periods are allocated from the
12-month demonstration figures with fixed monthly weights (cumulative rounding, so months always sum to the period total).
Break-even = within ±₹500 of zero. Risk rating is derived from PD (<3 % Low, <8 % Medium, else High).

## Security notes

* No secrets in the frontend; nothing sensitive in `localStorage` (only the mock-session profile, in session storage unless "Remember me").
* Server error bodies and stack traces are never displayed; request/response bodies are never logged.
* The mock login is for demonstration only. Authentication, authorisation and data security must be enforced by Spring Boot.
