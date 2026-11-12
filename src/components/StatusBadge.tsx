import { CircleCheck, CircleMinus, CircleX, TriangleAlert } from 'lucide-react'
import type { ContributionStatus, LoanStatus, RiskRating } from '../types/loan'

const base = 'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold'

const CONTRIBUTION_STYLES: Record<ContributionStatus, { cls: string; short: string; long: string; Icon: typeof CircleCheck }> = {
  positive: {
    cls: 'bg-positive/10 text-positive ring-1 ring-inset ring-positive/20',
    short: 'Positive',
    long: 'Positive Estimated Contribution',
    Icon: CircleCheck,
  },
  negative: {
    cls: 'bg-negative/10 text-negative ring-1 ring-inset ring-negative/20',
    short: 'Negative',
    long: 'Negative Estimated Contribution',
    Icon: CircleX,
  },
  'break-even': {
    cls: 'bg-slate-100 text-muted ring-1 ring-inset ring-slate-200',
    short: 'Break-even',
    long: 'Break-even Estimated Contribution',
    Icon: CircleMinus,
  },
}

export function ContributionBadge({ status, long = false }: { status: ContributionStatus; long?: boolean }) {
  const s = CONTRIBUTION_STYLES[status]
  return (
    <span className={`${base} ${s.cls}`}>
      <s.Icon className="size-3.5" aria-hidden="true" />
      {long ? s.long : s.short}
    </span>
  )
}

const RISK_STYLES: Record<RiskRating, string> = {
  Low: 'bg-primary/10 text-primary ring-1 ring-inset ring-primary/20',
  Medium: 'bg-warning/10 text-warning ring-1 ring-inset ring-warning/30',
  High: 'bg-warning text-white',
}

export function RiskBadge({ rating }: { rating: RiskRating | null }) {
  if (rating === null) return <span className={`${base} bg-slate-100 text-muted ring-1 ring-inset ring-slate-200`}>Not assessed</span>
  return (
    <span className={`${base} ${RISK_STYLES[rating]}`}>
      {rating === 'High' && <TriangleAlert className="size-3.5" aria-hidden="true" />}
      {rating} Risk
    </span>
  )
}

export function LoanStatusBadge({ status }: { status: LoanStatus }) {
  const cls =
    status === 'Active'
      ? 'bg-slate-100 text-ink ring-1 ring-inset ring-slate-200'
      : status === 'Under Watch'
        ? 'bg-warning/10 text-warning ring-1 ring-inset ring-warning/30'
        : status === 'Defaulted'
          ? 'bg-negative/10 text-negative ring-1 ring-inset ring-negative/20'
          : 'bg-slate-100 text-muted ring-1 ring-inset ring-slate-200'
  return <span className={`${base} ${cls}`}>{status}</span>
}

/** Shown instead of a contribution when the period has no financial record and/or risk assessment. */
export function NoDataBadge({ missing }: { missing?: string[] }) {
  const detail = missing?.length
    ? `Missing: ${missing.map((m) => (m === 'FINANCIAL_RECORD' ? 'financial record' : m === 'RISK_ASSESSMENT' ? 'risk assessment' : m === 'COST_ALLOCATION_MISMATCH' ? 'operational cost breakdown does not add up to the operational cost' : m)).join(' and ')}`
    : 'No financial data for this period'
  return (
    <span title={detail} className={`${base} bg-slate-100 text-muted ring-1 ring-inset ring-slate-200`}>
      No data
    </span>
  )
}