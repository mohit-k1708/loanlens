import type {
  AttentionLoan,
  BackendLoanFormValues,
  CustomerOption,
  DashboardSummary,
  FinancialRecordFormValues,
  FinancialRecordView,
  LoanDetail,
  LoanFinancials,
  LoanPage,
  LoanType,
  MonthlyTrendPoint,
  RiskAssessmentFormValues,
  RiskDetails,
  RiskRating,
  LoanStatus,
  ContributionStatus,
  Paise,
} from '../../types/loan'
import type { AiExplanation, AiFact, AiFallbackReason, AiImpact, AiReason, AiStatus } from '../../types/ai'
import { getPeriod } from '../../utils/profitability'
import { ApiError } from '../apiError'
import {
  asArray,
  asObject,
  readBoolean,
  readMapped,
  readNullableMapped,
  readNullablePaise,
  readNumber,
  readPaise,
  readString,
  readStringArray,
  toRupees,
  type Obj,
} from '../validators'

/**
 * Maps the REAL Spring Boot responses (see backend/README.md) to the UI view models, and view-model form values
 * to request bodies. Money arrives as rupees and becomes integer paise; rates arrive as decimal fractions
 * (0.10) and become percentages (10). Nothing is calculated here: totals and contributions are copied from the
 * backend, and a missing value stays null instead of becoming zero.
 */

/* ---------------- enum tables (backend code <-> UI label) ---------------- */

const LOAN_TYPE: Record<string, LoanType> = {
  PERSONAL: 'Personal Loan',
  HOME: 'Home Loan',
  AUTO: 'Auto Loan',
  BUSINESS: 'Business Loan',
  EDUCATION: 'Education Loan',
  GOLD: 'Gold Loan',
}
const LOAN_STATUS: Record<string, LoanStatus> = {
  ACTIVE: 'Active',
  UNDER_WATCH: 'Under Watch',
  CLOSED: 'Closed',
  DEFAULTED: 'Defaulted',
}
const RISK_RATING: Record<string, RiskRating> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' }
const CONTRIBUTION: Record<string, ContributionStatus> = { POSITIVE: 'positive', NEGATIVE: 'negative', BREAK_EVEN: 'break-even' }
const COST_CATEGORY: Record<string, string> = {
  LOAN_PROCESSING: 'Loan Processing',
  CUSTOMER_SUPPORT: 'Customer Support',
  LOAN_SERVICING: 'Loan Servicing',
  COLLECTIONS: 'Collections',
  OTHER: 'Other',
}

const invert = <T extends string>(table: Record<string, T>): Record<T, string> =>
  Object.fromEntries(Object.entries(table).map(([code, label]) => [label, code])) as Record<T, string>
export const LOAN_TYPE_CODE = invert(LOAN_TYPE)
export const LOAN_STATUS_CODE = invert(LOAN_STATUS)
export const RISK_RATING_CODE = invert(RISK_RATING)
export const CONTRIBUTION_CODE = invert(CONTRIBUTION)

/** 0.0875 -> 8.75 */
export const fractionToPercent = (fraction: number): number => Number((fraction * 100).toFixed(4))
/** 8.75 -> 0.0875 */
export const percentToFraction = (percent: number): number => Number((percent / 100).toFixed(6))

/** A figure that does not add up means the payload is corrupt; refuse to show it. */
function assertEquals(actual: number, expected: number, what: string): void {
  if (Math.abs(actual - expected) > 1) throw new ApiError('invalid-response', `Inconsistent figures: ${what}`)
}

/* ---------------- loans ---------------- */

