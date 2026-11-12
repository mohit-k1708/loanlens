import { useId, useState, type FormEvent } from 'react'
import { saveRiskAssessment } from '../services/backendRecordService'
import type { RiskAssessmentFormValues, RiskDetails } from '../types/loan'
import { describeSaveError, FormErrorBanner, FormField, parsePercentInput, parseRupeesToPaise } from './FormField'
import { Modal } from './Modal'

interface Props {
  open: boolean
  loanId: string
  defaultPeriod: string
  /** Suggested exposure at default (the loan's outstanding balance, in paise). */
  defaultEad?: number
  /** When set, the form corrects this existing assessment (the period cannot change). */
  existing?: RiskDetails
  onClose: () => void
  onSaved: (period: string) => void
}

type Key = 'reportingPeriod' | 'pd' | 'lgd' | 'ead'
const KEYS: readonly Key[] = ['reportingPeriod', 'pd', 'lgd', 'ead']
/** Backend field names -> form keys */
const FIELD_MAP: Partial<Record<string, Key>> = { probabilityOfDefault: 'pd', lossGivenDefault: 'lgd', exposureAtDefault: 'ead' }
type Form = Record<Key, string>

function initial(p: Props): Form {
  if (p.existing) {
    return {
      reportingPeriod: p.existing.reportingPeriod ?? p.defaultPeriod,
      pd: String(p.existing.pdPct),
      lgd: String(p.existing.lgdPct),
      ead: String(p.existing.ead / 100),
    }
  }
  return { reportingPeriod: p.defaultPeriod, pd: '', lgd: '', ead: p.defaultEad === undefined ? '' : String(p.defaultEad / 100) }
}

function validate(f: Form): { errors: Partial<Record<Key, string>>; values?: RiskAssessmentFormValues } {
  const errors: Partial<Record<Key, string>> = {}
  if (!/^\d{4}$/.test(f.reportingPeriod.trim())) errors.reportingPeriod = 'Enter a four-digit year, e.g. 2026.'
  const pd = parsePercentInput(f.pd, 0, 100)
  if (pd === null) errors.pd = 'Enter a percentage between 0 and 100, e.g. 10.'
  const lgd = parsePercentInput(f.lgd, 0, 100)
  if (lgd === null) errors.lgd = 'Enter a percentage between 0 and 100, e.g. 60.'
  const ead = parseRupeesToPaise(f.ead)
  if (ead === null) errors.ead = 'Enter the exposure in rupees (0 or more).'
  if (Object.keys(errors).length > 0) return { errors }
  return { errors, values: { reportingPeriod: f.reportingPeriod.trim(), pd: pd!, lgd: lgd!, ead: ead! } }
}

function Body({ formId, loanId, existing, onSaved, onSavingChange, ...props }: Omit<Props, 'open' | 'onClose'> & { formId: string; onSavingChange: (saving: boolean) => void }) {
  const [form, setForm] = useState<Form>(() => initial({ ...props, loanId, existing, onSaved, open: true, onClose: () => {} }))
  const [errors, setErrors] = useState<Partial<Record<Key, string>>>({})
  const [banner, setBanner] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (key: Key, value: string) => {
    setForm((f) => ({ ...f, [key]: value }))
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
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
      await saveRiskAssessment(loanId, result.values, Boolean(existing))
      onSaved(result.values.reportingPeriod)
    } catch (error) {
      const d = describeSaveError(error, KEYS, FIELD_MAP, 'The risk assessment')
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
        PD, LGD and EAD are model inputs. The <strong>Expected Credit Loss is calculated by the backend</strong> from them (PD × LGD × EAD) and
        shown after saving. It is an estimate of potential future loss, not a loss that has occurred. The risk rating is derived from the PD.
      </p>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label="Reporting period (year)" error={errors.reportingPeriod}>
          {(p) => <input {...p} type="text" inputMode="numeric" value={form.reportingPeriod} onChange={(e) => set('reportingPeriod', e.target.value)} placeholder="2026" disabled={Boolean(existing)} />}
        </FormField>
        <FormField label="Exposure at Default – EAD (₹)" error={errors.ead} hint="Defaults to the outstanding balance.">
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.ead} onChange={(e) => set('ead', e.target.value)} placeholder="e.g. 500000" />}
        </FormField>
        <FormField label="Probability of Default – PD (%)" error={errors.pd}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.pd} onChange={(e) => set('pd', e.target.value)} placeholder="e.g. 10" />}
        </FormField>
        <FormField label="Loss Given Default – LGD (%)" error={errors.lgd}>
          {(p) => <input {...p} type="text" inputMode="decimal" value={form.lgd} onChange={(e) => set('lgd', e.target.value)} placeholder="e.g. 60" />}
        </FormField>
      </div>
    </form>
  )
}

export function RiskAssessmentFormModal(props: Props) {
  const formId = useId()
  const [saving, setSaving] = useState(false)
  const { open, onClose, existing } = props
  return (
    <Modal
      open={open}
      onClose={() => !saving && onClose()}
      title={existing ? `Edit risk assessment ${existing.reportingPeriod ?? ''}` : 'Add risk assessment'}
      description={`Loan ${props.loanId}`}
      footer={
        <>
          <button type="button" onClick={onClose} className="btn-secondary" disabled={saving}>
            Cancel
          </button>
          <button type="submit" form={formId} className="btn-primary" disabled={saving}>
            {saving ? 'Saving…' : existing ? 'Save Changes' : 'Add Assessment'}
          </button>
        </>
      }
    >
      <Body {...props} formId={formId} onSavingChange={setSaving} />
    </Modal>
  )
}
