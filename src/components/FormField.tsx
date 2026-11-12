import { CircleAlert } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { isApiError, userMessage } from '../services/apiError'
import { parseRupeesToPaise } from '../utils/formatCurrency'

interface FieldProps {
  label: string
  error?: string
  hint?: string
  required?: boolean
  children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string; className: string }) => ReactNode
}

/** A labelled form control with an inline error / hint, wired for screen readers. */
export function FormField({ label, error, hint, required = true, children }: FieldProps) {
  const id = useId()
  const describedBy = error ? `${id}-err` : hint ? `${id}-hint` : undefined
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink">
        {label} {required ? <span className="text-negative" aria-hidden="true">*</span> : <span className="text-xs font-normal text-muted">(optional)</span>}
      </label>
      {children({ id, 'aria-invalid': Boolean(error), 'aria-describedby': describedBy, className: `input ${error ? 'input-error' : ''}` })}
      {error ? (
        <p id={`${id}-err`} role="alert" className="mt-1 text-xs text-negative">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1 text-xs text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export function FormErrorBanner({ message }: { message: string }) {
  return (
    <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-negative/30 bg-negative/5 px-3.5 py-3 text-sm text-negative">
      <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      {message}
    </div>
  )
}

/** Parse "10", "8.75" etc. Returns null if not a number with up to 2 decimals in [min, max]. */
export function parsePercentInput(value: string, min: number, max: number): number | null {
  if (!/^\d+(\.\d{1,2})?$/.test(value.trim())) return null
  const n = Number(value)
  return n >= min && n <= max ? n : null
}

export { parseRupeesToPaise }

/**
 * Turn a failed save into field errors + a banner message.
 * `fieldMap` translates backend field names to form keys where they differ (e.g. probabilityOfDefault -> pd).
 */
export function describeSaveError<K extends string>(
  error: unknown,
  formKeys: readonly K[],
  fieldMap: Partial<Record<string, K>> = {},
  subject = 'The record',
): { fieldErrors: Partial<Record<K, string>>; banner: string } {
  const fieldErrors: Partial<Record<K, string>> = {}
  if (isApiError(error) && error.fieldErrors) {
    for (const [serverField, message] of Object.entries(error.fieldErrors)) {
      const key = fieldMap[serverField] ?? (formKeys.includes(serverField as K) ? (serverField as K) : undefined)
      if (key) fieldErrors[key] = message
    }
  }
  return { fieldErrors, banner: `${subject} was not saved. ${userMessage(error)}` }
}
