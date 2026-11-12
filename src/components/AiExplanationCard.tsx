import { Calculator, FileText, Minus, ShieldCheck, Sparkles, TrendingDown, TrendingUp, TriangleAlert, UserCheck } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { AiExplanation, AiFallbackReason, AiFact, AiImpact } from '../types/ai'

/** Headline figures always shown first, in the order a manager reads the calculation. */
const CORE_KEYS = ['totalIncome', 'fundingCost', 'operationalCost', 'contributionBeforeEcl', 'expectedCreditLoss', 'contributionAfterEcl']
const RISK_KEYS = ['probabilityOfDefault', 'lossGivenDefault', 'exposureAtDefault']

const REASON_TEXT: Record<AiFallbackReason, string> = {
  NOT_CONFIGURED: 'No AI model is configured on the server.',
  PROVIDER_ERROR: 'The AI service returned an error.',
  PROVIDER_AUTH: 'The AI service rejected the server’s credentials. Ask your administrator to check the AI configuration.',
  PROVIDER_BILLING: 'The AI service reports a billing or credit problem on the server’s account. Ask your administrator to check it.',
  TIMEOUT: 'The AI service did not answer in time.',
  RATE_LIMITED: 'The AI service is limiting requests right now.',
  INVALID_AI_RESPONSE: 'The AI answer did not pass the server’s checks (for example it contained a figure LoanLens did not supply), so it was discarded.',
  INSUFFICIENT_DATA: 'Required records are missing, so nothing could be calculated.',
}

const IMPACT: Record<AiImpact, { icon: typeof Minus; label: string; className: string }> = {
  NEGATIVE: { icon: TrendingDown, label: 'Reduces contribution', className: 'text-negative' },
  POSITIVE: { icon: TrendingUp, label: 'Increases contribution', className: 'text-positive' },
  NEUTRAL: { icon: Minus, label: 'Context', className: 'text-muted' },
}

function signClass(fact: AiFact): string {
  if (fact.key.startsWith('contribution') || fact.key.includes('.contribution') || fact.key.startsWith('change.')) {
    return fact.value > 0 ? 'text-positive' : fact.value < 0 ? 'text-negative' : 'text-muted'
  }
  if (fact.key === 'expectedCreditLoss') return 'text-warning'
  return 'text-ink'
}

function FactGrid({ facts }: { facts: AiFact[] }) {
  return (
    <dl className="grid gap-x-6 gap-y-0 sm:grid-cols-2">
      {facts.map((f) => (
        <div key={f.key} className="flex items-center justify-between gap-4 border-b border-line py-2 text-sm last:border-b-0">
          <dt className="min-w-0 text-muted">
            {f.label}
            {f.key === 'expectedCreditLoss' && <span className="ml-1 text-xs">(estimate)</span>}
          </dt>
          <dd className={`tabular whitespace-nowrap font-semibold ${signClass(f)}`}>{f.display}</dd>
        </div>
      ))}
    </dl>
  )
}

function SourceBanner({ e }: { e: AiExplanation }) {
  if (e.source === 'llm') {
    return (
      <div data-testid="ai-source" data-source="llm" className="flex items-start gap-2 rounded-lg bg-primary/5 px-3 py-2 text-xs text-primary ring-1 ring-inset ring-primary/15">
        <Sparkles className="mt-px size-3.5 shrink-0" aria-hidden="true" />
        <p>
          <strong>AI-generated explanation</strong>
          {e.model ? ` · ${e.model}` : ''}. The wording was written by an AI model from the figures below; LoanLens calculated those figures and checked the answer before showing it.
        </p>
      </div>
    )
  }
  return (
    <div data-testid="ai-source" data-source="fallback" className="flex items-start gap-2 rounded-lg bg-slate-100 px-3 py-2 text-xs text-ink ring-1 ring-inset ring-slate-200">
      <ShieldCheck className="mt-px size-3.5 shrink-0 text-muted" aria-hidden="true" />
      <p>
        <strong>Deterministic explanation — generated from backend figures. No AI model was used.</strong>
        {e.fallbackReason ? ` ${REASON_TEXT[e.fallbackReason]}` : ''}
      </p>
    </div>
  )
}

/**
 * The structured answer of POST /ai/explain. Three kinds of content are visibly separate:
 *   1. "Calculated by LoanLens"   - figures the backend calculated (authoritative)
 *   2. "Explanation"              - wording, from an AI model OR from fixed backend rules (labelled which)
 *   3. "For human review"         - recommendations that need a person's judgement; never decisions
 */