function mapLoanObject(o: Obj): LoanDetail {
  const customer = asObject(o.customer, 'customer')
  const prof = o.profitability == null ? null : asObject(o.profitability, 'profitability')
  const available = prof !== null && readBoolean(prof, 'available')

  return {
    id: readString(o, 'loanCode'),
    customerId: readString(customer, 'customerCode'),
    customerName: readString(customer, 'fullName'),
    loanType: readMapped(o, 'loanType', LOAN_TYPE),
    originalAmount: readPaise(o, 'originalPrincipal'),
    outstandingBalance: readPaise(o, 'outstandingBalance'),
    interestRate: fractionToPercent(readNumber(o, 'interestRate')),
    durationMonths: readNumber(o, 'tenureMonths'),
    startDate: readString(o, 'startDate'),
    maturityDate: readString(o, 'maturityDate'),
    status: readMapped(o, 'loanStatus', LOAN_STATUS),
    riskRating: readNullableMapped(o, 'latestRiskRating', RISK_RATING),
    totalIncome: available ? readPaise(prof, 'totalIncome') : null,
    totalExpenses: available ? readPaise(prof, 'totalExpenses') : null,
    contributionBeforeEcl: available ? readPaise(prof, 'contributionBeforeEcl') : null,
    expectedCreditLoss: available ? readPaise(prof, 'expectedCreditLoss') : null,
    estimatedContribution: available ? readPaise(prof, 'contributionAfterEcl') : null,
    contributionStatus: available ? readMapped(prof, 'contributionStatus', CONTRIBUTION) : null,
    missingInputs: prof !== null && !available ? readStringArray(prof, 'missingInputs') : undefined,
  }
}

export function mapLoan(data: unknown): LoanDetail {
  return mapLoanObject(asObject(data, 'loan'))
}

export function mapLoanPage(data: unknown): LoanPage {
  const o = asObject(data, 'loan page')
  return {
    items: asArray(o.content, 'content').map((item) => mapLoanObject(asObject(item, 'loan'))),
    totalElements: readNumber(o, 'totalElements'),
    totalPages: readNumber(o, 'totalPages'),
  }
}

/* ---------------- profitability, risk, financial records ---------------- */

export function mapProfitability(data: unknown): LoanFinancials {
  const o = asObject(data, 'profitability')
  const risk = asObject(o.risk, 'risk')
  const lines = asArray(o.operationalCostAllocations, 'operationalCostAllocations').map((item) => {
    const a = asObject(item, 'allocation')
    return {
      activity: COST_CATEGORY[readString(a, 'costCategory')] ?? readString(a, 'costCategory'),
      driver: readString(a, 'allocationDriver'),
      allocatedCost: readPaise(a, 'allocatedAmount'),
    }
  })
  const snapshot = asObject(o.snapshot, 'snapshot')
  const ecl = readPaise(o, 'expectedCreditLoss')

  const f: LoanFinancials = {
    period: getPeriod(readString(o, 'reportingPeriod')),
    months: 12,
    interestIncome: readPaise(o, 'interestIncome'),
    feeIncome: readPaise(o, 'feeIncome'),
    totalIncome: readPaise(o, 'totalIncome'),
    fundingCost: readPaise(o, 'fundingCost'),
    operationalCost: readPaise(o, 'operationalCost'),
    expectedCreditLoss: ecl,
    totalExpenses: readPaise(o, 'totalExpenses'),
    contributionBeforeEcl: readPaise(o, 'contributionBeforeEcl'),
    contributionAfterEcl: readPaise(o, 'contributionAfterEcl'),
    contributionStatus: readMapped(o, 'contributionStatus', CONTRIBUTION),
    funding: {
      averageOutstandingBalance: readNullablePaise(o, 'averageOutstandingBalance'),
      fundingRatePct: fractionToPercent(readNumber(o, 'fundingRate')),
      months: 12,
      timeInYears: 1,
      fundingCost: readPaise(o, 'fundingCost'),
    },
    activities: lines,
    credit: {
      pdPct: fractionToPercent(readNumber(risk, 'probabilityOfDefault')),
      lgdPct: fractionToPercent(readNumber(risk, 'lossGivenDefault')),
      ead: readPaise(risk, 'exposureAtDefault'),
      eclTwelveMonth: ecl,
      eclForPeriod: ecl,
    },
    notes: readStringArray(o, 'notes'),
    snapshot: {
      calculatedAt: readString(snapshot, 'calculatedAt'),
      version: readString(snapshot, 'calculationVersion'),
      newlyCreated: readBoolean(snapshot, 'newlyCreated'),
    },
  }

  assertEquals(f.totalIncome, f.interestIncome + f.feeIncome, 'total income')
  assertEquals(f.totalExpenses, f.fundingCost + f.operationalCost + f.expectedCreditLoss, 'total expenses')
  assertEquals(f.contributionBeforeEcl, f.totalIncome - f.fundingCost - f.operationalCost, 'contribution before ECL')
  assertEquals(f.contributionAfterEcl, f.totalIncome - f.totalExpenses, 'contribution after ECL')
  if (lines.length > 0) assertEquals(f.operationalCost, lines.reduce((s, l) => s + l.allocatedCost, 0), 'operational cost allocation')
  return f
}

