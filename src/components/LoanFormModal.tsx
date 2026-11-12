import { CircleAlert } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { isApiError, userMessage } from '../services/apiError'
import { keys } from '../services/keys'
import { createLoan, getLoan, updateLoan } from '../services/loanService'
import { LOAN_TYPES, type LoanDetail, type LoanFormValues, type LoanType } from '../types/loan'
import { formatINR, formatSignedINR, parseRupeesToPaise } from '../utils/formatCurrency'
import { buildLoan } from '../utils/loanFactory'
import { DEFAULT_PERIOD, computeLoanFinancials, deriveRiskRating } from '../utils/profitability'
import { ErrorState } from './ErrorState'
import { Modal } from './Modal'
import { Skeleton } from './Skeleton'
import { RiskBadge } from './StatusBadge'

interface FormState {
  customerName: string
  customerId: string
  loanType: LoanType
  originalAmount: string
  outstandingBalance: string
  interestRate: string
  durationMonths: string
  startDate: string
  interestIncome: string
  feeIncome: string
  fundingRate: string
  supportContacts: string
  pd: string
  lgd: string
}

type FieldKey = keyof FormState
type Errors = Partial<Record<FieldKey, string>>

const today = () => new Date().toISOString().slice(0, 10)

const EMPTY: FormState = {
  customerName: '',
  customerId: '',
  loanType: 'Personal Loan',
  originalAmount: '',
  outstandingBalance: '',
  interestRate: '',
  durationMonths: '',
  startDate: '',
  interestIncome: '',
  feeIncome: '0',
  fundingRate: '7',
  supportContacts: '4',
  pd: '',
  lgd: '',
}

const rupeesText = (paise: number) => String(paise / 100)

function fromDetail(d: LoanDetail): FormState {
  const inputs = d.inputs
  if (!inputs) throw new Error('The demonstration form needs the loan reporting inputs')
  return {
    customerName: d.customerName,
    customerId: d.customerId,
    loanType: d.loanType,
    originalAmount: rupeesText(d.originalAmount),
    outstandingBalance: rupeesText(d.outstandingBalance),
    interestRate: String(d.interestRate),
    durationMonths: String(d.durationMonths),
    startDate: d.startDate,
    interestIncome: rupeesText(inputs.interestIncome),
    feeIncome: rupeesText(inputs.feeIncome),
    fundingRate: String(inputs.fundingRate),
    supportContacts: String(inputs.supportContacts),
    pd: String(inputs.pd),
    lgd: String(inputs.lgd),
  }
}

const parsePercent = (value: string, max: number): number | null => {
  if (!/^\d+(\.\d{1,2})?$/.test(value.trim())) return null
  const n = Number(value)
  return n <= max ? n : null
}

/** Map backend field names (API contract) back to form fields. */
const SERVER_FIELD_MAP: Record<string, FieldKey> = {
  probabilityOfDefaultPercent: 'pd',
  lossGivenDefaultPercent: 'lgd',
  fundingRatePercent: 'fundingRate',
}
const FORM_KEYS = Object.keys(EMPTY) as FieldKey[]

