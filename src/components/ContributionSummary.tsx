import { ContributionBadge } from './StatusBadge'
import { ECL_DISCLAIMER, InfoTooltip } from './InfoTooltip'
import type { LoanFinancials } from '../types/loan'
import { formatSignedINR } from '../utils/formatCurrency'
import { periodRangeLabel } from '../utils/profitability'

const FINAL_LABEL = {
  positive: 'Positive Estimated Contribution',
  negative: 'Negative Estimated Contribution',
  'break-even': 'Break-even Estimated Contribution',
} as const

/**
 * Contribution Before ECL, Expected Credit Loss and Contribution After ECL – always shown separately.
 * The ECL is presented as an ESTIMATE (amber), never as a confirmed loss.
 */
export function ContributionSummary({ financials }: { financials: LoanFinancials }) {
  const f = financials
  const finalTone =
    f.contributionStatus === 'positive'
      ? 'border-positive/30 bg-positive/5 text-positive'
      : f.contributionStatus === 'negative'
        ? 'border-negative/30 bg-negative/5 text-negative'
        : 'border-slate-200 bg-slate-50 text-muted'

  return (
    <section className="card p-5 sm:p-6" aria-labelledby="contribution-summary-title">
      <div className="mb-5 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 id="contribution-summary-title" className="text-base font-semibold text-ink">
            Financial Contribution Summary
          </h2>
          <p className="mt-0.5 text-sm text-muted">{periodRangeLabel(f.period)}</p>
        </div>
        <ContributionBadge status={f.contributionStatus} long />
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        <div className="rounded-xl border border-line bg-slate-50/60 p-4">
          <p className="text-xs font-medium uppercase tracking-wide text-muted">Contribution Before ECL</p>
          <p className={`tabular mt-2 text-2xl font-bold ${f.contributionBeforeEcl >= 0 ? 'text-positive' : 'text-negative'}`}>
            {formatSignedINR(f.contributionBeforeEcl)}
          </p>
          <p className="mt-1 text-xs text-muted">Total income − funding cost − operational cost</p>
        </div>

        <div className="rounded-xl border border-warning/30 bg-warning/5 p-4">
          <p className="flex items-center gap-1 text-xs font-medium uppercase tracking-wide text-warning">
            Expected Credit Loss (estimate)
            <InfoTooltip text={ECL_DISCLAIMER} label="About Expected Credit Loss" />
          </p>
          <p className="tabular mt-2 text-2xl font-bold text-warning">{formatSignedINR(-f.expectedCreditLoss)}</p>
          <p className="mt-1 text-xs text-muted">PD × LGD × EAD — an estimate, not a recorded loss</p>
        </div>

        <div className={`rounded-xl border p-4 ${finalTone}`}>
          <p className="text-xs font-medium uppercase tracking-wide">Contribution After ECL</p>
          <p className="tabular mt-2 text-2xl font-bold">{formatSignedINR(f.contributionAfterEcl)}</p>
          <p className="mt-1 text-xs font-semibold">{FINAL_LABEL[f.contributionStatus]}</p>
        </div>
      </div>

      {f.contributionStatus === 'negative' && (
        <p className="mt-4 rounded-lg bg-slate-50 px-4 py-3 text-sm leading-relaxed text-muted">
          This figure is negative because the <strong className="font-semibold text-ink">estimated</strong> credit loss is larger than the contribution before ECL. {ECL_DISCLAIMER}
        </p>
      )}
    </section>
  )
}
