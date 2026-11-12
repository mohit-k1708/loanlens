import { useId, type KeyboardEvent } from 'react'

export interface TabItem<T extends string> {
  id: T
  label: string
}

interface TabsProps<T extends string> {
  tabs: readonly TabItem<T>[]
  active: T
  onChange: (id: T) => void
  label: string
}

/** Accessible tablist (arrow-key navigation). Render panels with `tabPanelProps`. */
export function Tabs<T extends string>({ tabs, active, onChange, label }: TabsProps<T>) {
  const baseId = useId()
  const onKeyDown = (event: KeyboardEvent, index: number) => {
    let next = index
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length
    else return
    event.preventDefault()
    onChange(tabs[next].id)
    document.getElementById(`${baseId}-${tabs[next].id}`)?.focus()
  }

  return (
    <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line">
      {tabs.map((tab, i) => {
        const selected = tab.id === active
        return (
          <button
            key={tab.id}
            id={`${baseId}-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.id)}
            onKeyDown={(e) => onKeyDown(e, i)}
            className={`-mb-px whitespace-nowrap border-b-2 px-4 py-3 text-sm font-semibold transition ${
              selected ? 'border-primary text-primary' : 'border-transparent text-muted hover:text-ink'
            }`}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}