function validate(form: FormState): { errors: Errors; values?: LoanFormValues } {
  const errors: Errors = {}

  const name = form.customerName.trim()
  if (!name) errors.customerName = 'Customer name is required.'
  else if (name.length < 2) errors.customerName = 'Enter at least 2 characters.'

  const customerId = form.customerId.trim().toUpperCase()
  if (customerId && !/^[A-Z0-9-]{3,12}$/.test(customerId)) errors.customerId = 'Use 3–12 letters, digits or hyphens.'

  const principal = parseRupeesToPaise(form.originalAmount)
  if (!form.originalAmount.trim()) errors.originalAmount = 'Principal amount is required.'
  else if (principal === null || principal <= 0) errors.originalAmount = 'Enter a valid amount in rupees, e.g. 500000.'

  const outstanding = parseRupeesToPaise(form.outstandingBalance)
  if (!form.outstandingBalance.trim()) errors.outstandingBalance = 'Outstanding balance is required.'
  else if (outstanding === null || outstanding <= 0) errors.outstandingBalance = 'Enter a valid amount greater than zero.'
  else if (principal !== null && outstanding > principal) errors.outstandingBalance = 'Cannot exceed the principal amount.'

  const rate = parsePercent(form.interestRate, 60)
  if (!form.interestRate.trim()) errors.interestRate = 'Interest rate is required.'
  else if (rate === null || rate <= 0) errors.interestRate = 'Enter a rate between 0.01 and 60.'

  const months = Number(form.durationMonths)
  if (!form.durationMonths.trim()) errors.durationMonths = 'Loan duration is required.'
  else if (!Number.isInteger(months) || months < 1 || months > 480) errors.durationMonths = 'Enter whole months between 1 and 480.'

  if (!form.startDate) errors.startDate = 'Start date is required.'
  else if (form.startDate > today()) errors.startDate = 'Start date cannot be in the future.'

  const interestIncome = parseRupeesToPaise(form.interestIncome)
  if (!form.interestIncome.trim()) errors.interestIncome = 'Interest income is required (enter 0 if none).'
  else if (interestIncome === null) errors.interestIncome = 'Enter a valid amount in rupees.'

  const feeIncome = parseRupeesToPaise(form.feeIncome)
  if (!form.feeIncome.trim()) errors.feeIncome = 'Fee income is required (enter 0 if none).'
  else if (feeIncome === null) errors.feeIncome = 'Enter a valid amount in rupees.'

  const fundingRate = parsePercent(form.fundingRate, 30)
  if (!form.fundingRate.trim()) errors.fundingRate = 'Funding rate is required.'
  else if (fundingRate === null) errors.fundingRate = 'Enter a rate between 0 and 30.'

  const contacts = Number(form.supportContacts)
  if (!form.supportContacts.trim()) errors.supportContacts = 'Enter the number of contacts (0 if none).'
  else if (!Number.isInteger(contacts) || contacts < 0 || contacts > 1000) errors.supportContacts = 'Enter a whole number between 0 and 1000.'

  const pd = parsePercent(form.pd, 100)
  if (!form.pd.trim()) errors.pd = 'Probability of Default is required.'
  else if (pd === null) errors.pd = 'Enter a percentage between 0 and 100.'

  const lgd = parsePercent(form.lgd, 100)
  if (!form.lgd.trim()) errors.lgd = 'Loss Given Default is required.'
  else if (lgd === null) errors.lgd = 'Enter a percentage between 0 and 100.'

  if (Object.keys(errors).length > 0) return { errors }

  return {
    errors,
    values: {
      customerName: name,
      customerId: customerId || undefined,
      loanType: form.loanType,
      originalAmount: principal!,
      outstandingBalance: outstanding!,
      interestRate: rate!,
      durationMonths: months,
      startDate: form.startDate,
      interestIncome: interestIncome!,
      feeIncome: feeIncome!,
      fundingRate: fundingRate!,
      supportContacts: contacts,
      pd: pd!,
      lgd: lgd!,
    },
  }
}

interface FieldProps {
  label: string
  error?: string
  hint?: string
  required?: boolean
  children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string; className: string }) => ReactNode
}

