import { useId, useState, type FormEvent } from 'react'
import { saveFinancialRecord } from '../services/backendRecordService'
import type { FinancialRecordFormValues, FinancialRecordView } from '../types/loan'
import { describeSaveError, FormErrorBanner, FormField, parsePercentInput, parseRupeesToPaise } from './FormField'
import { Modal } from './Modal'

interface Props {
  open: boolean
  loanId: string
  /** Period pre-filled when adding (usually the selected reporting period). */
  defaultPeriod: string
  /** When set, the form corrects this existing record (the period cannot change). */
  existing?: FinancialRecordView
  onClose: () => void
  onSaved: (period: string) => void
}

type Key = 'reportingPeriod' | 'interestIncome' | 'feeIncome' | 'averageOutstandingBalance' | 'fundingRate' | 'fundingCost' | 'operationalCost'
const KEYS: readonly Key[] = ['reportingPeriod', 'interestIncome', 'feeIncome', 'averageOutstandingBalance', 'fundingRate', 'fundingCost', 'operationalCost']
type Form = Record<Key, string>
const rupees = (paise: number) => String(paise / 100)

function initial(defaultPeriod: string, e?: FinancialRecordView): Form {
  return e
    ? {
        reportingPeriod: e.reportingPeriod,
        interestIncome: rupees(e.interestIncome),
        feeIncome: rupees(e.feeIncome),
        averageOutstandingBalance: e.averageOutstandingBalance === null ? '' : rupees(e.averageOutstandingBalance),
        fundingRate: String(e.fundingRatePct),
        fundingCost: rupees(e.fundingCost),
        operationalCost: rupees(e.operationalCost),
      }
    : { reportingPeriod: defaultPeriod, interestIncome: '', feeIncome: '0', averageOutstandingBalance: '', fundingRate: '', fundingCost: '', operationalCost: '' }
}

function validate(f: Form): { errors: Partial<Record<Key, string>>; values?: FinancialRecordFormValues } {
  const errors: Partial<Record<Key, string>> = {}
  if (!/^\d{4}$/.test(f.reportingPeriod.trim())) errors.reportingPeriod = 'Enter a four-digit year, e.g. 2026.'
  const interest = parseRupeesToPaise(f.interestIncome)
  if (interest === null) errors.interestIncome = 'Enter the interest income in rupees (0 if none).'
  const fee = parseRupeesToPaise(f.feeIncome)
  if (fee === null) errors.feeIncome = 'Enter the fee income in rupees (0 if none).'
  let avg: number | null = null
  if (f.averageOutstandingBalance.trim()) {
    avg = parseRupeesToPaise(f.averageOutstandingBalance)
    if (avg === null) errors.averageOutstandingBalance = 'Enter a valid amount in rupees, or leave blank.'
  }
  const rate = parsePercentInput(f.fundingRate, 0, 100)
  if (rate === null) errors.fundingRate = 'Enter the funding rate as a percentage between 0 and 100, e.g. 7.'
  const funding = parseRupeesToPaise(f.fundingCost)
  if (funding === null) errors.fundingCost = 'Enter the funding cost in rupees.'
  const ops = parseRupeesToPaise(f.operationalCost)
  if (ops === null) errors.operationalCost = 'Enter the operational cost in rupees.'
  if (Object.keys(errors).length > 0) return { errors }
  return {
    errors,
    values: {
      reportingPeriod: f.reportingPeriod.trim(),
      interestIncome: interest!,
      feeIncome: fee!,
      averageOutstandingBalance: avg,
      fundingRate: rate!,
      fundingCost: funding!,
      operationalCost: ops!,
    },
  }
}

function Body({ formId, loanId, defaultPeriod, existing, onSaved, onSavingChange }: Omit<Props, 'open' | 'onClose'> & { formId: string; onSavingChange: (saving: boolean) => void }) {
  const [form, setForm] = useState<Form>(() => initial(defaultPeriod, existing))
  const [errors, setErrors] = useState<Partial<Record<Key, string>>>({})
  const [banner, setBanner] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (key: Key, value: string) => {
    setForm((f) => ({ ...f, [key]: value }))
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return // no duplicate submissions
    setBanner('')
    const result = validate(form)
    if (!result.values) {
      setErrors(result.errors)
      requestAnimationFrame(() => document.querySelector<HTMLElement>(`#${CSS.escape(formId)} [aria-invalid="true"]`)?.focus())
      return
    }
    setBusy(true)
    onSavingChange(true)
    try {
      await saveFinancialRecord(loanId, result.values, Boolean(existing))
      onSaved(result.values.reportingPeriod)
    } catch (error) {
      const d = describeSaveError(error, KEYS, {}, 'The financial record')
      setErrors(d.fieldErrors)
      setBanner(d.banner)
      setBusy(false)
      onSavingChange(false)
    }
  }

  return (
    <form id={formId} onSubmit={submit} noValidate className="space-y-5">
      {banner && <FormErrorBanner message={banner} />}
      <p className="text-xs leading-relaxed text-muted">
        These are the supplied income and cost inputs for one reporting period. Interest income is an input, not calculated from principal × rate.
        The backend validates them and calculates the profitability from what you record.
      </p>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label="Reporting period (year)" error={errors.reportingPeriod}>
          {(p) => <input {...p} type="text" inputMode="numeric" value={form.reportingPeriod} onChange={(e) => set('reportingPeriod', e.target.value)} placeholder="2026" disabled={Boolean(existing)} />}
        </FormField>
        <FormField label="Interest income (₹)" error={errors.interestIncome}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.interestIncome} onChange={(e) => set('interestIncome', e.target.value)} placeholder="e.g. 60000" />}
        </FormField>
        <FormField label="Fee income (₹)" error={errors.feeIncome}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.feeIncome} onChange={(e) => set('feeIncome', e.target.value)} />}
        </FormField>
        <FormField label="Average outstanding balance (₹)" required={false} error={errors.averageOutstandingBalance} hint="If given, the backend checks funding cost against balance × funding rate.">
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.averageOutstandingBalance} onChange={(e) => set('averageOutstandingBalance', e.target.value)} placeholder="e.g. 500000" />}
        </FormField>
        <FormField label="Funding rate (% p.a.)" error={errors.fundingRate}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.fundingRate} onChange={(e) => set('fundingRate', e.target.value)} placeholder="e.g. 7" />}
        </FormField>
        <FormField label="Funding cost (₹)" error={errors.fundingCost}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.fundingCost} onChange={(e) => set('fundingCost', e.target.value)} placeholder="e.g. 35000" />}
        </FormField>
        <FormField label="Operational cost (₹)" error={errors.operationalCost}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.operationalCost} onChange={(e) => set('operationalCost', e.target.value)} placeholder="e.g. 7000" />}
        </FormField>
      </div>
    </form>
  )
}

export function FinancialRecordFormModal(props: Props) {
  const formId = useId()
  const [saving, setSaving] = useState(false)
  const { open, onClose, existing } = props
  return (
    <Modal
      open={open}
      onClose={() => !saving && onClose()}
      title={existing ? `Edit financial record ${existing.reportingPeriod}` : 'Add financial record'}
      description={`Loan ${props.loanId}`}
      footer={
        <>
          <button type="button" onClick={onClose} className="btn-secondary" disabled={saving}>
            Cancel
          </button>
          <button type="submit" form={formId} className="btn-primary" disabled={saving}>
            {saving ? 'Saving…' : existing ? 'Save Changes' : 'Add Record'}
          </button>
        </>
      }
    >
      <Body {...props} formId={formId} onSavingChange={setSaving} />
    </Modal>
  )
}
