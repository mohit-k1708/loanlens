import { ArrowLeft, FileQuestion, Info } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChartCard } from '../components/ChartCard'
import { ChartTooltip } from '../components/ChartTooltip'
import { ContributionSummary } from '../components/ContributionSummary'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { MissingInputsNotice } from '../components/MissingInputsNotice'
import { env } from '../config/env'
import { PeriodSelect } from '../components/PeriodSelect'
import { LoadingRegion, Skeleton } from '../components/Skeleton'
import { ContributionBadge } from '../components/StatusBadge'
import { useAppData } from '../context/AppDataContext'
import { ALLOCATION_METHOD } from '../data/costModel'
import { useApi } from '../hooks/useApi'
import { isApiError } from '../services/apiError'
import { keys } from '../services/keys'
import { getLoan } from '../services/loanService'
import { getProfitability } from '../services/profitabilityService'
import type { LoanDetail, LoanFinancials } from '../types/loan'
import { formatCompactINR, formatINR, formatPercent, formatSignedINR } from '../utils/formatCurrency'
import { periodLengthLabel, periodRangeLabel } from '../utils/profitability'

const SERIES = {
  interest: { name: 'Interest Income', color: '#16A34A' },
  fee: { name: 'Fee Income', color: '#86EFAC' },
  funding: { name: 'Funding Cost', color: '#2563EB' },
  operational: { name: 'Operational Cost', color: '#94A3B8' },
  ecl: { name: 'Expected Credit Loss (estimate)', color: '#D97706' },
} as const

const formatUnits = (n: number) => Number(n.toFixed(2)).toLocaleString('en-IN')

function Row({ label, amount, kind, strong = false }: { label: string; amount: number; kind: 'income' | 'expense' | 'estimate' | 'total'; strong?: boolean }) {
  const color =
    kind === 'income' ? 'text-positive' : kind === 'expense' ? 'text-negative' : kind === 'estimate' ? 'text-warning' : amount >= 0 ? 'text-positive' : 'text-negative'
  return (
    <tr className={strong ? 'bg-slate-50/80 font-semibold' : ''}>
      <td className="px-5 py-3 text-ink">{label}</td>
      <td className={`tabular px-5 py-3 text-right font-semibold ${color}`}>{formatSignedINR(amount)}</td>
    </tr>
  )
}

export default function Profitability() {
  // Remount per loan so nothing from the previous loan can be shown while the next one loads.
  const { loanId = '' } = useParams()
  return <ProfitabilityLoader key={loanId} loanId={loanId} />
}

function ProfitabilityLoader({ loanId }: { loanId: string }) {
  const { period } = useAppData()
  // The same cache keys as the Loan Details page: both screens read one response, so figures always agree.
  const loanQuery = useApi(keys.loan(loanId, period.id), () => getLoan(loanId, period.id), { keepPrevious: true })
  const finQuery = useApi(keys.profitability(loanId, period.id), () => getProfitability(loanId, period.id), { keepPrevious: true })
  const error = loanQuery.error ?? finQuery.error

  if (isApiError(error) && error.kind === 'not-found') {
    return (
      <div className="card">
        <EmptyState
          icon={FileQuestion}
          title="Loan not found"
          description={`There is no loan with ID “${loanId}”.`}
          action={
            <Link to="/profitability" className="btn-primary">
              Choose a loan to analyse
            </Link>
          }
        />
      </div>
    )
  }
  // No financial record / risk assessment for the period is a normal, explained state, not a failure.
  if (isApiError(finQuery.error) && finQuery.error.kind === 'missing-inputs') {
    return (
      <>
        <Link to={`/loans/${loanId}`} className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-primary">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to Loan Details
        </Link>
        <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-ink">Profitability &amp; Cost Analysis</h1>
            <p className="mt-1.5 text-sm text-muted">
              {loanQuery.data ? `${loanQuery.data.customerName} · Loan ID: ${loanId} · ${loanQuery.data.loanType}` : `Loan ID: ${loanId}`}
            </p>
          </div>
          <PeriodSelect className="min-w-64" />
        </header>
        <MissingInputsNotice missing={finQuery.error.missingInputs ?? []} loanId={loanId} />
      </>
    )
  }
  if (error) {
    return (
      <>
        <Link to={`/loans/${loanId}`} className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-primary">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to Loan Details
        </Link>
        <ErrorState
          error={error}
          subject="the profitability analysis"
          onRetry={() => {
            loanQuery.reload()
            finQuery.reload()
          }}
        />
      </>
    )
  }
  if (!loanQuery.data || !finQuery.data) {
    return (
      <LoadingRegion label="Loading profitability analysis">
        <Skeleton className="h-16 w-96 max-w-full" />
        <div className="mt-6 grid gap-6 xl:grid-cols-2">
          <Skeleton className="h-72" />
          <Skeleton className="h-72" />
        </div>
        <Skeleton className="mt-6 h-48" />
      </LoadingRegion>
    )
  }
  return <ProfitabilityView loan={loanQuery.data} f={finQuery.data} />
}

