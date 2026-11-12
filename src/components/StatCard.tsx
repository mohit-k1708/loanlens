import type { LucideIcon } from 'lucide-react'
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react'
import { Link } from 'react-router-dom'

type Accent = 'blue' | 'green' | 'red' | 'amber' | 'slate'

const ACCENTS: Record<Accent, string> = {
  blue: 'bg-primary/10 text-primary',
  green: 'bg-positive/10 text-positive',
  red: 'bg-negative/10 text-negative',
  amber: 'bg-warning/10 text-warning',
  slate: 'bg-slate-100 text-muted',
}

export interface Trend {
  label: string
  direction: 'up' | 'down' | 'flat'
  /** Whether this direction is good news (green), bad news (red) or neutral. */
  tone: 'good' | 'bad' | 'neutral'
}

interface StatCardProps {
  icon: LucideIcon
  label: string
  value: string
  description: string
  accent?: Accent
  trend?: Trend
  /** Optional navigation target; makes the whole card a link. */
  to?: string
  /** Colour the value (e.g. green/red for contribution). */
  valueClassName?: string
}

export function StatCard({ icon: Icon, label, value, description, accent = 'blue', trend, to, valueClassName }: StatCardProps) {
  const TrendIcon = trend?.direction === 'up' ? ArrowUpRight : trend?.direction === 'down' ? ArrowDownRight : Minus
  const trendCls =
    trend?.tone === 'good'
      ? 'bg-positive/10 text-positive'
      : trend?.tone === 'bad'
        ? 'bg-negative/10 text-negative'
        : 'bg-slate-100 text-muted'

  const body = (
    <>
      <div className="flex items-start justify-between gap-3">
        <span className={`flex size-11 items-center justify-center rounded-xl ${ACCENTS[accent]}`}>
          <Icon className="size-5" aria-hidden="true" />
        </span>
        {trend && (
          <span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold ${trendCls}`}>
            <TrendIcon className="size-3.5" aria-hidden="true" />
            {trend.label}
          </span>
        )}
      </div>
      <p className="mt-5 text-sm font-medium text-muted xl:min-h-10">{label}</p>
      <p className={`tabular mt-1 text-[1.7rem] font-bold leading-tight tracking-tight ${valueClassName ?? 'text-ink'}`}>{value}</p>
      <p className="mt-2 text-xs leading-relaxed text-muted">{description}</p>
    </>
  )

  const cls = 'card block p-5'
  return to ? (
    <Link to={to} className={`${cls} transition hover:border-primary/40 hover:shadow-md`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  )
}
