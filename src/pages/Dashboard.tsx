import { Banknote, Layers, ShieldAlert, TrendingUp } from 'lucide-react'
import { Link } from 'react-router-dom'
import { CartesianGrid, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChartCard } from '../components/ChartCard'
import { ChartTooltip } from '../components/ChartTooltip'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { LoanTable } from '../components/LoanTable'
import { DemoDataNotice, PageHeader } from '../components/PageHeader'
import { PeriodSelect } from '../components/PeriodSelect'
import { LoadingRegion, Skeleton } from '../components/Skeleton'
import { StatCard, type Trend } from '../components/StatCard'
import { env } from '../config/env'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { getDashboardSummary } from '../services/dashboardService'
import { keys } from '../services/keys'
import type { DashboardSummary } from '../types/loan'
import { formatCompactINR, formatINR, formatPercent, formatSignedINR } from '../utils/formatCurrency'
import { periodRangeLabel } from '../utils/profitability'

const COLORS = { positive: '#16A34A', negative: '#DC2626', breakEven: '#94A3B8', afterEcl: '#2563EB' }

function DashboardSkeleton() {
  return (
    <LoadingRegion label="Loading portfolio overview">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-40" />
        ))}
      </div>
      <div className="mt-6 grid gap-6 xl:grid-cols-3">
        <Skeleton className="h-80" />
        <Skeleton className="h-80 xl:col-span-2" />
      </div>
      <Skeleton className="mt-6 h-72" />
    </LoadingRegion>
  )
}