export function AiExplanationCard({ explanation: e }: { explanation: AiExplanation }) {
  const byKey = new Map(e.facts.map((f) => [f.key, f]))
  const core = CORE_KEYS.map((k) => byKey.get(k)).filter((f): f is AiFact => f !== undefined)
  const risk = RISK_KEYS.map((k) => byKey.get(k)).filter((f): f is AiFact => f !== undefined)
  const shown = new Set([...CORE_KEYS, ...RISK_KEYS])
  const extra = e.facts.filter((f) => !shown.has(f.key))
  const explainer = e.source === 'llm' ? 'written by AI' : 'backend rules'

  return (
    <div className="space-y-4 text-sm leading-relaxed text-ink" data-testid="ai-explanation">
      <SourceBanner e={e} />

      {e.dataStatus === 'insufficient' ? (
        <section role="status" aria-label="Insufficient information" className="rounded-xl border border-warning/40 bg-warning/5 px-4 py-3">
          <p className="flex items-center gap-2 font-semibold text-ink">
            <TriangleAlert className="size-4 text-warning" aria-hidden="true" />
            Not enough information to explain this loan
          </p>
          <p className="mt-2">{e.summary}</p>
          {e.missingInputs.length > 0 && (
            <ul className="mt-2 list-disc space-y-0.5 pl-5">
              {e.missingInputs.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          )}
        </section>
      ) : (
        <>
          <section aria-label="Explanation">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">Explanation · {explainer}</h4>
            <p className="mt-1">{e.summary}</p>
          </section>

          <section aria-label="Calculated by LoanLens" className="rounded-xl border border-line bg-slate-50/70 px-4 py-3">
            <h4 className="flex flex-wrap items-baseline justify-between gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
              <span>Calculated by LoanLens · {e.loanId} · {e.reportingPeriod}</span>
              <span className="font-normal normal-case tracking-normal">backend figures, not written by AI</span>
            </h4>
            <div className="mt-1">
              <FactGrid facts={core} />
            </div>
            {risk.length > 0 && (
              <div className="mt-2 border-t border-line pt-1">
                <FactGrid facts={risk} />
              </div>
            )}
            {extra.length > 0 && (
              <details className="mt-2 text-xs">
                <summary className="cursor-pointer font-semibold text-primary">More figures (earlier period, comparison, hypothetical what-if)</summary>
                <div className="mt-1">
                  <FactGrid facts={extra} />
                </div>
              </details>
            )}
          </section>

          {e.reasons.length > 0 && (
            <section aria-label="Key factors">
              <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">Key factors · explanation {explainer}</h4>
              <ul className="mt-2 space-y-2">
                {e.reasons.map((r, i) => {
                  const meta = IMPACT[r.impact]
                  const Icon = meta.icon
                  return (
                    <li key={`${r.factorKey}-${i}`} className="rounded-lg border border-line bg-white px-3 py-2">
                      <p className="flex flex-wrap items-center justify-between gap-2">
                        <span className={`inline-flex items-center gap-1.5 font-semibold ${meta.className}`}>
                          <Icon className="size-4" aria-hidden="true" />
                          {r.factor}
                          <span className="sr-only"> ({meta.label})</span>
                        </span>
                        <span className="tabular font-semibold text-ink" title="Amount calculated by the backend">
                          {r.amountDisplay}
                        </span>
                      </p>
                      <p className="mt-1 text-ink/90">{r.explanation}</p>
                    </li>
                  )
                })}
              </ul>
            </section>
          )}
        </>
      )}

      {e.recommendations.length > 0 && (
        <section aria-label="Recommendations requiring human review" className="rounded-xl border border-primary/25 bg-primary/5 px-4 py-3">
          <h4 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-primary">
            <UserCheck className="size-4" aria-hidden="true" />
            For human review · not decisions
          </h4>
          <ul className="mt-2 list-disc space-y-1 pl-5">
            {e.recommendations.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </section>
      )}

      {e.limitations.length > 0 && (
        <details className="text-xs text-muted">
          <summary className="cursor-pointer font-semibold">Assumptions and limitations</summary>
          <ul className="mt-1 list-disc space-y-1 pl-5">
            {e.limitations.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </details>
      )}

      {e.dataStatus === 'complete' && (
        <p className="flex flex-wrap gap-x-4 gap-y-1 border-t border-line pt-2 text-xs font-semibold">
          <Link to={`/loans/${e.loanId}`} className="inline-flex items-center gap-1.5 text-primary hover:underline">
            <FileText className="size-3.5" aria-hidden="true" />
            View loan details
          </Link>
          <Link to={`/loans/${e.loanId}/profitability`} className="inline-flex items-center gap-1.5 text-primary hover:underline">
            <Calculator className="size-3.5" aria-hidden="true" />
            Profitability analysis
          </Link>
        </p>
      )}
    </div>
  )
}
