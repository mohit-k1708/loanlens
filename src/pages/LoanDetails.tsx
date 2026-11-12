import { ArrowLeft, Calculator, CircleCheck, FileQuestion, FilePlus2, Pencil, Plus, ShieldPlus, Sparkles, TriangleAlert, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { BackendLoanFormModal } from '../components/BackendLoanFormModal'
import { ContributionSummary } from '../components/ContributionSummary'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { FinancialRecordFormModal } from '../components/FinancialRecordFormModal'
import { ECL_DISCLAIMER, InfoTooltip } from '../components/InfoTooltip'
import { LoanFormModal } from '../components/LoanFormModal'
import { missingMessages, NO_RISK_ASSESSMENT } from '../components/MissingInputsNotice'
import { PeriodSelect } from '../components/PeriodSelect'
import { RiskAssessmentFormModal } from '../components/RiskAssessmentFormModal'
import { LoadingRegion, Skeleton } from '../components/Skeleton'
import { ContributionBadge, LoanStatusBadge, NoDataBadge, RiskBadge } from '../components/StatusBadge'
import { Tabs, type TabItem } from '../components/Tabs'
import { env } from '../config/env'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { isApiError } from '../services/apiError'
import { listFinancialRecords } from '../services/backendRecordService'
import { keys } from '../services/keys'
import { getLoan } from '../services/loanService'
import { getProfitability, getRisk } from '../services/profitabilityService'
import type { FinancialRecordView, LoanDetail, LoanFinancials, RiskDetails } from '../types/loan'
import { formatDate, formatDuration, formatINR, formatPercent, formatSignedINR } from '../utils/formatCurrency'
import { periodLengthLabel, periodRangeLabel } from '../utils/profitability'

type TabId = 'overview' | 'financial' | 'credit'
const TABS: readonly TabItem<TabId>[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'financial', label: 'Financial Breakdown' },
  { id: 'credit', label: 'Credit Risk' },
]

function InfoItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-muted">{label}</dt>
      <dd className="tabular mt-1 text-sm font-semibold text-ink">{children}</dd>
    </div>
  )
}

function LoanInformation({ loan }: { loan: LoanDetail }) {
  return (
    <section className="card p-5 sm:p-6" aria-labelledby="loan-info-title">
      <h2 id="loan-info-title" className="mb-5 text-base font-semibold text-ink">
        Customer &amp; Loan Information
      </h2>
      <dl className="grid gap-x-6 gap-y-5 sm:grid-cols-2 lg:grid-cols-3">
        <InfoItem label="Customer Name">{loan.customerName}</InfoItem>
        <InfoItem label="Customer ID">{loan.customerId}</InfoItem>
        <InfoItem label="Loan ID">{loan.id}</InfoItem>
        <InfoItem label="Loan Type">{loan.loanType}</InfoItem>
        <InfoItem label="Original Loan Amount">{formatINR(loan.originalAmount)}</InfoItem>
        <InfoItem label="Outstanding Balance">{formatINR(loan.outstandingBalance)}</InfoItem>
        <InfoItem label="Customer Interest Rate">{formatPercent(loan.interestRate)} p.a.</InfoItem>
        <InfoItem label="Loan Duration">{formatDuration(loan.durationMonths)}</InfoItem>
        <InfoItem label="Loan Start Date">{formatDate(loan.startDate)}</InfoItem>
        {loan.maturityDate && <InfoItem label="Maturity Date">{formatDate(loan.maturityDate)}</InfoItem>}
        <InfoItem label="Loan Status">
          <LoanStatusBadge status={loan.status} />
        </InfoItem>
        <InfoItem label="Risk Rating">
          <RiskBadge rating={loan.riskRating} />
        </InfoItem>
      </dl>
    </section>
  )
}

interface MetricCardProps {
  label: string
  value: string
  tone: 'income' | 'expense' | 'estimate'
  note: string
  badge?: string
}