export function mapRisk(data: unknown): RiskDetails {
  const o = asObject(data, 'risk assessment')
  return {
    loanId: readString(o, 'loanCode'),
    riskRating: readMapped(o, 'riskRating', RISK_RATING),
    pdPct: fractionToPercent(readNumber(o, 'probabilityOfDefault')),
    lgdPct: fractionToPercent(readNumber(o, 'lossGivenDefault')),
    ead: readPaise(o, 'exposureAtDefault'),
    expectedCreditLoss12Month: readPaise(o, 'expectedCreditLoss'),
    reportingPeriod: readString(o, 'reportingPeriod'),
    assessedAt: readString(o, 'assessedAt'),
  }
}

export function mapFinancialRecords(data: unknown): FinancialRecordView[] {
  return asArray(data, 'financial records').map((item) => {
    const o = asObject(item, 'financial record')
    return {
      reportingPeriod: readString(o, 'reportingPeriod'),
      interestIncome: readPaise(o, 'interestIncome'),
      feeIncome: readPaise(o, 'feeIncome'),
      totalIncome: readPaise(o, 'totalIncome'),
      averageOutstandingBalance: readNullablePaise(o, 'averageOutstandingBalance'),
      fundingRatePct: fractionToPercent(readNumber(o, 'fundingRate')),
      fundingCost: readPaise(o, 'fundingCost'),
      operationalCost: readPaise(o, 'operationalCost'),
      updatedAt: readString(o, 'updatedAt'),
    }
  })
}

/* ---------------- dashboard ---------------- */

export function mapDashboard(data: unknown): DashboardSummary {
  const o = asObject(data, 'dashboard')
  const withData = readNumber(o, 'loansWithData')
  const withoutData = readNumber(o, 'loansWithoutData')
  const portfolio = {
    totalLoans: readNumber(o, 'totalActiveLoans'),
    totalOutstanding: readPaise(o, 'totalOutstandingBalance'),
    totalIncome: readPaise(o, 'totalIncome'),
    totalExpenses: readPaise(o, 'totalExpenses'),
    contributionBeforeEcl: readPaise(o, 'contributionBeforeEcl'),
    expectedCreditLoss: readPaise(o, 'expectedCreditLoss'),
    contributionAfterEcl: readPaise(o, 'contributionAfterEcl'),
    positiveCount: readNumber(o, 'positiveContributionLoans'),
    negativeCount: readNumber(o, 'negativeContributionLoans'),
    breakEvenCount: readNumber(o, 'breakEvenLoans'),
    underWatchCount: readNumber(o, 'underWatchLoans'),
    highRiskCount: readNumber(o, 'highRiskLoans'),
    noDataCount: withoutData,
  }
  if (portfolio.positiveCount + portfolio.negativeCount + portfolio.breakEvenCount !== withData || withData + withoutData !== portfolio.totalLoans) {
    throw new ApiError('invalid-response', 'Inconsistent figures: loan counts')
  }
  assertEquals(portfolio.contributionAfterEcl, portfolio.totalIncome - portfolio.totalExpenses, 'portfolio contribution')

  const trend: MonthlyTrendPoint[] = asArray(o.contributionTrend, 'contributionTrend').map((item, i) => {
    const t = asObject(item, 'trend point')
    const label = readString(t, 'reportingPeriod')
    return {
      monthIndex: i,
      label,
      shortLabel: label,
      contributionBeforeEcl: readPaise(t, 'contributionBeforeEcl'),
      expectedCreditLoss: readPaise(t, 'expectedCreditLoss'),
      contributionAfterEcl: readPaise(t, 'contributionAfterEcl'),
    }
  })

  const attentionLoans: AttentionLoan[] = asArray(o.attentionLoans, 'attentionLoans').map((item) => {
    const a = asObject(item, 'attention loan')
    return {
      id: readString(a, 'loanCode'),
      customerId: readString(a, 'customerCode'),
      customerName: readString(a, 'customerName'),
      loanType: readMapped(a, 'loanType', LOAN_TYPE),
      status: readMapped(a, 'loanStatus', LOAN_STATUS),
      outstandingBalance: readPaise(a, 'outstandingBalance'),
      riskRating: readNullableMapped(a, 'riskRating', RISK_RATING),
      estimatedContribution: readNullablePaise(a, 'contributionAfterEcl'),
      contributionStatus: readNullableMapped(a, 'contributionStatus', CONTRIBUTION),
      reasons: readStringArray(a, 'reasons'),
    }
  })

  return { period: getPeriod(readString(o, 'reportingPeriod')), portfolio, trend, attentionLoans }
}

