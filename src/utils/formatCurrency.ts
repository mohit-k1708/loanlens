import type { Paise } from '../types/loan'

// Reusable Indian-locale currency formatters (lakh/crore digit grouping).
const WHOLE_RUPEES = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
})
const WITH_PAISE = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/** Format integer paise as INR, e.g. 50000000 -> "₹5,00,000". Shows paise only when present. */
export function formatINR(paise: Paise): string {
  if (paise === 0) return WHOLE_RUPEES.format(0)
  const formatter = paise % 100 === 0 ? WHOLE_RUPEES : WITH_PAISE
  return formatter.format(paise / 100)
}

/** Same as formatINR but always prefixes positive values with "+" (negatives already carry "-"). */
export function formatSignedINR(paise: Paise): string {
  return paise > 0 ? `+${formatINR(paise)}` : formatINR(paise)
}

/** Compact Indian notation for tight spaces (KPI cards, chart axes): ₹2.46 L, ₹3.1 Cr. */
export function formatCompactINR(paise: Paise): string {
  const rupees = paise / 100
  const abs = Math.abs(rupees)
  const sign = rupees < 0 ? '-' : ''
  const trim = (n: number) => String(Number(n.toFixed(2)))
  if (abs >= 1e7) return `${sign}₹${trim(abs / 1e7)} Cr`
  if (abs >= 1e5) return `${sign}₹${trim(abs / 1e5)} L`
  return formatINR(paise)
}

export function formatPercent(value: number, maxDigits = 2): string {
  return `${Number(value.toFixed(maxDigits))}%`
}

const DATE_FORMAT = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
})

export function formatDate(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00`)
  return Number.isNaN(date.getTime()) ? isoDate : DATE_FORMAT.format(date)
}

export function formatDuration(months: number): string {
  const years = Math.floor(months / 12)
  const rest = months % 12
  const parts: string[] = []
  if (years) parts.push(`${years} yr${years > 1 ? 's' : ''}`)
  if (rest) parts.push(`${rest} mo`)
  return `${months} months${years ? ` (${parts.join(' ')})` : ''}`
}

/** Parse a rupee string typed by a user ("5,00,000" or "1250.50") into integer paise, or null if invalid. */
export function parseRupeesToPaise(input: string): Paise | null {
  const cleaned = input.replace(/[,\s₹]/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null
  return Math.round(Number(cleaned) * 100)
}
