import { Info } from 'lucide-react'
import { useId } from 'react'

/** Accessible hover/focus tooltip. Content is also reachable by screen readers via aria-describedby. */
export function InfoTooltip({ text, label = 'More information' }: { text: string; label?: string }) {
  const id = useId()
  return (
    <span className="group relative inline-flex">
      <button type="button" aria-label={label} aria-describedby={id} className="rounded-full p-0.5 text-muted hover:text-primary">
        <Info className="size-4" aria-hidden="true" />
      </button>
      <span
        id={id}
        role="tooltip"
        className="pointer-events-none fixed inset-x-4 bottom-4 z-50 hidden rounded-lg bg-navy px-3 py-2.5 text-xs font-normal leading-relaxed text-white shadow-lg group-focus-within:block group-hover:block sm:absolute sm:inset-x-auto sm:bottom-auto sm:left-1/2 sm:top-full sm:z-30 sm:mt-2 sm:w-72 sm:-translate-x-1/2"
      >
        {text}
      </span>
    </span>
  )
}

export const ECL_DISCLAIMER =
  'Expected Credit Loss is an estimate of potential future credit loss. It does not mean that the borrower has actually defaulted or that the bank has already lost this amount.'