/* ---------------- customers, periods ---------------- */

export function mapCustomerOptions(data: unknown): CustomerOption[] {
  const o = asObject(data, 'customer page')
  return asArray(o.content, 'content').map((item) => {
    const c = asObject(item, 'customer')
    return { id: readNumber(c, 'id'), customerCode: readString(c, 'customerCode'), fullName: readString(c, 'fullName') }
  })
}

export function mapCreatedId(data: unknown): number {
  return readNumber(asObject(data, 'created record'), 'id')
}

export function mapPeriods(data: unknown): string[] {
  return asArray(data, 'reporting periods').map((p) => {
    if (typeof p !== 'string' || !/^\d{4}$/.test(p)) throw new ApiError('invalid-response', 'Unexpected response shape: reporting period')
    return p
  })
}

/* ---------------- request bodies (view model -> backend) ---------------- */

export function toLoanCreateBody(values: BackendLoanFormValues, customerId: number) {
  return {
    loanCode: values.loanCode,
    customerId,
    loanType: LOAN_TYPE_CODE[values.loanType],
    originalPrincipal: toRupees(values.originalAmount),
    outstandingBalance: toRupees(values.outstandingBalance),
    interestRate: percentToFraction(values.interestRate),
    tenureMonths: values.durationMonths,
    startDate: values.startDate,
    loanStatus: LOAN_STATUS_CODE[values.loanStatus],
  }
}

export function toLoanUpdateBody(values: BackendLoanFormValues) {
  const { loanCode: _code, customerId: _customer, ...rest } = toLoanCreateBody(values, 0)
  return rest
}

export function toCustomerBody(c: Extract<BackendLoanFormValues['customer'], { kind: 'new' }>) {
  return {
    customerCode: c.customerCode,
    fullName: c.fullName,
    email: c.email,
    employmentType: c.employmentType,
    annualIncome: toRupees(c.annualIncome),
  }
}

export function toFinancialRecordBody(v: FinancialRecordFormValues) {
  return {
    reportingPeriod: v.reportingPeriod,
    interestIncome: toRupees(v.interestIncome),
    feeIncome: toRupees(v.feeIncome),
    averageOutstandingBalance: v.averageOutstandingBalance === null ? null : toRupees(v.averageOutstandingBalance),
    fundingRate: percentToFraction(v.fundingRate),
    fundingCost: toRupees(v.fundingCost),
    operationalCost: toRupees(v.operationalCost),
  }
}

export function toRiskAssessmentBody(v: RiskAssessmentFormValues) {
  return {
    reportingPeriod: v.reportingPeriod,
    probabilityOfDefault: percentToFraction(v.pd),
    lossGivenDefault: percentToFraction(v.lgd),
    exposureAtDefault: toRupees(v.ead as Paise),
  }
}

/* ---------------- AI explanation (POST /ai/explain, GET /ai/status) ---------------- */

