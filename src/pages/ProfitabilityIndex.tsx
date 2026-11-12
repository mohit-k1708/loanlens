import { ArrowRight, Landmark } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { DemoDataNotice, PageHeader } from '../components/PageHeader'
import { PeriodSelect } from '../components/PeriodSelect'
import { LoadingRegion, Skeleton } from '../components/Skeleton'
import { ContributionBadge, NoDataBadge } from '../components/StatusBadge'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { keys } from '../services/keys'
import { listLoans } from '../services/loanService'
import { formatINR, formatSignedINR } from '../utils/formatCurrency'
import { periodRangeLabel } from '../utils/profitability'

/** Entry point from the sidebar: pick a loan to open its profitability & cost analysis. */
export default function ProfitabilityIndex() {
  const { period } = useAppData()
  const { data: loans, error, loading, reload } = useApi(keys.loans(period.id), () => listLoans(period.id), { keepPrevious: true })
  const navigate = useNavigate()

  // Lowest contribution first; loans with no data for the period go last.
  const ranked = useMemo(
    () => [...(loans ?? [])].sort((a, b) => (a.estimatedContribution ?? Number.POSITIVE_INFINITY) - (b.estimatedContribution ?? Number.POSITIVE_INFINITY)),
    [loans],
  )
  // Totals add up the loans' own (backend-calculated) figures; loans without data are left out, not counted as zero.
  const totals = useMemo(() => {
    const t = { income: 0, expenses: 0, contribution: 0, withData: 0, withoutData: 0 }
    for (const l of ranked) {
      if (l.totalIncome === null || l.totalExpenses === null || l.estimatedContribution === null) t.withoutData++
      else {
        t.income += l.totalIncome
        t.expenses += l.totalExpenses
        t.contribution += l.estimatedContribution
        t.withData++
      }
    }
    return t
  }, [ranked])

  return (
    <>
      <PageHeader
        title="Profitability Analysis"
        subtitle="Select a loan to see exactly how its estimated contribution is calculated."
        actions={<PeriodSelect className="min-w-64" />}
      />
      <DemoDataNotice className="-mt-3 mb-6" />

      {error ? (
        <ErrorState error={error} onRetry={reload} subject="the loan list" />
      ) : !loans ? (
        <LoadingRegion label="Loading loans">
          <Skeleton className="h-96" />
        </LoadingRegion>
      ) : ranked.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={Landmark}
            title="No loans available"
            description="Add a loan in Loan Management to analyse its profitability."
            action={
              <Link to="/loans" className="btn-primary">
                Go to Loan Management
              </Link>
            }
          />
        </div>
      ) : (
        <section aria-busy={loading} className={`card overflow-hidden ${loading ? 'opacity-60' : ''}`} aria-labelledby="rank-title">
          <div className="p-5 sm:p-6">
            <h2 id="rank-title" className="text-base font-semibold text-ink">
              Loans by estimated contribution
            </h2>
            <p className="mt-0.5 text-sm text-muted">{periodRangeLabel(period)} · lowest contribution first</p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[46rem] text-left text-sm">
              <caption className="sr-only">Loans ranked by estimated contribution</caption>
              <thead>
                <tr className="border-y border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
                  <th scope="col" className="px-5 py-3 font-semibold">Loan</th>
                  <th scope="col" className="px-5 py-3 text-right font-semibold">Total Income</th>
                  <th scope="col" className="px-5 py-3 text-right font-semibold">Total Expenses</th>
                  <th scope="col" className="px-5 py-3 text-right font-semibold">Estimated Contribution</th>
                  <th scope="col" className="px-5 py-3 font-semibold">Status</th>
                  <th scope="col" className="px-5 py-3 text-right font-semibold">
                    <span className="sr-only">Action</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {ranked.map((loan) => {
                  const hasData = loan.estimatedContribution !== null && loan.contributionStatus !== null
                  return (
                    <tr
                      key={loan.id}
                      tabIndex={0}
                      onClick={() => navigate(`/loans/${loan.id}/profitability`)}
                      onKeyDown={(e) => e.key === 'Enter' && navigate(`/loans/${loan.id}/profitability`)}
                      className="group cursor-pointer hover:bg-slate-50 focus-visible:bg-slate-50"
                    >
                      <td className="px-5 py-3.5">
                        <p className="font-medium text-ink">{loan.customerName}</p>
                        <p className="text-xs text-muted">
                          {loan.id} · {loan.loanType}
                        </p>
                      </td>
                      <td className="tabular px-5 py-3.5 text-right text-ink">{loan.totalIncome === null ? '—' : formatINR(loan.totalIncome)}</td>
                      <td className="tabular px-5 py-3.5 text-right text-ink">{loan.totalExpenses === null ? '—' : formatINR(loan.totalExpenses)}</td>
                      <td
                        className={`tabular px-5 py-3.5 text-right font-semibold ${
                          loan.contributionStatus === 'positive' ? 'text-positive' : loan.contributionStatus === 'negative' ? 'text-negative' : 'text-muted'
                        }`}
                      >
                        {loan.estimatedContribution === null ? '—' : formatSignedINR(loan.estimatedContribution)}
                      </td>
                      <td className="px-5 py-3.5">
                        {hasData && loan.contributionStatus ? <ContributionBadge status={loan.contributionStatus} /> : <NoDataBadge missing={loan.missingInputs} />}
                      </td>
                      <td className="px-5 py-3.5 text-right">
                        <Link to={`/loans/${loan.id}/profitability`} onClick={(e) => e.stopPropagation()} className="inline-flex items-center gap-1.5 text-xs font-semibold text-primary hover:underline">
                          {hasData ? 'Analyse' : 'Open'} <ArrowRight className="size-3.5" aria-hidden="true" />
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-line bg-slate-50/80 font-semibold">
                  <td className="px-5 py-3.5 text-ink">
                    Portfolio total ({totals.withData} loan{totals.withData === 1 ? '' : 's'} with data)
                  </td>
                  <td className="tabular px-5 py-3.5 text-right text-ink">{formatINR(totals.income)}</td>
                  <td className="tabular px-5 py-3.5 text-right text-ink">{formatINR(totals.expenses)}</td>
                  <td className={`tabular px-5 py-3.5 text-right ${totals.contribution >= 0 ? 'text-positive' : 'text-negative'}`}>{formatSignedINR(totals.contribution)}</td>
                  <td colSpan={2} />
                </tr>
              </tfoot>
            </table>
          </div>
          {totals.withoutData > 0 && (
            <p className="border-t border-line px-5 py-3 text-xs text-muted sm:px-6">
              {totals.withoutData} loan{totals.withoutData === 1 ? ' has' : 's have'} no financial record and/or risk assessment for this period and {totals.withoutData === 1 ? 'is' : 'are'} not included in the totals.
            </p>
          )}
        </section>
      )}
    </>
  )
}