function MetricCard({ label, value, tone, note, badge }: MetricCardProps) {
  const toneCls = tone === 'income' ? 'text-positive' : tone === 'expense' ? 'text-negative' : 'text-warning'
  const barCls = tone === 'income' ? 'bg-positive' : tone === 'expense' ? 'bg-negative' : 'bg-warning'
  return (
    <div className="card relative overflow-hidden p-4">
      <span className={`absolute inset-y-0 left-0 w-1 ${barCls}`} aria-hidden="true" />
      <p className="flex items-center justify-between gap-2 text-xs font-medium uppercase tracking-wide text-muted">
        {label}
        {badge && <span className="rounded-full bg-warning/10 px-2 py-0.5 text-[0.65rem] font-semibold normal-case tracking-normal text-warning">{badge}</span>}
      </p>
      <p className={`tabular mt-2 text-xl font-bold ${toneCls}`}>{value}</p>
      <p className="mt-1 text-xs text-muted">{note}</p>
    </div>
  )
}

function FinancialCards({ f }: { f: LoanFinancials }) {
  return (
    <section aria-labelledby="fin-cards-title">
      <h2 id="fin-cards-title" className="mb-3 text-base font-semibold text-ink">
        Financial Summary · <span className="font-normal text-muted">{periodRangeLabel(f.period)}</span>
      </h2>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <MetricCard label="Interest Income" value={formatSignedINR(f.interestIncome)} tone="income" note="Recorded for the period" />
        <MetricCard label="Fee Income" value={formatSignedINR(f.feeIncome)} tone="income" note="Recorded for the period" />
        <MetricCard label="Funding Cost" value={formatSignedINR(-f.fundingCost)} tone="expense" note="Cost of funds for the loan" />
        <MetricCard label="Operational Cost" value={formatSignedINR(-f.operationalCost)} tone="expense" note="Allocated via activity costing" />
        <MetricCard label="Expected Credit Loss" value={formatSignedINR(-f.expectedCreditLoss)} tone="estimate" badge="Estimate" note="Estimated, not a recorded loss" />
      </div>
    </section>
  )
}

