import { useId, useMemo, useState, type FormEvent } from 'react'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { createBackendLoan, listLoans, updateBackendLoan } from '../services/loanService'
import { listCustomers } from '../services/backendRecordService'
import { keys } from '../services/keys'
import { EMPLOYMENT_TYPES, LOAN_TYPES, type BackendLoanFormValues, type EmploymentType, type LoanDetail, type LoanStatus, type LoanType } from '../types/loan'
import { describeSaveError, FormErrorBanner, FormField, parsePercentInput, parseRupeesToPaise } from './FormField'
import { ErrorState } from './ErrorState'
import { Modal } from './Modal'

/**
 * Add / edit a loan against the real backend.
 *  - Add: pick an existing customer or enter a new one, then the loan details (the backend needs a customer).
 *  - Edit: the loan's own fields only. Financial and risk inputs are separate records with their own forms.
 * Nothing is calculated here; the backend validates, stores and calculates.
 */

type Key =
  | 'loanCode'
  | 'customerChoice'
  | 'customerCode'
  | 'fullName'
  | 'email'
  | 'employmentType'
  | 'annualIncome'
  | 'loanType'
  | 'originalPrincipal'
  | 'outstandingBalance'
  | 'interestRate'
  | 'tenureMonths'
  | 'startDate'
  | 'loanStatus'

const KEYS: readonly Key[] = [
  'loanCode', 'customerChoice', 'customerCode', 'fullName', 'email', 'employmentType', 'annualIncome',
  'loanType', 'originalPrincipal', 'outstandingBalance', 'interestRate', 'tenureMonths', 'startDate', 'loanStatus',
]
/** Backend field names that differ from the form keys. */
const FIELD_MAP: Partial<Record<string, Key>> = { customerId: 'customerChoice' }

type Form = Record<Key, string>
type Errors = Partial<Record<Key, string>>

const STATUSES: LoanStatus[] = ['Active', 'Under Watch', 'Closed', 'Defaulted']
const NEW = 'new'
const today = () => new Date().toISOString().slice(0, 10)
const rupees = (paise: number) => String(paise / 100)

function initial(existing?: LoanDetail): Form {
  if (existing) {
    return {
      loanCode: existing.id,
      customerChoice: '',
      customerCode: '',
      fullName: '',
      email: '',
      employmentType: 'SALARIED',
      annualIncome: '',
      loanType: existing.loanType,
      originalPrincipal: rupees(existing.originalAmount),
      outstandingBalance: rupees(existing.outstandingBalance),
      interestRate: String(existing.interestRate),
      tenureMonths: String(existing.durationMonths),
      startDate: existing.startDate,
      loanStatus: existing.status,
    }
  }
  return {
    loanCode: '', customerChoice: '', customerCode: '', fullName: '', email: '', employmentType: 'SALARIED', annualIncome: '',
    loanType: 'Personal Loan', originalPrincipal: '', outstandingBalance: '', interestRate: '', tenureMonths: '', startDate: '', loanStatus: 'Active',
  }
}

function validate(f: Form, mode: 'add' | 'edit'): { errors: Errors; values?: BackendLoanFormValues } {
  const errors: Errors = {}
  if (mode === 'add') {
    if (!/^[A-Z][A-Z0-9-]{2,19}$/.test(f.loanCode.trim())) errors.loanCode = 'Start with a letter; 3-20 characters of uppercase letters, digits or hyphens, e.g. L1014.'
    if (!f.customerChoice) errors.customerChoice = 'Choose a customer or add a new one.'
    if (f.customerChoice === NEW) {
      if (!/^[A-Z0-9][A-Z0-9-]{2,19}$/.test(f.customerCode.trim())) errors.customerCode = '3-20 characters: uppercase letters, digits or hyphens, e.g. C2014.'
      if (f.fullName.trim().length < 2) errors.fullName = 'Enter the customer name (at least 2 characters).'
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim())) errors.email = 'Enter a valid email address.'
      if (parseRupeesToPaise(f.annualIncome) === null) errors.annualIncome = 'Enter the annual income in rupees (0 if unknown).'
    }
  }
  const principal = parseRupeesToPaise(f.originalPrincipal)
  if (principal === null || principal <= 0) errors.originalPrincipal = 'Enter a principal amount greater than zero.'
  const outstanding = parseRupeesToPaise(f.outstandingBalance)
  if (outstanding === null) errors.outstandingBalance = 'Enter the outstanding balance in rupees (0 if fully repaid).'
  else if (principal !== null && outstanding > principal) errors.outstandingBalance = 'Cannot exceed the principal amount.'
  else if (f.loanStatus === 'Closed' && outstanding !== 0) errors.outstandingBalance = 'A closed loan must have a zero balance.'
  const rate = parsePercentInput(f.interestRate, 0.01, 60)
  if (rate === null) errors.interestRate = 'Enter a rate as a percentage between 0.01 and 60, e.g. 10.5.'
  const months = Number(f.tenureMonths)
  if (!Number.isInteger(months) || months < 1 || months > 480) errors.tenureMonths = 'Enter whole months between 1 and 480.'
  if (!f.startDate) errors.startDate = 'Start date is required.'
  else if (f.startDate > today()) errors.startDate = 'Start date cannot be in the future.'

  if (Object.keys(errors).length > 0) return { errors }
  const customer: BackendLoanFormValues['customer'] =
    f.customerChoice === NEW
      ? { kind: 'new', customerCode: f.customerCode.trim(), fullName: f.fullName.trim(), email: f.email.trim(), employmentType: f.employmentType as EmploymentType, annualIncome: parseRupeesToPaise(f.annualIncome)! }
      : { kind: 'existing', id: Number(f.customerChoice) }
  return {
    errors,
    values: {
      loanCode: f.loanCode.trim(),
      customer,
      loanType: f.loanType as LoanType,
      originalAmount: principal!,
      outstandingBalance: outstanding!,
      interestRate: rate!,
      durationMonths: months,
      startDate: f.startDate,
      loanStatus: f.loanStatus as LoanStatus,
    },
  }
}