const AI_IMPACTS: readonly AiImpact[] = ['NEGATIVE', 'POSITIVE', 'NEUTRAL']
const AI_FALLBACK_REASONS: readonly AiFallbackReason[] = ['NOT_CONFIGURED', 'PROVIDER_ERROR', 'PROVIDER_AUTH', 'PROVIDER_BILLING', 'TIMEOUT', 'RATE_LIMITED', 'INVALID_AI_RESPONSE', 'INSUFFICIENT_DATA']

function oneOf<T extends string>(o: Obj, key: string, allowed: readonly T[]): T {
  const v = readString(o, key)
  if (!(allowed as readonly string[]).includes(v)) throw new ApiError('invalid-response', `Unexpected value for "${key}"`)
  return v as T
}

function readOptionalText(o: Obj, key: string): string | undefined {
  const v = o[key]
  if (v === undefined || v === null) return undefined
  if (typeof v !== 'string') throw new ApiError('invalid-response', `"${key}" must be text`)
  return v
}

/**
 * Validates the structured explanation before anything is displayed. The figures (`facts`, `amountDisplay`) are the
 * backend's own and are shown as received; nothing here calculates or rewrites a number.
 */
export function mapAiExplanation(data: unknown): AiExplanation {
  const o = asObject(data, 'ai explanation')
  const source = oneOf(o, 'source', ['AI', 'FALLBACK'] as const)
  const dataStatus = oneOf(o, 'dataStatus', ['COMPLETE', 'INSUFFICIENT'] as const)
  const fallbackRaw = readOptionalText(o, 'fallbackReason')
  if (fallbackRaw !== undefined && !(AI_FALLBACK_REASONS as readonly string[]).includes(fallbackRaw)) {
    throw new ApiError('invalid-response', 'Unexpected fallback reason')
  }
  if (source === 'FALLBACK' && fallbackRaw === undefined) throw new ApiError('invalid-response', 'A fallback explanation must say why')

  const facts: AiFact[] = asArray(o.facts, 'facts').map((f) => {
    const r = asObject(f, 'fact')
    return { key: readString(r, 'key'), label: readString(r, 'label'), display: readString(r, 'display'), unit: oneOf(r, 'unit', ['RUPEES', 'PERCENT'] as const), value: readNumber(r, 'value') }
  })
  const reasons: AiReason[] = asArray(o.reasons, 'reasons').map((x) => {
    const r = asObject(x, 'reason')
    return {
      factorKey: readString(r, 'factorKey'),
      factor: readString(r, 'factor'),
      amountDisplay: readString(r, 'amountDisplay'),
      impact: oneOf(r, 'impact', AI_IMPACTS),
      explanation: readString(r, 'explanation'),
    }
  })
  if (dataStatus === 'COMPLETE' && facts.length === 0) throw new ApiError('invalid-response', 'A complete explanation must carry its figures')

  return {
    loanId: readString(o, 'loanId'),
    reportingPeriod: readString(o, 'reportingPeriod'),
    source: source === 'AI' ? 'llm' : 'fallback',
    fallbackReason: fallbackRaw as AiFallbackReason | undefined,
    provider: readOptionalText(o, 'provider'),
    model: readOptionalText(o, 'model'),
    dataStatus: dataStatus === 'COMPLETE' ? 'complete' : 'insufficient',
    summary: readString(o, 'summary'),
    facts,
    reasons,
    recommendations: readStringArray(o, 'recommendations'),
    limitations: readStringArray(o, 'limitations'),
    missingInputs: readStringArray(o, 'missingInputs'),
    generatedAt: readString(o, 'generatedAt'),
  }
}

export function mapAiStatus(data: unknown): AiStatus {
  const o = asObject(data, 'ai status')
  return {
    providerConfigured: readBoolean(o, 'providerConfigured'),
    provider: readOptionalText(o, 'provider'),
    model: readOptionalText(o, 'model'),
    mode: oneOf(o, 'mode', ['AI', 'FALLBACK_ONLY'] as const),
    message: readString(o, 'message'),
  }
}