function DashboardContent({ data }: { data: DashboardSummary }) {
  const { portfolio: summary, trend, attentionLoans, period } = data
  const rangeLabel = periodRangeLabel(period)

  // Month-on-month movement of portfolio contribution, from the monthly series the backend returned.
  const latest = trend[trend.length - 1]
  const previous = trend.length > 1 ? trend[trend.length - 2] : undefined
  let contributionTrend: Trend | undefined
  if (latest && previous && previous.contributionAfterEcl !== 0) {
    const change = ((latest.contributionAfterEcl - previous.contributionAfterEcl) / Math.abs(previous.contributionAfterEcl)) * 100
    contributionTrend = {
      label: `${change >= 0 ? '+' : ''}${change.toFixed(1)}% ${latest.shortLabel} vs ${previous.shortLabel}`,
      direction: change > 0 ? 'up' : change < 0 ? 'down' : 'flat',
      tone: change > 0 ? 'good' : change < 0 ? 'bad' : 'neutral',
    }
  }

  const distribution = [
    { name: 'Positive contribution', value: summary.positiveCount, fill: COLORS.positive },
    { name: 'Negative contribution', value: summary.negativeCount, fill: COLORS.negative },
    { name: 'Break-even', value: summary.breakEvenCount, fill: COLORS.breakEven },
    // Backend mode: loans with no financial record / risk assessment for the period are their own group, never zeros.
    ...(summary.noDataCount ? [{ name: 'No data for this period', value: summary.noDataCount, fill: '#CBD5E1' }] : []),
  ]
  const pieData = distribution.filter((d) => d.value > 0)
  const share = (n: number) => (summary.totalLoans ? formatPercent((n / summary.totalLoans) * 100, 0) : '0%')

  if (summary.totalLoans === 0) {
    return (
      <div className="card">
        <EmptyState
          icon={Layers}
          title="No loans available"
          description="There are no loans to analyse yet. Add a loan in Loan Management to see portfolio performance."
          action={
            <Link to="/loans" className="btn-primary">
              Go to Loan Management
            </Link>
          }
        />
      </div>
    )
  }

  return (
    <>
      {summary.noDataCount ? (
        <p role="status" className="mb-4 rounded-lg border border-line bg-white px-4 py-2.5 text-sm text-muted">
          {summary.noDataCount} active loan{summary.noDataCount === 1 ? ' has' : 's have'} no financial record and/or risk assessment for {rangeLabel} and {summary.noDataCount === 1 ? 'is' : 'are'} not included in the contribution figures.
        </p>
      ) : null}

      {/* KPI cards */}
      <section aria-label="Key portfolio metrics" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          icon={Layers}
          label="Total Active Loans"
          value={String(summary.totalLoans)}
          description={`${summary.underWatchCount} under watch · ${summary.highRiskCount} rated High risk`}
          to="/loans"
        />
        <StatCard
          icon={Banknote}
          label="Total Outstanding Amount"
          value={formatCompactINR(summary.totalOutstanding)}
          description={`${formatINR(summary.totalOutstanding)} owed across the portfolio`}
          accent="slate"
          to="/loans"
        />
        <StatCard
          icon={TrendingUp}
          label="Total Estimated Contribution"
          value={formatSignedINR(summary.contributionAfterEcl)}
          description={`Before ECL ${formatSignedINR(summary.contributionBeforeEcl)}, less ${formatINR(summary.expectedCreditLoss)} estimated credit loss · ${rangeLabel}`}
          accent={summary.contributionAfterEcl >= 0 ? 'green' : 'red'}
          valueClassName={summary.contributionAfterEcl >= 0 ? 'text-positive' : 'text-negative'}
          trend={contributionTrend}
          to="/profitability"
        />
        <StatCard
          icon={ShieldAlert}
          label="Loans with Negative Estimated Contribution"
          value={String(summary.negativeCount)}
          description={`${share(summary.negativeCount)} of loans. Estimates after ECL, not confirmed losses`}
          accent="red"
          valueClassName={summary.negativeCount > 0 ? 'text-negative' : 'text-ink'}
          to="/loans?status=negative"
        />
      </section>

      {/* Charts */}
      <div className="mt-6 grid gap-6 xl:grid-cols-3">
        <ChartCard title="Contribution Distribution" subtitle={`Loans by estimated contribution · ${rangeLabel}`}>
          <div className="flex flex-col items-center gap-5 sm:flex-row xl:flex-col 2xl:flex-row">
            <div className="relative h-52 w-52 shrink-0" role="img" aria-label={`Donut chart: ${distribution.map((d) => `${d.value} ${d.name}`).join(', ')}`}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={pieData} dataKey="value" nameKey="name" innerRadius={62} outerRadius={92} paddingAngle={pieData.length > 1 ? 3 : 0} stroke="none" />
                  <Tooltip content={<ChartTooltip formatValue={(v) => `${v} loan${v === 1 ? '' : 's'}`} />} />
                </PieChart>
              </ResponsiveContainer>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <span className="tabular text-3xl font-bold text-ink">{summary.totalLoans}</span>
                <span className="text-xs text-muted">total loans</span>
              </div>
            </div>
            <ul className="w-full space-y-3 text-sm">
              {distribution.map((d) => (
                <li key={d.name} className="flex items-center justify-between gap-3">
                  <span className="flex items-center gap-2 text-ink">
                    <span className="size-3 rounded-full" style={{ backgroundColor: d.fill }} aria-hidden="true" />
                    {d.name}
                  </span>
                  <span className="tabular whitespace-nowrap font-semibold text-ink">
                    {d.value} <span className="font-normal text-muted">({share(d.value)})</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </ChartCard>

        <ChartCard
          className="xl:col-span-2"
          title={env.useMock ? 'Monthly Contribution Trend' : 'Contribution Trend by Reporting Period'}
          subtitle={env.useMock ? `Estimated portfolio contribution · ${rangeLabel}` : 'Estimated portfolio contribution for each reporting period that has data'}
          action={
            <ul className="flex flex-wrap gap-4 text-xs text-muted">
              <li className="flex items-center gap-2">
                <span className="h-0.5 w-5 rounded bg-primary" aria-hidden="true" />
                After ECL (estimated contribution)
              </li>
              <li className="flex items-center gap-2">
                <span className="w-5 border-t-2 border-dashed border-slate-400" aria-hidden="true" />
                Before ECL
              </li>
            </ul>
          }
        >
          {/* No history is invented: the backend stores annual periods only, so a trend needs at least two of them. */}
          {trend.length < 2 && !env.useMock ? (
            <div className="flex h-72 flex-col items-center justify-center rounded-xl bg-slate-50 px-6 text-center" role="status">
              <p className="text-sm font-semibold text-ink">
                {trend.length === 0 ? 'No contribution history yet' : `Only reporting period ${trend[0].label} has data`}
              </p>
              <p className="mt-1 max-w-md text-sm text-muted">
                A trend needs at least two reporting periods with financial records and risk assessments. Monthly history is not stored.
                {trend.length === 1 && (
                  <>
                    {' '}
                    Contribution for {trend[0].label}: <strong className="tabular text-ink">{formatSignedINR(trend[0].contributionAfterEcl)}</strong> after estimated credit loss.
                  </>
                )}
              </p>
            </div>
          ) : (
          <div className="h-72" role="img" aria-label={`Line chart of estimated portfolio contribution, ${rangeLabel}`}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={trend} margin={{ top: 8, right: 12, left: 4, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E2E8F0" />
                <XAxis dataKey="shortLabel" tickLine={false} axisLine={false} tick={{ fill: '#64748B', fontSize: 12 }} />
                <YAxis tickLine={false} axisLine={false} width={64} tick={{ fill: '#64748B', fontSize: 12 }} tickFormatter={(v: number) => formatCompactINR(v)} />
                <Tooltip content={<ChartTooltip formatValue={(v) => formatSignedINR(v)} />} />
                <Line type="monotone" dataKey="contributionBeforeEcl" name="Before ECL" stroke="#94A3B8" strokeWidth={2} strokeDasharray="5 4" dot={false} />
                <Line
                  type="monotone"
                  dataKey="contributionAfterEcl"
                  name="After ECL (estimated)"
                  stroke={COLORS.afterEcl}
                  strokeWidth={3}
                  dot={{ r: 3.5, fill: '#fff', strokeWidth: 2 }}
                  activeDot={{ r: 6 }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
          )}
        </ChartCard>
      </div>

      {/* Loans requiring attention */}
      <section className="card mt-6 overflow-hidden" aria-labelledby="attention-title">
        <header className="flex flex-wrap items-start justify-between gap-3 p-5 sm:p-6">
          <div>
            <h2 id="attention-title" className="text-base font-semibold text-ink">
              Loans Requiring Attention
            </h2>
            <p className="mt-0.5 text-sm text-muted">Loans with a negative estimated contribution, a High risk rating, or under watch — most negative first.</p>
          </div>
          <Link to="/loans" className="btn-secondary py-2">
            View all loans
          </Link>
        </header>
        {attentionLoans.length === 0 ? (
          <EmptyState icon={ShieldAlert} title="No loans need attention" description="No loan currently has a negative estimated contribution, a High risk rating, or an under-watch status." />
        ) : (
          <LoanTable loans={attentionLoans} columns={['loanId', 'customer', 'loanType', 'outstanding', 'risk', 'contribution', 'action']} actionLabel="Investigate" caption="Loans requiring attention" />
        )}
      </section>
    </>
  )
}

export default function Dashboard() {
  const { period } = useAppData()
  const { data, error, loading, reload } = useApi(keys.dashboard(period.id), () => getDashboardSummary(period.id), { keepPrevious: true })

  return (
    <>
      <PageHeader
        title="Good morning, Manager"
        subtitle="Here's an overview of your loan portfolio."
        actions={<PeriodSelect className="w-full min-w-64 sm:w-auto" />}
      />
      <DemoDataNotice className="-mt-3 mb-6" />

      {error ? (
        <ErrorState error={error} onRetry={reload} subject="the portfolio overview" />
      ) : data ? (
        <div aria-busy={loading} className={loading ? 'opacity-60 transition' : 'transition'}>
          <DashboardContent data={data} />
        </div>
      ) : (
        <DashboardSkeleton />
      )}
    </>
  )
}