interface BodyProps {
  formId: string
  mode: 'add' | 'edit'
  existing?: LoanDetail
  onSubmit: (values: BackendLoanFormValues) => Promise<void>
}

function Body({ formId, mode, existing, onSubmit }: BodyProps) {
  const { period } = useAppData()
  const [form, setForm] = useState<Form>(() => initial(existing))
  const [errors, setErrors] = useState<Errors>({})
  const [banner, setBanner] = useState('')

  // Customers for the picker (add only); loans only to suggest the next free loan code.
  const customers = useApi(mode === 'add' ? keys.customers() : null, () => listCustomers())
  const loans = useApi(mode === 'add' ? keys.loans(period.id) : null, () => listLoans(period.id))
  const suggestion = useMemo(() => {
    const max = (loans.data ?? []).reduce((m, l) => Math.max(m, Number(l.id.replace(/\D/g, '')) || 0), 1000)
    return `L${max + 1}`
  }, [loans.data])

  const set = (key: Key, value: string) => {
    setForm((f) => ({ ...f, [key]: value }))
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBanner('')
    const result = validate(form, mode)
    if (!result.values) {
      setErrors(result.errors)
      requestAnimationFrame(() => document.querySelector<HTMLElement>(`#${CSS.escape(formId)} [aria-invalid="true"]`)?.focus())
      return
    }
    try {
      await onSubmit(result.values)
    } catch (error) {
      const d = describeSaveError(error, KEYS, FIELD_MAP, 'The loan')
      setErrors(d.fieldErrors)
      setBanner(d.banner)
    }
  }

  return (
    <form id={formId} onSubmit={submit} noValidate className="space-y-6">
      {banner && <FormErrorBanner message={banner} />}

      <fieldset>
        <legend className="mb-3 text-sm font-semibold text-ink">{mode === 'add' ? 'Customer' : 'Loan'}</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Loan ID" error={errors.loanCode} hint={mode === 'add' && !form.loanCode ? `Next free ID: ${suggestion}` : mode === 'edit' ? 'The loan ID cannot be changed.' : undefined}>
            {(p) => (
              <div className="flex gap-2">
                <input {...p} type="text" value={form.loanCode} onChange={(e) => set('loanCode', e.target.value.toUpperCase())} placeholder={suggestion} disabled={mode === 'edit'} autoComplete="off" />
                {mode === 'add' && !form.loanCode && (
                  <button type="button" onClick={() => set('loanCode', suggestion)} className="btn-secondary shrink-0 px-3 py-2">
                    Use {suggestion}
                  </button>
                )}
              </div>
            )}
          </FormField>

          {mode === 'add' ? (
            <FormField label="Customer" error={errors.customerChoice}>
              {(p) => (
                <select {...p} value={form.customerChoice} onChange={(e) => set('customerChoice', e.target.value)}>
                  <option value="">Select a customer…</option>
                  {(customers.data ?? []).map((c) => (
                    <option key={c.id} value={String(c.id)}>
                      {c.fullName} ({c.customerCode})
                    </option>
                  ))}
                  <option value={NEW}>+ New customer…</option>
                </select>
              )}
            </FormField>
          ) : (
            <div>
              <p className="mb-1.5 text-sm font-medium text-ink">Customer</p>
              <p className="rounded-lg border border-line bg-slate-50 px-3 py-2.5 text-sm text-muted">
                {existing?.customerName} ({existing?.customerId}). The customer of a loan cannot be changed.
              </p>
            </div>
          )}
        </div>
        {mode === 'add' && customers.error != null && (
          <div className="mt-3">
            <ErrorState error={customers.error} onRetry={customers.reload} subject="the customer list" bare />
          </div>
        )}

        {mode === 'add' && form.customerChoice === NEW && (
          <div className="mt-4 grid gap-4 rounded-xl border border-line bg-slate-50/60 p-4 sm:grid-cols-2">
            <FormField label="Customer name" error={errors.fullName}>
              {(p) => <input {...p} type="text" value={form.fullName} onChange={(e) => set('fullName', e.target.value)} autoComplete="off" />}
            </FormField>
            <FormField label="Customer ID" error={errors.customerCode} hint="e.g. C2014">
              {(p) => <input {...p} type="text" value={form.customerCode} onChange={(e) => set('customerCode', e.target.value.toUpperCase())} autoComplete="off" />}
            </FormField>
            <FormField label="Email" error={errors.email}>
              {(p) => <input {...p} type="email" value={form.email} onChange={(e) => set('email', e.target.value)} autoComplete="off" />}
            </FormField>
            <FormField label="Employment type" error={errors.employmentType}>
              {(p) => (
                <select {...p} value={form.employmentType} onChange={(e) => set('employmentType', e.target.value)}>
                  {EMPLOYMENT_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t.replace('_', ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}
                    </option>
                  ))}
                </select>
              )}
            </FormField>
            <FormField label="Annual income (₹)" error={errors.annualIncome}>
              {(p) => <input {...p} type="text" inputMode="decimal" value={form.annualIncome} onChange={(e) => set('annualIncome', e.target.value)} placeholder="e.g. 900000" />}
            </FormField>
          </div>
        )}
      </fieldset>

      <fieldset>
        <legend className="mb-3 text-sm font-semibold text-ink">Loan details</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Loan type" error={errors.loanType}>
            {(p) => (
              <select {...p} value={form.loanType} onChange={(e) => set('loanType', e.target.value)}>
                {LOAN_TYPES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            )}
          </FormField>
          <FormField label="Loan status" error={errors.loanStatus}>
            {(p) => (
              <select {...p} value={form.loanStatus} onChange={(e) => set('loanStatus', e.target.value)}>
                {STATUSES.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            )}
          </FormField>
          <FormField label="Principal amount (₹)" error={errors.originalPrincipal}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.originalPrincipal} onChange={(e) => set('originalPrincipal', e.target.value)} placeholder="e.g. 500000" />}
          </FormField>
          <FormField label="Outstanding balance (₹)" error={errors.outstandingBalance}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.outstandingBalance} onChange={(e) => set('outstandingBalance', e.target.value)} placeholder="e.g. 450000" />}
          </FormField>
          <FormField label="Customer interest rate (% p.a.)" error={errors.interestRate}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.interestRate} onChange={(e) => set('interestRate', e.target.value)} placeholder="e.g. 10.5" />}
          </FormField>
          <FormField label="Loan duration (months)" error={errors.tenureMonths}>
            {(p) => <input {...p} type="text" inputMode="numeric" value={form.tenureMonths} onChange={(e) => set('tenureMonths', e.target.value)} placeholder="e.g. 60" />}
          </FormField>
          <FormField label="Loan start date" error={errors.startDate}>
            {(p) => <input {...p} type="date" max={today()} value={form.startDate} onChange={(e) => set('startDate', e.target.value)} />}
          </FormField>
        </div>
        <p className="mt-3 text-xs leading-relaxed text-muted">
          Interest income, funding and operating costs and the credit-risk inputs are recorded per reporting period after the loan exists
          (see the Financial Breakdown and Credit Risk tabs on the loan page). Profitability is calculated by the backend.
        </p>
      </fieldset>
    </form>
  )
}