function ProfitabilityView({ loan, f }: { loan: LoanDetail; f: LoanFinancials }) {
  const period = f.period
  const rangeLabel = periodRangeLabel(period)
  const chartData = [
    { name: 'Total Income', [SERIES.interest.name]: f.interestIncome, [SERIES.fee.name]: f.feeIncome, label: `Total Income · ${formatINR(f.totalIncome)}` },
    {
      name: 'Total Expenses',
      [SERIES.funding.name]: f.fundingCost,
      [SERIES.operational.name]: f.operationalCost,
      [SERIES.ecl.name]: f.expectedCreditLoss,
      label: `Total Expenses · ${formatINR(f.totalExpenses)}`,
    },
  ]

  const timeLabel =
    f.funding.months === 12
      ? '1 year'
      : `${f.funding.months}/12 year (${Number(f.funding.timeInYears.toFixed(2))} year)`

  return (
    <>
      <Link to={`/loans/${loan.id}`} className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-primary">
        <ArrowLeft className="size-4" aria-hidden="true" />
        Back to Loan Details
      </Link>

      <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-ink">Profitability &amp; Cost Analysis</h1>
          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
            <span className="font-medium text-ink">{loan.customerName}</span>· Loan ID: {loan.id} · {loan.loanType}
            <ContributionBadge status={f.contributionStatus} />
          </p>
        </div>
        <PeriodSelect className="min-w-64" />
      </header>

        <div className="space-y-6">
          <div className="grid gap-6 xl:grid-cols-2">
            {/* A. Calculation */}
            <section className="card p-5 sm:p-6" aria-labelledby="calc-title">
              <h2 id="calc-title" className="text-base font-semibold text-ink">
                Contribution Calculation
              </h2>
              <p className="mt-0.5 text-sm text-muted">
                {rangeLabel} · {periodLengthLabel(period)}
              </p>
              <ol className="mt-5 space-y-4 text-sm">
                <li className="rounded-xl border border-line bg-slate-50/60 p-4">
                  <p className="font-semibold text-ink">Total Income = Interest Income + Fee Income</p>
                  <p className="tabular mt-1.5 text-muted">
                    {formatINR(f.interestIncome)} + {formatINR(f.feeIncome)} = <strong className="text-positive">{formatINR(f.totalIncome)}</strong>
                  </p>
                </li>
                <li className="rounded-xl border border-line bg-slate-50/60 p-4">
                  <p className="font-semibold text-ink">Total Expenses = Funding Cost + Operational Cost + Expected Credit Loss</p>
                  <p className="tabular mt-1.5 text-muted">
                    {formatINR(f.fundingCost)} + {formatINR(f.operationalCost)} + {formatINR(f.expectedCreditLoss)} = <strong className="text-negative">{formatINR(f.totalExpenses)}</strong>
                  </p>
                </li>
                <li className="rounded-xl border border-primary/25 bg-primary/5 p-4">
                  <p className="font-semibold text-ink">Estimated Contribution = Total Income − Total Expenses</p>
                  <p className="tabular mt-1.5 text-muted">
                    {formatINR(f.totalIncome)} − {formatINR(f.totalExpenses)} ={' '}
                    <strong className={f.contributionAfterEcl >= 0 ? 'text-positive' : 'text-negative'}>{formatSignedINR(f.contributionAfterEcl)}</strong>
                  </p>
                </li>
              </ol>
              <p className="mt-4 text-xs leading-relaxed text-muted">
                Interest income is a supplied reporting-period figure in this prototype; it is not recalculated as principal × interest rate.
              </p>
            </section>

            {/* B. Breakdown table */}
            <section className="card overflow-hidden" aria-labelledby="breakdown-title">
              <div className="p-5 pb-3 sm:p-6 sm:pb-3">
                <h2 id="breakdown-title" className="text-base font-semibold text-ink">
                  Financial Breakdown
                </h2>
                <p className="mt-0.5 text-sm text-muted">Each component shown separately</p>
              </div>
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-y border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
                    <th scope="col" className="px-5 py-3 font-semibold">Financial Activity</th>
                    <th scope="col" className="px-5 py-3 text-right font-semibold">Amount</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  <Row label="Interest Income" amount={f.interestIncome} kind="income" />
                  <Row label="Fee Income" amount={f.feeIncome} kind="income" />
                  <Row label="Funding Cost" amount={-f.fundingCost} kind="expense" />
                  <Row label="Operational Cost" amount={-f.operationalCost} kind="expense" />
                  <Row label="Expected Credit Loss (estimate)" amount={-f.expectedCreditLoss} kind="estimate" />
                  <Row label="Estimated Contribution" amount={f.contributionAfterEcl} kind="total" strong />
                </tbody>
              </table>
            </section>
          </div>

          {/* C. Contribution before / after ECL */}
          <ContributionSummary financials={f} />

          <div className="grid gap-6 xl:grid-cols-5">
            {/* D. Funding cost */}
            <section className="card p-5 sm:p-6 xl:col-span-2" aria-labelledby="funding-title">
              <h2 id="funding-title" className="text-base font-semibold text-ink">
                Funding Cost
              </h2>
              <p className="mt-0.5 text-sm text-muted">Simplified demonstration method</p>
              <dl className="mt-5 space-y-3 text-sm">
                {[
                  ['Average Outstanding Balance', f.funding.averageOutstandingBalance === null ? 'Not recorded' : formatINR(f.funding.averageOutstandingBalance)],
                  ['Funding Rate', `${formatPercent(f.funding.fundingRatePct)} per annum`],
                  ['Reporting Period', `${rangeLabel} (${timeLabel})`],
                ].map(([k, v]) => (
                  <div key={k} className="flex justify-between gap-4 border-b border-line pb-3">
                    <dt className="text-muted">{k}</dt>
                    <dd className="tabular text-right font-semibold text-ink">{v}</dd>
                  </div>
                ))}
                <div className="flex justify-between gap-4">
                  <dt className="font-semibold text-ink">Funding Cost</dt>
                  <dd className="tabular font-bold text-negative">{formatSignedINR(-f.fundingCost)}</dd>
                </div>
              </dl>
              <p className="tabular mt-4 rounded-lg bg-slate-50 px-3 py-2.5 text-xs leading-relaxed text-muted">
                Funding Cost = Average Outstanding Balance × Funding Rate × Time
                <br />
                {f.funding.averageOutstandingBalance === null ? (
                  <>
                    The average balance was not recorded for this period, so the funding cost of <strong className="text-ink">{formatINR(f.fundingCost)}</strong> is shown as supplied ({formatPercent(f.funding.fundingRatePct)} funding rate).
                  </>
                ) : (
                  <>
                    {formatINR(f.funding.averageOutstandingBalance)} × {formatPercent(f.funding.fundingRatePct)} × {f.funding.months === 12 ? '1' : Number(f.funding.timeInYears.toFixed(2))} = <strong className="text-ink">{formatINR(f.fundingCost)}</strong>
                  </>
                )}
              </p>
            </section>

            {/* E. Operational cost (ABC) */}
            <section className="card overflow-hidden xl:col-span-3" aria-labelledby="ops-title">
              <div className="p-5 pb-3 sm:p-6 sm:pb-3">
                <h2 id="ops-title" className="text-base font-semibold text-ink">
                  Operational Cost · Activity-Based Costing
                </h2>
                <p className="mt-1 text-sm leading-relaxed text-muted">{ALLOCATION_METHOD}</p>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[34rem] text-left text-sm">
                  <thead>
                    <tr className="border-y border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
                      <th scope="col" className="px-5 py-3 font-semibold">Activity</th>
                      <th scope="col" className="px-5 py-3 font-semibold">Cost driver</th>
                      <th scope="col" className="px-5 py-3 text-right font-semibold">Driver units (12 mo)</th>
                      <th scope="col" className="px-5 py-3 text-right font-semibold">Cost per unit</th>
                      <th scope="col" className="px-5 py-3 text-right font-semibold">Allocated cost</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {f.activities.length === 0 && (
                      <tr>
                        <td colSpan={5} className="px-5 py-4 text-muted">
                          No operational cost allocation lines have been recorded for this period. The total below comes from the financial record.
                        </td>
                      </tr>
                    )}
                    {f.activities.map((a) => (
                      <tr key={a.activity}>
                        <td className="px-5 py-3 font-medium text-ink">{a.activity}</td>
                        <td className="px-5 py-3 text-muted">{a.driver}</td>
                        <td className="tabular px-5 py-3 text-right text-ink">{a.driverUnits === undefined ? '—' : formatUnits(a.driverUnits)}</td>
                        <td className="tabular px-5 py-3 text-right text-ink">{a.ratePerUnit === undefined ? '—' : formatINR(a.ratePerUnit)}</td>
                        <td className="tabular px-5 py-3 text-right font-semibold text-ink">{formatINR(a.allocatedCost)}</td>
                      </tr>
                    ))}
                    <tr className="bg-slate-50/80 font-semibold">
                      <td className="px-5 py-3 text-ink" colSpan={4}>
                        Total Operational Cost
                      </td>
                      <td className="tabular px-5 py-3 text-right text-negative">{formatINR(f.operationalCost)}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p className="px-5 py-4 text-xs leading-relaxed text-muted sm:px-6">
                {env.useMock
                  ? 'Cost rates are demonstration assumptions, not real bank cost data.'
                  : 'Allocated amounts are as recorded for this loan. The backend stores the amount per cost category, not driver units or unit rates.'}
                {f.months !== 12 && ' For shorter periods the 12-month activity cost is spread evenly across months.'}
              </p>
            </section>
          </div>

          {/* F. Chart */}
          <ChartCard
            title="Income vs Expenses"
            subtitle={`${rangeLabel} · stacked by component`}
            action={
              <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-xs text-muted">
                {Object.values(SERIES).map((s) => (
                  <li key={s.name} className="flex items-center gap-2">
                    <span className="size-2.5 rounded-sm" style={{ backgroundColor: s.color }} aria-hidden="true" />
                    {s.name}
                  </li>
                ))}
              </ul>
            }
          >
            <div className="h-80" role="img" aria-label={`Stacked bar chart comparing total income ${formatINR(f.totalIncome)} with total expenses ${formatINR(f.totalExpenses)}`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chartData} margin={{ top: 8, right: 16, left: 4, bottom: 0 }} barCategoryGap="35%">
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E2E8F0" />
                  <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fill: '#1E293B', fontSize: 13, fontWeight: 600 }} />
                  <YAxis tickLine={false} axisLine={false} width={68} tick={{ fill: '#64748B', fontSize: 12 }} tickFormatter={(v: number) => formatCompactINR(v)} />
                  <Tooltip cursor={{ fill: 'rgba(148,163,184,0.12)' }} content={<ChartTooltip formatValue={(v) => formatINR(v)} />} />
                  <Bar dataKey={SERIES.interest.name} stackId="a" fill={SERIES.interest.color} />
                  <Bar dataKey={SERIES.fee.name} stackId="a" fill={SERIES.fee.color} />
                  <Bar dataKey={SERIES.funding.name} stackId="a" fill={SERIES.funding.color} />
                  <Bar dataKey={SERIES.operational.name} stackId="a" fill={SERIES.operational.color} />
                  <Bar dataKey={SERIES.ecl.name} stackId="a" fill={SERIES.ecl.color} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <p className="mt-3 text-sm text-muted">
              Income of {formatINR(f.totalIncome)} against expenses of {formatINR(f.totalExpenses)} gives an estimated contribution of{' '}
              <strong className={f.contributionAfterEcl >= 0 ? 'text-positive' : 'text-negative'}>{formatSignedINR(f.contributionAfterEcl)}</strong>. The amber segment is an estimate of potential credit loss.
            </p>
          </ChartCard>

          {f.notes && f.notes.length > 0 && (
            <section className="card p-5 sm:p-6" aria-labelledby="backend-notes-title">
              <h2 id="backend-notes-title" className="text-base font-semibold text-ink">
                How this result was produced
              </h2>
              <p className="mt-1 text-sm text-muted">Calculated by the LoanLens backend from the recorded financial record and risk assessment for {rangeLabel}.</p>
              <ul className="mt-3 list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-ink">
                {f.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
              {f.snapshot && (
                <p className="mt-3 text-xs text-muted">
                  Stored calculation · version {f.snapshot.version} · {new Date(f.snapshot.calculatedAt).toLocaleString('en-IN')}
                  {f.snapshot.newlyCreated ? ' · newly stored' : ' · reused (inputs unchanged)'}
                </p>
              )}
            </section>
          )}

          <div role="note" className="flex gap-3 rounded-xl border border-primary/20 bg-primary/5 p-4 text-sm leading-relaxed text-ink">
            <Info className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden="true" />
            <p>
              <strong>Simplified educational model.</strong> This is a simplified educational profitability model, not a complete bank accounting or regulatory profitability calculation. Expected Credit Loss is an estimate and does not mean the borrower has defaulted or that the bank has already lost this amount.
            </p>
          </div>
        </div>
    </>
  )
}