function Field({ label, error, hint, required = true, children }: FieldProps) {
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

/* ---------------- Form body (mounted fresh each time the modal opens) ---------------- */

interface BodyProps {
  formId: string
  mode: 'add' | 'edit'
  initial?: LoanDetail
  onSubmit: (values: LoanFormValues) => Promise<void>
}

function LoanFormBody({ formId, mode, initial, onSubmit }: BodyProps) {
  const [form, setForm] = useState<FormState>(() => (initial ? fromDetail(initial) : EMPTY))
  const [errors, setErrors] = useState<Errors>({})
  const [formError, setFormError] = useState('')

  const set = <K extends FieldKey>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }))
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }))
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setFormError('')
    const result = validate(form)
    if (!result.values) {
      setErrors(result.errors)
      requestAnimationFrame(() => document.querySelector<HTMLElement>(`#${CSS.escape(formId)} [aria-invalid="true"]`)?.focus())
      return
    }
    try {
      await onSubmit(result.values)
    } catch (error) {
      if (isApiError(error) && error.kind === 'validation' && error.fieldErrors) {
        const mapped: Errors = {}
        for (const [serverField, message] of Object.entries(error.fieldErrors)) {
          const key = SERVER_FIELD_MAP[serverField] ?? (FORM_KEYS.includes(serverField as FieldKey) ? (serverField as FieldKey) : undefined)
          if (key) mapped[key] = message
        }
        setErrors(mapped)
      }
      setFormError(`The loan was not saved. ${userMessage(error, 'the loan')}`)
    }
  }

  // Local preview so the manager sees the effect of the inputs before saving. The saved figures
  // are always the ones returned by the service layer.
  const preview = useMemo(() => {
    const { values } = validate(form)
    if (!values) return null
    const loan = buildLoan({ ...values, id: 'preview', customerId: values.customerId ?? 'preview' })
    return computeLoanFinancials(loan, DEFAULT_PERIOD)
  }, [form])

  const pdPreview = parsePercent(form.pd, 100)

  return (
    <form id={formId} onSubmit={handleSubmit} noValidate className="space-y-6">
      {formError && (
        <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-negative/30 bg-negative/5 px-3.5 py-3 text-sm text-negative">
          <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          {formError}
        </div>
      )}

      <fieldset>
        <legend className="mb-3 text-sm font-semibold text-ink">Customer &amp; loan details</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Customer name" error={errors.customerName}>
            {(p) => <input {...p} type="text" value={form.customerName} onChange={(e) => set('customerName', e.target.value)} placeholder="e.g. Anita Rao" autoComplete="off" />}
          </Field>
          <Field label="Customer ID" required={false} error={errors.customerId} hint={mode === 'edit' ? 'The customer ID cannot be changed.' : 'Leave blank to generate automatically.'}>
            {(p) => (
              <input {...p} type="text" value={form.customerId} onChange={(e) => set('customerId', e.target.value)} placeholder="e.g. C2013" autoComplete="off" disabled={mode === 'edit'} />
            )}
          </Field>
          <Field label="Loan type">
            {(p) => (
              <select {...p} value={form.loanType} onChange={(e) => set('loanType', e.target.value as LoanType)}>
                {LOAN_TYPES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Loan start date" error={errors.startDate}>
            {(p) => <input {...p} type="date" max={today()} value={form.startDate} onChange={(e) => set('startDate', e.target.value)} />}
          </Field>
          <Field label="Principal amount (₹)" error={errors.originalAmount}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.originalAmount} onChange={(e) => set('originalAmount', e.target.value)} placeholder="e.g. 500000" />}
          </Field>
          <Field label="Outstanding balance (₹)" error={errors.outstandingBalance}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.outstandingBalance} onChange={(e) => set('outstandingBalance', e.target.value)} placeholder="e.g. 450000" />}
          </Field>
          <Field label="Customer interest rate (% p.a.)" error={errors.interestRate}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.interestRate} onChange={(e) => set('interestRate', e.target.value)} placeholder="e.g. 10.5" />}
          </Field>
          <Field label="Loan duration (months)" error={errors.durationMonths}>
            {(p) => <input {...p} type="text" inputMode="numeric" value={form.durationMonths} onChange={(e) => set('durationMonths', e.target.value)} placeholder="e.g. 60" />}
          </Field>
        </div>
      </fieldset>

      <fieldset>
        <legend className="mb-1 text-sm font-semibold text-ink">12-month reporting-period inputs</legend>
        <p className="mb-3 text-xs text-muted">
          Income is a supplied input in this prototype. Average outstanding balance is taken as the outstanding balance, EAD equals the outstanding balance, and operational cost uses the activity rates for the selected loan type.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Interest income (₹)" error={errors.interestIncome}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.interestIncome} onChange={(e) => set('interestIncome', e.target.value)} placeholder="e.g. 60000" />}
          </Field>
          <Field label="Fee income (₹)" error={errors.feeIncome}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.feeIncome} onChange={(e) => set('feeIncome', e.target.value)} />}
          </Field>
          <Field label="Funding rate (% p.a.)" error={errors.fundingRate}>
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.fundingRate} onChange={(e) => set('fundingRate', e.target.value)} />}
          </Field>
          <Field label="Customer-service contacts" error={errors.supportContacts} hint="Cost driver for the Customer Support activity.">
            {(p) => <input {...p} type="text" inputMode="numeric" value={form.supportContacts} onChange={(e) => set('supportContacts', e.target.value)} />}
          </Field>
          <Field label="Probability of Default – PD (%)" error={errors.pd} hint="Supplied risk-model input.">
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.pd} onChange={(e) => set('pd', e.target.value)} placeholder="e.g. 10" />}
          </Field>
          <Field label="Loss Given Default – LGD (%)" error={errors.lgd} hint="Supplied risk-model input.">
            {(p) => <input {...p} type="text" inputMode="decimal" value={form.lgd} onChange={(e) => set('lgd', e.target.value)} placeholder="e.g. 60" />}
          </Field>
        </div>
        {pdPreview !== null && (
          <p className="mt-3 flex items-center gap-2 text-xs text-muted">
            Risk rating derived from PD: <RiskBadge rating={deriveRiskRating(pdPreview)} />
          </p>
        )}
      </fieldset>

      {preview && (
        <div className="rounded-xl border border-primary/20 bg-primary/5 p-4 text-sm" aria-live="polite">
          <p className="font-semibold text-ink">
            Preview (12 months) <span className="font-normal text-muted">· calculated in your browser</span>
          </p>
          <dl className="mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-3">
            <div>
              <dt className="text-xs text-muted">Expected Credit Loss (estimate)</dt>
              <dd className="tabular font-semibold text-warning">{formatINR(preview.expectedCreditLoss)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Operational cost</dt>
              <dd className="tabular font-semibold text-ink">{formatINR(preview.operationalCost)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Estimated contribution</dt>
              <dd className={`tabular font-semibold ${preview.contributionAfterEcl >= 0 ? 'text-positive' : 'text-negative'}`}>{formatSignedINR(preview.contributionAfterEcl)}</dd>
            </div>
          </dl>
        </div>
      )}
    </form>
  )
}

