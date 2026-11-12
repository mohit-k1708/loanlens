# AI explanations

`POST /api/ai/explain` explains one loan's profitability for one reporting period. It never changes data, never calculates
anything itself and never sees the database.

```
React (AI Insights) ─ POST /api/ai/explain ─▶ AiController ─▶ AiExplanationService
                                                   │  rate limit + 8 KB body cap
                                                   ▼
                          FinancialContextService ─▶ ProfitabilityService.evaluate (read-only) ─▶ PostgreSQL
                                                   │  validated, sanitized facts only
                                                   ▼
                              AiProvider (Anthropic SDK)  ─▶ model answer (untrusted)
                                                   ▼
                          AiResponseValidator ─▶ structured explanation ─▶ React
                                       (on any failure)  DeterministicExplainer ─▶ labelled FALLBACK
```

The browser holds no AI key, database credential or prompt. Only the Spring Boot backend talks to the provider.

## Configuration (environment variables; nothing is committed)

| Variable | Default | Meaning |
|---|---|---|
| `AI_PROVIDER` | `anthropic` | `anthropic` or `none` |
| `AI_API_KEY` | *(empty)* | Provider key. **Empty = no AI**: the endpoint still works and answers with the labelled deterministic explanation |
| `AI_MODEL` | `claude-sonnet-5-5` | Model id |
| `AI_TIMEOUT_SECONDS` | `30` | Whole-request timeout for one provider call (the SDK does not retry) |
| `AI_MAX_OUTPUT_TOKENS` | `4000` | Cap on the model's answer |
| `AI_RATE_LIMIT_PER_MINUTE` | `20` | Per client address, `/api/ai/explain` |
| `AI_MAX_REQUEST_BYTES` | `8192` | Request body cap on `/api/ai/*` |
| `AI_BASE_URL` | *(empty)* | Optional provider URL override (tests, proxies) |

```powershell
$env:AI_API_KEY = "<your key>"      # PowerShell; never put it in a file in the repo
```

The frontend has one switch, `VITE_AI_ENABLED=false`, to turn the page off. It carries no secret.

## Endpoints

`POST /api/ai/explain`

```json
{ "loanId": "L1001", "reportingPeriod": 2026, "question": "Why is this loan showing a negative contribution?",
  "compareToLoanId": "L1002" }
```
`reportingPeriod` may be a number or a string. `compareToLoanId` is optional. The question is 3–500 characters; unknown fields are rejected.

Response (abridged): `source` (`AI` | `FALLBACK`), `fallbackReason`, `provider`/`model` (null for a fallback), `dataStatus`
(`COMPLETE` | `INSUFFICIENT`), `summary`, `facts[]` (backend-calculated, with `display` text such as `₹5,00,000`),
`reasons[]` (`factorKey`, `factor`, `amount`, `amountDisplay`, `impact`, `explanation`), `recommendations[]`, `limitations[]`,
`missingInputs[]`, `humanReviewRequired` (always true).

`GET /api/ai/status` → `{ providerConfigured, provider, model, mode: "AI" | "FALLBACK_ONLY", message }`. No key, no URL.

Fallback reasons: `NOT_CONFIGURED`, `PROVIDER_AUTH` (401/403), `PROVIDER_BILLING` (402), `RATE_LIMITED` (429), `TIMEOUT`, `PROVIDER_ERROR` (network, 5xx/529, invalid model/request, unexpected response format), `INVALID_AI_RESPONSE`, `INSUFFICIENT_DATA`.

Errors use the standard body: 400 validation, 404 unknown loan, 413 body too large, 422 inconsistent cost breakdown,
429 rate limited (with `Retry-After`), 500 generic with an `errorId`.

## What the AI is given and what it may do