interface Props {
  open: boolean
  mode: 'add' | 'edit'
  existing?: LoanDetail
  onClose: () => void
  onSaved: (loan: LoanDetail, mode: 'add' | 'edit') => void
}

export function BackendLoanFormModal({ open, mode, existing, onClose, onSaved }: Props) {
  const formId = useId()
  const { invalidate } = useAppData()
  const [saving, setSaving] = useState(false)

  const submit = async (values: BackendLoanFormValues) => {
    if (saving) return // no duplicate submissions
    setSaving(true)
    try {
      const saved = mode === 'add' ? await createBackendLoan(values) : await updateBackendLoan(existing?.id ?? values.loanCode, values)
      invalidate() // every screen refetches from the backend
      onSaved(saved, mode)
      onClose()
    } catch (error) {
      setSaving(false)
      throw error
    }
  }

  return (
    <Modal
      open={open}
      onClose={() => !saving && onClose()}
      title={mode === 'add' ? 'Add New Loan' : `Edit Loan ${existing?.id ?? ''}`}
      description={mode === 'add' ? 'The loan is saved to the LoanLens database.' : 'Update the loan. Figures on every screen refresh after saving.'}
      footer={
        <>
          <button type="button" onClick={onClose} className="btn-secondary" disabled={saving}>
            Cancel
          </button>
          <button type="submit" form={formId} className="btn-primary" disabled={saving}>
            {saving ? 'Saving…' : mode === 'add' ? 'Add Loan' : 'Save Changes'}
          </button>
        </>
      }
    >
      <Body formId={formId} mode={mode} existing={existing} onSubmit={submit} />
    </Modal>
  )
}
