import { ArrowRight, Eye, Pencil } from 'lucide-react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import type { LoanSummary } from '../types/loan'
import { formatINR, formatPercent, formatSignedINR } from '../utils/formatCurrency'
import { ContributionBadge, NoDataBadge, RiskBadge } from './StatusBadge'

export type LoanColumn =
  | 'loanId'
  | 'customer'
  | 'loanType'
  | 'principal'
  | 'outstanding'
  | 'rate'
  | 'risk'
  | 'contribution'
  | 'action'

/** The fields the table needs. The dashboard's attention list has no principal or interest rate, so those are optional. */
export type LoanTableRow = Pick<
  LoanSummary,
  'id' | 'customerId' | 'customerName' | 'loanType' | 'outstandingBalance' | 'riskRating' | 'estimatedContribution' | 'contributionStatus'
> &
  Partial<Pick<LoanSummary, 'originalAmount' | 'interestRate' | 'missingInputs'>>

interface ColumnDef {
  header: string
  align?: 'right'
  render: (loan: LoanTableRow, ctx: { actionLabel: string; onEdit?: (loan: LoanTableRow) => void }) => ReactNode
}

const COLUMNS: Record<LoanColumn, ColumnDef> = {
  loanId: {
    header: 'Loan ID',
    render: (loan) => <span className="font-semibold text-primary">{loan.id}</span>,
  },
  customer: {
    header: 'Customer Name',
    render: (loan) => (
      <div>
        <p className="font-medium text-ink">{loan.customerName}</p>
        <p className="text-xs text-muted">{loan.customerId}</p>
      </div>
    ),
  },
  loanType: { header: 'Loan Type', render: (loan) => loan.loanType },
  principal: {
    header: 'Principal Amount',
    align: 'right',
    render: (loan) => <span className="tabular">{loan.originalAmount === undefined ? '—' : formatINR(loan.originalAmount)}</span>,
  },
  outstanding: {
    header: 'Outstanding Balance',
    align: 'right',
    render: (loan) => <span className="tabular">{formatINR(loan.outstandingBalance)}</span>,
  },
  rate: {
    header: 'Interest Rate',
    align: 'right',
    render: (loan) => <span className="tabular">{loan.interestRate === undefined ? '—' : formatPercent(loan.interestRate)}</span>,
  },
  risk: { header: 'Risk Rating', render: (loan) => <RiskBadge rating={loan.riskRating} /> },
  contribution: {
    header: 'Estimated Contribution',
    align: 'right',
    render: (loan) => {
      // A loan without data for the period shows "No data", never a made-up zero.
      if (loan.estimatedContribution === null || loan.contributionStatus === null) {
        return (
          <div className="flex flex-col items-end gap-1">
            <span className="text-muted">—</span>
            <NoDataBadge missing={loan.missingInputs} />
          </div>
        )
      }
      const color =
        loan.contributionStatus === 'positive' ? 'text-positive' : loan.contributionStatus === 'negative' ? 'text-negative' : 'text-muted'
      return (
        <div className="flex flex-col items-end gap-1">
          <span className={`tabular font-semibold ${color}`}>{formatSignedINR(loan.estimatedContribution)}</span>
          <ContributionBadge status={loan.contributionStatus} />
        </div>
      )
    },
  },
  action: {
    header: 'Action',
    align: 'right',
    render: (loan, { actionLabel, onEdit }) => (
      <div className="flex items-center justify-end gap-2">
        {onEdit && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation()
              onEdit(loan)
            }}
            aria-label={`Edit loan ${loan.id} for ${loan.customerName}`}
            title="Edit loan"
            className="rounded-lg border border-line bg-white p-1.5 text-muted shadow-sm hover:border-primary/40 hover:text-primary"
          >
            <Pencil className="size-3.5" aria-hidden="true" />
          </button>
        )}
        <span className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white px-3 py-1.5 text-xs font-semibold text-primary shadow-sm group-hover:border-primary/40 group-hover:bg-primary/5">
          {actionLabel === 'View Details' ? <Eye className="size-3.5" aria-hidden="true" /> : null}
          {actionLabel}
          {actionLabel !== 'View Details' ? <ArrowRight className="size-3.5" aria-hidden="true" /> : null}
        </span>
      </div>
    ),
  },
}

interface LoanTableProps {
  loans: LoanTableRow[]
  columns: LoanColumn[]
  /** Label for the action column button. */
  actionLabel?: string
  caption: string
  /** When provided, an Edit button appears in the action column. */
  onEdit?: (loan: LoanTableRow) => void
}

/** Table of loans. Every row is clickable and keyboard accessible; it navigates to the loan details. */
export function LoanTable({ loans, columns, actionLabel = 'View Details', caption, onEdit }: LoanTableProps) {
  const navigate = useNavigate()
  const open = (id: string) => navigate(`/loans/${id}`)

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[56rem] border-collapse text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line bg-slate-50/70 text-xs uppercase tracking-wide text-muted">
            {columns.map((c) => (
              <th key={c} scope="col" className={`px-4 py-3 font-semibold ${COLUMNS[c].align === 'right' ? 'text-right' : ''}`}>
                {COLUMNS[c].header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {loans.map((loan) => (
            <tr
              key={loan.id}
              onClick={() => open(loan.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && e.target === e.currentTarget) open(loan.id)
              }}
              tabIndex={0}
              aria-label={`${loan.customerName}, loan ${loan.id}. Press Enter to view details`}
              className="group cursor-pointer transition hover:bg-slate-50 focus-visible:bg-slate-50"
            >
              {columns.map((c) => (
                <td key={c} className={`px-4 py-3.5 align-middle text-ink ${COLUMNS[c].align === 'right' ? 'text-right' : ''}`}>
                  {COLUMNS[c].render(loan, { actionLabel, onEdit })}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