/** Backend mode: every financial record stored for this loan, with add / correct. */
function FinancialRecordsPanel({ loanId, selectedPeriod, onAdd, onEdit }: { loanId: string; selectedPeriod: string; onAdd: () => void; onEdit: (r: FinancialRecordView) => void }) {
  const { data, error, reload } = useApi(keys.financialRecords(loanId), () => listFinancialRecords(loanId))
  return (
    <section className="card overflow-hidden" aria-labelledby="records-title">
      <div className="flex flex-wrap items-center justify-between gap-3 p-5 sm:p-6">
        <div>
          <h2 id="records-title" className="text-base font-semibold text-ink">
            Financial records
          </h2>
          <p className="mt-0.5 text-sm text-muted">Supplied income and cost inputs, one record per reporting period.</p>
        </div>
        <button type="button" onClick={onAdd} className="btn-secondary py-2">
          <Plus className="size-4" aria-hidden="true" />
          Add financial record
        </button>
      </div>
      {error ? (
        <ErrorState error={error} onRetry={reload} subject="the financial records" bare />
      ) : !data ? (
        <div className="px-6 pb-6">
          <Skeleton className="h-24" />
        </div>
      ) : data.length === 0 ? (
        <EmptyState icon={FilePlus2} title="No financial records yet" description="No financial records have been recorded for this loan. Add one to calculate its profitability." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-3xl text-left text-sm">
            <caption className="sr-only">Financial records by reporting period</caption>
            <thead>
              <tr className="border-y border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
                <th scope="col" className="px-5 py-3 font-semibold">Period</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold">Interest income</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold">Fee income</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold">Funding cost</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold">Operational cost</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold">
                  <span className="sr-only">Action</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.map((r) => (
                <tr key={r.reportingPeriod} className={r.reportingPeriod === selectedPeriod ? 'bg-primary/5' : ''}>
                  <td className="px-5 py-3 font-semibold text-ink">
                    {r.reportingPeriod}
                    {r.reportingPeriod === selectedPeriod && <span className="ml-2 text-xs font-normal text-primary">selected</span>}
                  </td>
                  <td className="tabular px-5 py-3 text-right text-positive">{formatINR(r.interestIncome)}</td>
                  <td className="tabular px-5 py-3 text-right text-positive">{formatINR(r.feeIncome)}</td>
                  <td className="tabular px-5 py-3 text-right text-negative">{formatINR(r.fundingCost)}</td>
                  <td className="tabular px-5 py-3 text-right text-negative">{formatINR(r.operationalCost)}</td>
                  <td className="px-5 py-3 text-right">
                    <button type="button" onClick={() => onEdit(r)} className="inline-flex items-center gap-1.5 text-xs font-semibold text-primary hover:underline" aria-label={`Edit financial record for ${r.reportingPeriod}`}>
                      <Pencil className="size-3.5" aria-hidden="true" />
                      Edit
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function FinancialBreakdownTab({ loanId, f }: { loanId: string; f: LoanFinancials }) {
  const pctOfIncome = (n: number) => (f.totalIncome ? formatPercent((n / f.totalIncome) * 100, 1) : '–')
  const rows: { label: string; amount: number; kind: 'income' | 'expense' | 'estimate' }[] = [
    { label: 'Interest Income', amount: f.interestIncome, kind: 'income' },
    { label: 'Fee Income', amount: f.feeIncome, kind: 'income' },
    { label: 'Funding Cost', amount: -f.fundingCost, kind: 'expense' },
    { label: 'Operational Cost', amount: -f.operationalCost, kind: 'expense' },
    { label: 'Expected Credit Loss (estimate)', amount: -f.expectedCreditLoss, kind: 'estimate' },
  ]
  return (
    <div className="space-y-6">
      <section className="card overflow-hidden" aria-labelledby="breakdown-title">
        <div className="flex flex-wrap items-center justify-between gap-3 p-5 sm:p-6">
          <div>
            <h2 id="breakdown-title" className="text-base font-semibold text-ink">
              Income and cost breakdown
            </h2>
            <p className="mt-0.5 text-sm text-muted">
              {periodRangeLabel(f.period)} · {periodLengthLabel(f.period)}
            </p>
          </div>
          <Link to={`/loans/${loanId}/profitability`} className="btn-primary py-2">
            <Calculator className="size-4" aria-hidden="true" />
            See how it is calculated
          </Link>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-lg text-left text-sm">
            <thead>
              <tr className="border-y border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
                <th scope="col" className="px-6 py-3 font-semibold">Component</th>
                <th scope="col" className="px-6 py-3 text-right font-semibold">Amount</th>
                <th scope="col" className="px-6 py-3 text-right font-semibold">% of total income</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((r) => (
                <tr key={r.label}>
                  <td className="px-6 py-3.5 text-ink">{r.label}</td>
                  <td className={`tabular px-6 py-3.5 text-right font-semibold ${r.kind === 'income' ? 'text-positive' : r.kind === 'expense' ? 'text-negative' : 'text-warning'}`}>{formatSignedINR(r.amount)}</td>
                  <td className="tabular px-6 py-3.5 text-right text-muted">{pctOfIncome(Math.abs(r.amount))}</td>
                </tr>
              ))}
              <tr className="bg-slate-50/70 font-semibold">
                <td className="px-6 py-3.5 text-ink">Estimated Contribution</td>
                <td className={`tabular px-6 py-3.5 text-right ${f.contributionAfterEcl >= 0 ? 'text-positive' : 'text-negative'}`}>{formatSignedINR(f.contributionAfterEcl)}</td>
                <td className="tabular px-6 py-3.5 text-right text-muted">{pctOfIncome(f.contributionAfterEcl)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div className="grid gap-6 md:grid-cols-2">
        <section className="card p-5 sm:p-6">
          <h3 className="text-base font-semibold text-ink">Funding cost</h3>
          <p className="tabular mt-3 text-sm leading-relaxed text-muted">
            {f.funding.averageOutstandingBalance === null ? (
              <>
                {formatPercent(f.funding.fundingRatePct)} funding rate. The average balance was not recorded, so the funding cost of <strong className="text-ink">{formatINR(f.fundingCost)}</strong> is shown as supplied.
              </>
            ) : (
              <>
                {formatINR(f.funding.averageOutstandingBalance)} average balance × {formatPercent(f.funding.fundingRatePct)} funding rate × {Number(f.funding.timeInYears.toFixed(2))} year{f.funding.timeInYears === 1 ? '' : 's'} ={' '}
                <strong className="text-ink">{formatINR(f.fundingCost)}</strong>
              </>
            )}
          </p>
        </section>
        <section className="card p-5 sm:p-6">
          <h3 className="text-base font-semibold text-ink">Operational cost</h3>
          <ul className="mt-3 space-y-1.5 text-sm">
            {f.activities.length === 0 && <li className="text-muted">No allocation lines recorded for this period.</li>}
            {f.activities.map((a) => (
              <li key={a.activity} className="flex justify-between gap-3">
                <span className="text-muted">{a.activity}</span>
                <span className="tabular font-medium text-ink">{formatINR(a.allocatedCost)}</span>
              </li>
            ))}
            <li className="flex justify-between gap-3 border-t border-line pt-2 font-semibold">
              <span className="text-ink">Total</span>
              <span className="tabular text-ink">{formatINR(f.operationalCost)}</span>
            </li>
          </ul>
        </section>
      </div>
    </div>
  )
}

/** Credit risk figures come from the risk endpoint for the selected period; opened only with this tab. */
function CreditRiskTab({ loanId, f, onAdd, onEdit }: { loanId: string; f: LoanFinancials | null; onAdd: () => void; onEdit: (r: RiskDetails) => void }) {
  const { period } = useAppData()
  const { data: risk, error, reload } = useApi(keys.risk(loanId, period.id), () => getRisk(loanId, period.id))

  // Backend mode: no assessment for this period is a normal state, with a way to add one.
  if (!env.useMock && isApiError(error) && error.kind === 'not-found') {
    return (
      <div role="status" className="card">
        <EmptyState
          icon={ShieldPlus}
          title="No risk assessment for this period"
          description={`${NO_RISK_ASSESSMENT} Expected Credit Loss and the profitability calculation need one.`}
          action={
            <button type="button" onClick={onAdd} className="btn-primary">
              <Plus className="size-4" aria-hidden="true" />
              Add risk assessment
            </button>
          }
        />
      </div>
    )
  }
  if (error) return <ErrorState error={error} onRetry={reload} subject="credit risk details" />
  if (!risk) {
    return (
      <LoadingRegion label="Loading credit risk details">
        <Skeleton className="h-64" />
      </LoadingRegion>
    )
  }

  const tiles = [
    { label: 'Probability of Default (PD)', value: formatPercent(risk.pdPct), note: 'Likelihood of default (model input)' },
    { label: 'Loss Given Default (LGD)', value: formatPercent(risk.lgdPct), note: 'Share of exposure lost if default occurs' },
    { label: 'Exposure at Default (EAD)', value: formatINR(risk.ead), note: 'Amount exposed at the time of default' },
  ]
  return (
    <div className="space-y-6">
      <section className="card p-5 sm:p-6" aria-labelledby="credit-title">
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="credit-title" className="flex items-center gap-1.5 text-base font-semibold text-ink">
              Credit Risk Details
              <InfoTooltip text={ECL_DISCLAIMER} label="About Expected Credit Loss" />
            </h2>
            {risk.reportingPeriod && (
              <p className="mt-0.5 text-xs text-muted">
                Reporting period {risk.reportingPeriod}
                {risk.assessedAt && <> · assessed {new Date(risk.assessedAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}</>}
              </p>
            )}
          </div>
          <div className="flex items-center gap-3">
            <RiskBadge rating={risk.riskRating} />
            {!env.useMock && (
              <button type="button" onClick={() => onEdit(risk)} className="btn-secondary px-3 py-1.5 text-xs">
                <Pencil className="size-3.5" aria-hidden="true" />
                Edit assessment
              </button>
            )}
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {tiles.map((t) => (
            <div key={t.label} className="rounded-xl border border-line bg-slate-50/60 p-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted">{t.label}</p>
              <p className="tabular mt-2 text-xl font-bold text-ink">{t.value}</p>
              <p className="mt-1 text-xs text-muted">{t.note}</p>
            </div>
          ))}
          <div className="rounded-xl border border-warning/30 bg-warning/5 p-4">
            <p className="text-xs font-medium uppercase tracking-wide text-warning">Expected Credit Loss (ECL)</p>
            <p className="tabular mt-2 text-xl font-bold text-warning">{formatINR(risk.expectedCreditLoss12Month)}</p>
            <p className="mt-1 text-xs text-muted">An estimate — not a recorded loss</p>
          </div>
        </div>

        <div className="mt-5 rounded-xl bg-slate-50 p-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted">How ECL is calculated</p>
          <p className="tabular mt-2 text-sm text-ink">
            ECL = PD × LGD × EAD = {formatPercent(risk.pdPct)} × {formatPercent(risk.lgdPct)} × {formatINR(risk.ead)} = <strong>{formatINR(risk.expectedCreditLoss12Month)}</strong>
          </p>
          {!env.useMock && <p className="mt-2 text-xs text-muted">The ECL figure is calculated by the LoanLens backend. This is a simplified educational model, not a full IFRS 9 calculation.</p>}
          {f && f.months !== 12 && (
            <p className="mt-2 text-xs text-muted">
              For the selected {f.months}-month period, {formatINR(f.credit.eclForPeriod)} of the 12-month ECL is attributed to the period (demonstration allocation).
            </p>
          )}
        </div>
      </section>

      <div role="note" className="flex gap-3 rounded-xl border border-warning/30 bg-warning/5 p-4 text-sm leading-relaxed text-ink">
        <TriangleAlert className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden="true" />
        <p>{ECL_DISCLAIMER}</p>
      </div>
    </div>
  )
}

export default function LoanDetails() {
  // Remount per loan so tab / modal state never leaks from one loan to the next.
  const { loanId = '' } = useParams()
  return <LoanDetailsView key={loanId} loanId={loanId} />
}

function LoanDetailsView({ loanId }: { loanId: string }) {
  const { period, invalidate } = useAppData()
  const [tab, setTab] = useState<TabId>('overview')
  const [editOpen, setEditOpen] = useState(false)
  const [recordModal, setRecordModal] = useState<{ existing?: FinancialRecordView } | null>(null)
  const [riskModal, setRiskModal] = useState<{ existing?: RiskDetails } | null>(null)
  const [notice, setNotice] = useState('')

  // keepPrevious is safe here: the view is remounted per loan, so only the period or an edit can change the key.
  const loanQuery = useApi(keys.loan(loanId, period.id), () => getLoan(loanId, period.id), { keepPrevious: true })
  const finQuery = useApi(keys.profitability(loanId, period.id), () => getProfitability(loanId, period.id), { keepPrevious: true })
  const loan = loanQuery.data
  // A period without a financial record / risk assessment is a normal state (shown on the page), not a failure.
  const missing = isApiError(finQuery.error) && finQuery.error.kind === 'missing-inputs' ? (finQuery.error.missingInputs ?? []) : null
  const financials = missing ? null : finQuery.data
  const error = loanQuery.error ?? (missing ? undefined : finQuery.error)
  const retry = () => {
    loanQuery.reload()
    finQuery.reload()
  }

  if (isApiError(error) && error.kind === 'not-found') {
    return (
      <div className="card">
        <EmptyState
          icon={FileQuestion}
          title="Loan not found"
          description={`Loan information could not be found for ID “${loanId}”.`}
          action={
            <Link to="/loans" className="btn-primary">
              Back to Loans
            </Link>
          }
        />
      </div>
    )
  }

  const saved = (message: string) => {
    setNotice(message)
    invalidate() // every screen (this page, the dashboard, the list) refetches from the backend
  }

  const loaded = loan && (financials || missing)

  return (
    <>
      <Link to="/loans" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-primary">
        <ArrowLeft className="size-4" aria-hidden="true" />
        Back to Loans
      </Link>

      {error ? (
        <ErrorState error={error} onRetry={retry} subject="loan information" />
      ) : !loaded ? (
        <LoadingRegion label="Loading loan details">
          <Skeleton className="h-16 w-96 max-w-full" />
          <Skeleton className="mt-6 h-12" />
          <div className="mt-6 grid gap-6 xl:grid-cols-2">
            <Skeleton className="h-64" />
            <Skeleton className="h-64" />
          </div>
        </LoadingRegion>
      ) : (
        <>
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <div className="flex flex-wrap items-center gap-3">
                <h1 className="text-2xl font-bold tracking-tight text-ink">{loan.customerName}</h1>
                {financials ? <ContributionBadge status={financials.contributionStatus} long /> : <NoDataBadge missing={missing ?? undefined} />}
              </div>
              <p className="mt-1.5 text-sm text-muted">
                <span className="font-medium text-ink">Loan ID: {loan.id}</span> · {loan.loanType}
              </p>
            </div>
            <div className="flex flex-wrap items-end gap-3">
              <PeriodSelect className="min-w-64" />
              <button type="button" onClick={() => setEditOpen(true)} className="btn-secondary">
                <Pencil className="size-4" aria-hidden="true" />
                Edit Loan
              </button>
              {env.aiEnabled && (
                <Link to={`/ai-insights?loan=${encodeURIComponent(loan.id)}`} className="btn-secondary">
                  <Sparkles className="size-4" aria-hidden="true" />
                  Ask AI
                </Link>
              )}
              <Link to={`/loans/${loan.id}/profitability`} className="btn-primary">
                <Calculator className="size-4" aria-hidden="true" />
                Profitability &amp; Cost Analysis
              </Link>
            </div>
          </header>

          {notice && (
            <div role="status" className="mb-5 flex items-center gap-3 rounded-xl border border-positive/30 bg-positive/5 px-4 py-3 text-sm text-ink">
              <CircleCheck className="size-5 shrink-0 text-positive" aria-hidden="true" />
              <span className="flex-1">{notice}</span>
              <button type="button" onClick={() => setNotice('')} aria-label="Dismiss message" className="rounded p-1 text-muted hover:text-ink">
                <X className="size-4" />
              </button>
            </div>
          )}

          <Tabs tabs={TABS} active={tab} onChange={setTab} label="Loan detail sections" />
          <div role="tabpanel" className="mt-6">
            {tab === 'overview' && (
              <div className="space-y-6">
                <div className="grid gap-6 xl:grid-cols-2">
                  <LoanInformation loan={loan} />
                  {financials ? (
                    <ContributionSummary financials={financials} />
                  ) : (
                    <section role="status" className="card p-5 sm:p-6" aria-labelledby="no-data-title">
                      <h2 id="no-data-title" className="text-base font-semibold text-ink">
                        Financial Contribution Summary
                      </h2>
                      <p className="mt-0.5 text-sm text-muted">{periodRangeLabel(period)}</p>
                      <ul className="mt-4 space-y-1.5 text-sm text-ink">
                        {missingMessages(missing ?? []).map((m) => (
                          <li key={m}>{m}</li>
                        ))}
                      </ul>
                      <p className="mt-3 text-xs text-muted">LoanLens does not assume zero for missing information. Record it for this period, or choose another reporting period.</p>
                      {!env.useMock && (
                        <div className="mt-4 flex flex-wrap gap-3">
                          <button type="button" onClick={() => setRecordModal({})} className="btn-secondary py-2">
                            <Plus className="size-4" aria-hidden="true" />
                            Add financial record
                          </button>
                          <button type="button" onClick={() => setRiskModal({})} className="btn-secondary py-2">
                            <Plus className="size-4" aria-hidden="true" />
                            Add risk assessment
                          </button>
                        </div>
                      )}
                    </section>
                  )}
                </div>
                {financials && <FinancialCards f={financials} />}
              </div>
            )}
            {tab === 'financial' && (
              <div className="space-y-6">
                {!env.useMock && <FinancialRecordsPanel loanId={loan.id} selectedPeriod={period.id} onAdd={() => setRecordModal({})} onEdit={(r) => setRecordModal({ existing: r })} />}
                {financials ? (
                  <FinancialBreakdownTab loanId={loan.id} f={financials} />
                ) : (
                  <div role="status" className="rounded-xl border border-line bg-white p-5 text-sm text-muted">
                    {missingMessages(missing ?? []).join(' ')} Choose a period that has records, or add them above.
                  </div>
                )}
              </div>
            )}
            {tab === 'credit' && <CreditRiskTab loanId={loan.id} f={financials ?? null}onAdd={() => setRiskModal({})} onEdit={(r) => setRiskModal({ existing: r })} />}
          </div>

          {env.useMock ? (
            <LoanFormModal open={editOpen} mode="edit" loanId={loan.id} onClose={() => setEditOpen(false)} onSaved={() => setNotice('Loan updated. All figures have been refreshed.')} />
          ) : (
            <>
              {editOpen && <BackendLoanFormModal open mode="edit" existing={loan} onClose={() => setEditOpen(false)} onSaved={() => setNotice('Loan updated. All figures have been refreshed.')} />}
              {recordModal && (
                <FinancialRecordFormModal
                  open
                  loanId={loan.id}
                  defaultPeriod={period.id}
                  existing={recordModal.existing}
                  onClose={() => setRecordModal(null)}
                  onSaved={(p) => {
                    setRecordModal(null)
                    saved(`Financial record for ${p} saved.`)
                  }}
                />
              )}
              {riskModal && (
                <RiskAssessmentFormModal
                  open
                  loanId={loan.id}
                  defaultPeriod={period.id}
                  defaultEad={loan.outstandingBalance}
                  existing={riskModal.existing}
                  onClose={() => setRiskModal(null)}
                  onSaved={(p) => {
                    setRiskModal(null)
                    saved(`Risk assessment for ${p} saved. The expected credit loss was calculated by the backend.`)
                  }}
                />
              )}
            </>
          )}
        </>
      )}
    </>
  )
}
