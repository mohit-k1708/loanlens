import { Calculator, FileText } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { AiEvidence, AiEvidenceTone } from '../types/ai'
import { formatSignedINR } from '../utils/formatCurrency'

const TONE_CLASS: Record<AiEvidenceTone, string> = {
  income: 'text-positive',
  expense: 'text-negative',
  estimate: 'text-warning',
  total: 'text-ink',
  neutral: 'text-ink',
}

/** The figures behind an AI explanation. Supplied by the backend, never composed by the model. */
export function AiEvidenceCard({ evidence }: { evidence: AiEvidence }) {
  return (
    <section className="rounded-xl border border-line bg-slate-50/70" aria-label={`Supporting figures: ${evidence.title}`}>
      <header className="flex flex-wrap items-baseline justify-between gap-x-3 border-b border-line px-4 py-2.5">
        <h4 className="text-sm font-semibold text-ink">{evidence.title}</h4>
        <p className="text-xs text-muted">{evidence.periodLabel}</p>
      </header>
      <dl className="divide-y divide-line px-4 text-sm">
        {evidence.items.map((item, i) => {
          const strong = item.tone === 'total'
          const value = item.amount !== undefined ? formatSignedINR(item.amount) : (item.text ?? '')
          // A signed total takes its colour from the sign (green/red), other rows from their tone.
          const color = strong && item.amount !== undefined ? (item.amount > 0 ? 'text-positive' : item.amount < 0 ? 'text-negative' : 'text-muted') : TONE_CLASS[item.tone]
          return (
            <div key={`${item.label}-${i}`} className={`flex items-center justify-between gap-4 py-2 ${strong ? 'font-semibold' : ''}`}>
              <dt className="min-w-0 text-muted">
                {item.loanId ? (
                  <Link to={`/loans/${item.loanId}`} className="text-primary hover:underline">
                    {item.label}
                  </Link>
                ) : (
                  item.label
                )}
              </dt>
              <dd className={`tabular whitespace-nowrap ${color}`}>{value}</dd>
            </div>
          )
        })}
      </dl>
      {evidence.loanId && (
        <footer className="flex flex-wrap gap-x-4 gap-y-1 border-t border-line px-4 py-2.5 text-xs font-semibold">
          <Link to={`/loans/${evidence.loanId}`} className="inline-flex items-center gap-1.5 text-primary hover:underline">
            <FileText className="size-3.5" aria-hidden="true" />
            View loan details
          </Link>
          <Link to={`/loans/${evidence.loanId}/profitability`} className="inline-flex items-center gap-1.5 text-primary hover:underline">
            <Calculator className="size-3.5" aria-hidden="true" />
            Profitability analysis
          </Link>
        </footer>
      )}
    </section>
  )
}
