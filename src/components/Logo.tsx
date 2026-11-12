import { ScanSearch } from 'lucide-react'

export function Logo({ tone = 'light', className = '' }: { tone?: 'light' | 'dark'; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <span className="flex size-9 items-center justify-center rounded-xl bg-primary text-white shadow-sm">
        <ScanSearch className="size-5" aria-hidden="true" />
      </span>
      <span className={`text-xl font-bold tracking-tight ${tone === 'light' ? 'text-white' : 'text-ink'}`}>
        Loan<span className="text-blue-400">Lens</span>
      </span>
    </span>
  )
}
