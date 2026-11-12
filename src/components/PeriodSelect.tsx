import { CalendarDays } from 'lucide-react'
import { useId } from 'react'
import { useAppData } from '../context/AppDataContext'
import type { PeriodId } from '../types/loan'
import { isYearPeriod, periodRangeLabel } from '../utils/profitability'

/** Reporting-period selector bound to the shared period state. */
export function PeriodSelect({ className = '' }: { className?: string }) {
  const id = useId()
  const { period, setPeriodId, availablePeriods } = useAppData()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-muted">
        Reporting period
      </label>
      <div className="relative">
        <CalendarDays className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
        <select id={id} value={period.id} onChange={(e) => setPeriodId(e.target.value as PeriodId)} className="input py-2 pl-9">
          {availablePeriods.map((p) => (
            <option key={p.id} value={p.id}>
              {isYearPeriod(p.id) ? `Reporting year ${p.id}` : `${p.label} (${periodRangeLabel(p)})`}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}
