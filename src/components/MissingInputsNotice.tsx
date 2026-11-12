import { FilePlus2, ShieldQuestion } from 'lucide-react'
import { Link } from 'react-router-dom'

interface MissingInputsNoticeProps {
  /** What the backend reported as missing (descriptive text, or FINANCIAL_RECORD / RISK_ASSESSMENT). */
  missing: string[]
  loanId: string
  /** Where the add-records buttons point. Defaults to the loan page. */
  compact?: boolean
}

export const NO_FINANCIAL_RECORD = 'No financial records are available for this reporting period.'
export const NO_RISK_ASSESSMENT = 'No risk assessment is available for this reporting period.'

/** Plain-language list of what is missing for a calculation. Missing data is explained, never replaced by zero. */
export function missingMessages(missing: string[]): string[] {
  const lower = missing.map((m) => m.toLowerCase())
  const out: string[] = []
  if (lower.some((m) => m.includes('financial'))) out.push(NO_FINANCIAL_RECORD)
  if (lower.some((m) => m.includes('risk'))) out.push(NO_RISK_ASSESSMENT)
  if (lower.some((m) => m.includes('cost_allocation') || m.includes('allocations total'))) {
    out.push('The recorded operational cost breakdown does not add up to the operational cost, so the figures cannot be shown yet.')
  }
  return out.length > 0 ? out : ['Some information needed for this calculation has not been recorded yet.']
}

export function MissingInputsNotice({ missing, loanId, compact = false }: MissingInputsNoticeProps) {
  const messages = missingMessages(missing)
  return (
    <div role="status" className="card overflow-hidden">
      <div className="flex flex-col items-center px-6 py-10 text-center">
        <span className="flex size-14 items-center justify-center rounded-full bg-warning/10 text-warning">
          <ShieldQuestion className="size-6" aria-hidden="true" />
        </span>
        <h2 className="mt-4 text-base font-semibold text-ink">Profitability cannot be calculated for this period yet</h2>
        <ul className="mt-2 space-y-1 text-sm text-muted">
          {messages.map((m) => (
            <li key={m}>{m}</li>
          ))}
        </ul>
        <p className="mt-3 max-w-md text-xs text-muted">
          LoanLens does not assume zero for missing information. Add the records for this reporting period, or choose a different period.
        </p>
        {!compact && (
          <Link to={`/loans/${loanId}`} className="btn-primary mt-5">
            <FilePlus2 className="size-4" aria-hidden="true" />
            Go to loan details to add records
          </Link>
        )}
      </div>
    </div>
  )
}
