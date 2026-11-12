interface TooltipEntry {
  name?: string | number
  value?: string | number | ReadonlyArray<string | number>
  color?: string
  /** Source datum; a `label` field, when present, is used as the full heading. */
  payload?: { fill?: string; label?: string }
}

interface ChartTooltipProps {
  active?: boolean
  label?: string | number
  payload?: ReadonlyArray<TooltipEntry>
  /** How to render each numeric value, e.g. formatINR or a plain count. */
  formatValue?: (value: number) => string
  /** Optional heading override (defaults to the axis label). */
  title?: string
}

/** Shared tooltip card for all charts. Reads Recharts' injected props. */
export function ChartTooltip({ active, label, payload, formatValue = String, title }: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null
  const heading = title ?? payload[0]?.payload?.label ?? (label !== undefined ? String(label) : undefined)
  return (
    <div className="rounded-lg border border-line bg-white px-3 py-2.5 text-xs shadow-lg">
      {heading && <p className="mb-1.5 font-semibold text-ink">{heading}</p>}
      <ul className="space-y-1">
        {payload.map((entry, i) => {
          const raw = Array.isArray(entry.value) ? entry.value[0] : entry.value
          const value = Number(raw)
          if (!Number.isFinite(value)) return null
          return (
            <li key={`${entry.name}-${i}`} className="flex items-center justify-between gap-6">
              <span className="flex items-center gap-2 text-muted">
                <span className="size-2 rounded-full" style={{ backgroundColor: entry.color ?? entry.payload?.fill }} aria-hidden="true" />
                {entry.name}
              </span>
              <span className="tabular font-semibold text-ink">{formatValue(value)}</span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
