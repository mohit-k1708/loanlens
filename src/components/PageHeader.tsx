import type { ReactNode } from 'react'
import { env } from '../config/env'

interface PageHeaderProps {
  title: string
  subtitle?: string
  actions?: ReactNode
}

export function PageHeader({ title, subtitle, actions }: PageHeaderProps) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-end gap-3">{actions}</div>}
    </div>
  )
}

/**
 * Shown only in mock mode. In live mode the data comes from the backend and is never labelled
 * (or mixed with) demonstration data.
 */
export function DemoDataNotice({ className = '' }: { className?: string }) {
  if (!env.useMock) return null
  return (
    <p
      className={`inline-flex items-center gap-2 rounded-full border border-warning/30 bg-warning/10 px-3 py-1 text-xs font-medium text-warning ${className}`}
    >
      <span className="size-1.5 rounded-full bg-warning" aria-hidden="true" />
      Demonstration data — all customers and figures are fictitious
    </p>
  )
}