/* ---------------- Modal ---------------- */

interface LoanFormModalProps {
  open: boolean
  mode: 'add' | 'edit'
  /** Required for edit mode. */
  loanId?: string
  onClose: () => void
  onSaved: (loan: LoanDetail, mode: 'add' | 'edit') => void
}

export function LoanFormModal({ open, mode, loanId, onClose, onSaved }: LoanFormModalProps) {
  const formId = useId()
  const { period, invalidate } = useAppData()
  const [saving, setSaving] = useState(false)

  const editing = mode === 'edit' && open && Boolean(loanId)
  const detail = useApi(editing ? keys.loan(loanId ?? '', period.id) : null, () => getLoan(loanId ?? '', period.id))
  const ready = mode === 'add' || Boolean(detail.data)

  const submit = async (values: LoanFormValues) => {
    setSaving(true)
    try {
      const saved = mode === 'add' ? await createLoan(values) : await updateLoan(loanId ?? '', values)
      invalidate() // every screen refetches from the single source of truth
      onSaved(saved, mode)
      onClose()
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={mode === 'add' ? 'Add New Loan' : `Edit Loan ${loanId ?? ''}`}
      description={
        mode === 'add'
          ? 'Enter the loan information. It is saved through the LoanLens data service.'
          : 'Update the loan information. Figures on every screen refresh after saving.'
      }
      footer={
        <>
          <button type="button" onClick={onClose} className="btn-secondary" disabled={saving}>
            Cancel
          </button>
          <button type="submit" form={formId} className="btn-primary" disabled={saving || !ready}>
            {saving ? 'Saving…' : mode === 'add' ? 'Add Loan' : 'Save Changes'}
          </button>
        </>
      }
    >
      {mode === 'edit' && detail.error ? (
        <ErrorState error={detail.error} onRetry={detail.reload} subject="loan details" bare />
      ) : !ready ? (
        <div role="status" aria-busy="true" className="space-y-3">
          <span className="sr-only">Loading loan details…</span>
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-12" />
          ))}
        </div>
      ) : (
        <LoanFormBody key={detail.data?.id ?? 'new'} formId={formId} mode={mode} initial={detail.data} onSubmit={submit} />
      )}
    </Modal>
  )
}