* **Only** a sanitized fact list: loan reference, loan type/status, rating, and the numbers (income, costs, ECL inputs and
  result, balances, earlier-period figures when that period is complete, funding-cost +10 / +25 / +50 % what-if figures, and the
  second loan's headline figures if one was chosen). All are computed by the backend.
* **Never** the customer's name, code, e-mail or phone. The manager's question is also scrubbed of those (the customer's own
  identifiers are removed by name; anything that looks like an e-mail address or a long phone/account number is removed) and
  placed in `<question>` tags that the system prompt tells the model to treat as untrusted text.
* The model returns JSON. A reason names a fact by `factorKey`; **the amount shown next to it is the backend's**, not parsed
  from the model's text. The effect (reduces/increases contribution) of income, cost and ECL factors is fixed by the backend.

## Validation of the model's answer (`AiResponseValidator`)

Rejected, and replaced by the deterministic fallback with `fallbackReason = INVALID_AI_RESPONSE`, if the reply:

* is not the agreed JSON shape, or exceeds length/count limits, or names an unknown fact key;
* contains a ₹ amount, percentage or larger number that is not one of the supplied facts (small counts 0–12 and the reporting
  years are allowed; loan codes are not treated as numbers);
* says a customer *will default*, recommends approving/rejecting/declining/writing off/recalling a loan or changing a price or
  interest rate, or describes the ECL as an actual/realised/incurred loss (a negation in the same sentence, such as "does not mean
  the borrower has defaulted", is allowed).

The same checks are run in tests over every deterministic template.

## Fallback

When there is no key, or the provider errors, times out, is rate limited, or its answer fails validation, the response is built by
`DeterministicExplainer` from fixed sentence templates and the same facts. It is clearly **not** AI: `source = FALLBACK`,
`provider`/`model` are null, a limitation states "No AI model was used", and the UI shows "Deterministic explanation — generated
from backend figures. No AI model was used." The question only chooses the template (why negative / largest cost / ECL change /
funding what-if / factors / comparison / plain language).

If the loan lacks its financial record or risk assessment for the period, the provider is **not called**; the answer (HTTP 200,
`dataStatus = INSUFFICIENT`) says the information is insufficient and lists what is missing. Nothing is estimated.

## Security and performance

* No key in the frontend, logs or responses (`AiProperties.toString()` masks it; tests assert it never appears in an exception or response).
* Logged per request: loan code, period, outcome, latency. Never the question, prompt or answer.
* Limits: body 8 KB (413), 20 requests/minute per client address (429; in-memory, per instance, not a replacement for authentication or a gateway), one provider call per request with a hard timeout and no SDK retries.
* Explanation requests are read-only (no snapshots written). No caching: figures must never be stale.

## Verifying the real Anthropic integration (needs your key)

The key is read only from the backend's environment variable `AI_API_KEY`. Nothing in the repo contains one and no file is read or written.

1. Get a key in the Anthropic Console (billing/credits must be active).
2. In a PowerShell terminal (this session only): `$env:AI_API_KEY = "<your key>"`.
3. From `backend/` run `.\scripts\verify-real-ai.ps1`. It refuses to run without a key and never prints it. It runs `RealAnthropicLiveIT`:
   status endpoint, one minimal real request ("Explain why funding costs reduce a bank's loan contribution. Answer in two sentences."),
   one real explanation of the fictitious Rahul sample (must have `source = AI`, the exact backend figures, no fallback), and two free
   negative checks (invalid key, invalid model). Two billable requests in total, each capped by a small output limit.
   Success looks like `Tests run: 5, Failures: 0, Skipped: 0`. Without a key the tests are reported as *skipped*, not passed.
4. For the full app: start the backend with the same variable set (`mvn spring-boot:run`), check `GET http://localhost:8080/api/ai/status`
   (`providerConfigured: true`), then use AI Insights with a loan selected; the answer banner must say "AI-generated explanation · claude-sonnet-5-5".

## Known limitations

* **A real provider call has not been tested.** No API key was available (verification steps above). The provider code (official Anthropic Java SDK 3.2.0) was
  tested against a local stub of the Anthropic API (headers, request body, parsing, 401/429/5xx, timeout, truncated answers),
  and the full stack was run against that stub. Whether a real Claude model's replies pass validation (strict by design) is
  therefore unproven; if they do not, users see the labelled fallback, never a made-up answer.
* **Authentication:** Anthropic's documentation accepts both `Authorization: Bearer <key>` and `x-api-key` (the latter described as a legacy
  fallback that is still supported). SDK 3.2.0 sends Bearer by default; this project sets `x-api-key` explicitly and sends no Authorization
  header (a stub test pins this). If `x-api-key` is ever retired, remove the `putHeader("x-api-key", ...)` line in `AnthropicAiProvider` and the SDK default (Bearer) applies.
* **Request details checked against the official docs:** `POST /v1/messages`; `anthropic-version: 2026-10-21` (sent by the SDK; the docs
  recommend the latest); `claude-sonnet-5-5` is a listed model id; no `temperature`/`top_p`/`top_k` are sent (they return 400 on every model since 2026-10-21).
* No authentication: the rate limit is per network address, and anyone who can reach the backend can ask questions.
* English only. Number validation is deliberately strict and may discard a good answer that rephrases a figure.
