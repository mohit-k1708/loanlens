import { FilterX } from 'lucide-react'
import { useId, type ReactNode } from 'react'

export interface FilterOption {
  value: string
  label: string
}

export interface FilterDefinition {
  id: string
  label: string
  value: string
  options: FilterOption[]
  onChange: (value: string) => void
}

function FilterSelect({ filter }: { filter: FilterDefinition }) {
  const id = useId()
  return (
    <div className="min-w-40 flex-1 sm:flex-none">
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-muted">
        {filter.label}
      </label>
      <select id={id} value={filter.value} onChange={(e) => filter.onChange(e.target.value)} className="input py-2">
        {filter.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  )
}

interface FilterBarProps {
  filters: FilterDefinition[]
  /** Extra controls rendered after the selects (e.g. the period selector). */
  trailing?: ReactNode
  hasActiveFilters: boolean
  onClear: () => void
}

export function FilterBar({ filters, trailing, hasActiveFilters, onClear }: FilterBarProps) {
  return (
    <div className="flex flex-wrap items-end gap-3">
      {filters.map((f) => (
        <FilterSelect key={f.id} filter={f} />
      ))}
      {trailing}
      <button type="button" onClick={onClear} disabled={!hasActiveFilters} className="btn-ghost px-3 py-2 disabled:opacity-40">
        <FilterX className="size-4" aria-hidden="true" />
        Clear filters
      </button>
    </div>
  )
}
