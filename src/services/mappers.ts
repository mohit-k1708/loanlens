import type { AiChatResponse, AiEvidence } from '../types/ai'
import {
  LOAN_TYPES,
  RISK_RATINGS,
  type AttentionLoan,
  type DashboardSummary,
  type LoanDetail,
  type LoanFinancials,
  type LoanFormValues,
  type LoanSummary,
  type MonthlyTrendPoint,
  type PeriodId,
  type RiskDetails,
} from '../types/loan'
import type { LoanInputDto } from '../types/api'
import { getPeriod, REPORTING_PERIODS } from '../utils/profitability'
import { ApiError } from './apiError'
import {
  asArray,
  asObject,
  readNumber,
  readOneOf,
  readOptionalPaise,
  readOptionalString,
  readPaise,
  readString,
  readStringArray,
  toRupees,
  type Obj,
} from './validators'

/** Convert and validate backend JSON (rupees) into UI view models (paise). */

const PERIOD_IDS = REPORTING_PERIODS.map((p) => p.id) as PeriodId[]
const CONTRIBUTION_STATUSES = ['positive', 'negative', 'break-even'] as const
const LOAN_STATUSES = ['Active', 'Under Watch'] as const

/** An amount equation that doesn't add up means the payload is corrupt; refuse to display it. */
function assertEquals(actual: number, expected: number, what: string): void {
  if (Math.abs(actual - expected) > 1) throw new ApiError('invalid-response', `Inconsistent figures: ${what}`)
}

function mapSummaryObject(o: Obj): LoanSummary {
  const totalIncome = readPaise(o, 'totalIncome')
  const totalExpenses = readPaise(o, 'totalExpenses')
  const contributionBeforeEcl = readPaise(o, 'contributionBeforeEcl')
  const expectedCreditLoss = readPaise(o, 'expectedCreditLoss')
  const estimatedContribution = readPaise(o, 'estimatedContribution')
  const summary: LoanSummary = {
    id: readString(o, 'loanId'),
    customerId: readString(o, 'customerId'),
    customerName: readString(o, 'customerName'),
    loanType: readOneOf(o, 'loanType', LOAN_TYPES),
    originalAmount: readPaise(o, 'originalAmount'),
    outstandingBalance: readPaise(o, 'outstandingBalance'),
    interestRate: readNumber(o, 'interestRate'),
    durationMonths: readNumber(o, 'durationMonths'),
    startDate: readString(o, 'startDate'),
    status: readOneOf(o, 'status', LOAN_STATUSES),
    riskRating: readOneOf(o, 'riskRating', RISK_RATINGS),
    totalIncome,
    totalExpenses,
    contributionBeforeEcl,
    expectedCreditLoss,
    estimatedContribution,
    contributionStatus: readOneOf(o, 'contributionStatus', CONTRIBUTION_STATUSES),
  }
  assertEquals(estimatedContribution, totalIncome - totalExpenses, `loan ${summary.id} contribution`)
  assertEquals(contributionBeforeEcl - expectedCreditLoss, estimatedContribution, `loan ${summary.id} ECL`)
  return summary
}

export function mapLoanSummary(data: unknown): LoanSummary {
  return mapSummaryObject(asObject(data, 'loan'))
}

export function mapLoanSummaryList(data: unknown): LoanSummary[] {
  return asArray(data, 'loan list').map(mapLoanSummary)
}

export function mapLoanDetail(data: unknown): LoanDetail {
  const o = asObject(data, 'loan')
  const inputs = asObject(o.reportingInputs, 'reportingInputs')
  return {
    ...mapSummaryObject(o),
    inputs: {
      interestIncome: readPaise(inputs, 'interestIncome'),
      feeIncome: readPaise(inputs, 'feeIncome'),
      averageOutstandingBalance: readPaise(inputs, 'averageOutstandingBalance'),
      fundingRate: readNumber(inputs, 'fundingRatePercent'),
      supportContacts: readNumber(inputs, 'supportContacts'),
      pd: readNumber(inputs, 'probabilityOfDefaultPercent'),
      lgd: readNumber(inputs, 'lossGivenDefaultPercent'),
    },
  }
}

export function mapProfitability(data: unknown): LoanFinancials {
  const o = asObject(data, 'profitability')
  const period = getPeriod(readOneOf(o, 'reportingPeriod', PERIOD_IDS))
  const funding = asObject(o.funding, 'funding')
  const credit = asObject(o.creditRisk, 'creditRisk')
  const months = readNumber(o, 'months')

  const f: LoanFinancials = {
    period,
    months,
    interestIncome: readPaise(o, 'interestIncome'),
    feeIncome: readPaise(o, 'feeIncome'),
    totalIncome: readPaise(o, 'totalIncome'),
    fundingCost: readPaise(o, 'fundingCost'),
    operationalCost: readPaise(o, 'operationalCost'),
    expectedCreditLoss: readPaise(o, 'expectedCreditLoss'),
    totalExpenses: readPaise(o, 'totalExpenses'),
    contributionBeforeEcl: readPaise(o, 'contributionBeforeEcl'),
    contributionAfterEcl: readPaise(o, 'estimatedContribution'),
    contributionStatus: readOneOf(o, 'contributionStatus', CONTRIBUTION_STATUSES),
    funding: {
      averageOutstandingBalance: readPaise(funding, 'averageOutstandingBalance'),
      fundingRatePct: readNumber(funding, 'fundingRatePercent'),
      months,
      timeInYears: readNumber(funding, 'timeInYears'),
      fundingCost: readPaise(o, 'fundingCost'),
    },
    activities: asArray(o.operationalActivities, 'operationalActivities').map((item) => {
      const a = asObject(item, 'operational activity')
      return {
        activity: readString(a, 'activity'),
        driver: readString(a, 'costDriver'),
        driverUnits: readNumber(a, 'driverUnits'),
        ratePerUnit: readPaise(a, 'costPerUnit'),
        allocatedCost: readPaise(a, 'allocatedCost'),
      }
    }),
    credit: {
      pdPct: readNumber(credit, 'probabilityOfDefaultPercent'),
      lgdPct: readNumber(credit, 'lossGivenDefaultPercent'),
      ead: readPaise(credit, 'exposureAtDefault'),
      eclTwelveMonth: readPaise(credit, 'expectedCreditLoss12Month'),
      eclForPeriod: readPaise(credit, 'expectedCreditLossForPeriod'),
    },
  }

  assertEquals(f.totalIncome, f.interestIncome + f.feeIncome, 'total income')
  assertEquals(f.totalExpenses, f.fundingCost + f.operationalCost + f.expectedCreditLoss, 'total expenses')
  assertEquals(f.contributionAfterEcl, f.totalIncome - f.totalExpenses, 'estimated contribution')
  assertEquals(f.contributionBeforeEcl, f.totalIncome - f.fundingCost - f.operationalCost, 'contribution before ECL')
  assertEquals(f.operationalCost, f.activities.reduce((s, a) => s + a.allocatedCost, 0), 'operational cost allocation')
  assertEquals(f.credit.eclForPeriod, f.expectedCreditLoss, 'period ECL')
  return f
}

export function mapRisk(data: unknown): RiskDetails {
  const o = asObject(data, 'risk')
  return {
    loanId: readString(o, 'loanId'),
    riskRating: readOneOf(o, 'riskRating', RISK_RATINGS),
    pdPct: readNumber(o, 'probabilityOfDefaultPercent'),
    lgdPct: readNumber(o, 'lossGivenDefaultPercent'),
    ead: readPaise(o, 'exposureAtDefault'),
    expectedCreditLoss12Month: readPaise(o, 'expectedCreditLoss12Month'),
  }
}

export function mapDashboard(data: unknown): DashboardSummary {
  const o = asObject(data, 'dashboard')
  const period = getPeriod(readOneOf(o, 'reportingPeriod', PERIOD_IDS))
  const trend: MonthlyTrendPoint[] = asArray(o.monthlyTrend, 'monthlyTrend').map((item, i) => {
    const t = asObject(item, 'trend point')
    const label = readString(t, 'month')
    return {
      monthIndex: i,
      label,
      shortLabel: label.slice(0, 3),
      contributionBeforeEcl: readPaise(t, 'contributionBeforeEcl'),
      expectedCreditLoss: readPaise(t, 'expectedCreditLoss'),
      contributionAfterEcl: readPaise(t, 'estimatedContribution'),
    }
  })
  const attentionLoans: AttentionLoan[] = asArray(o.attentionLoans, 'attentionLoans').map((item) => {
    const a = asObject(item, 'attention loan')
    return { ...mapSummaryObject(a), reasons: readStringArray(a, 'reasons') }
  })

  const portfolio = {
    totalLoans: readNumber(o, 'totalActiveLoans'),
    totalOutstanding: readPaise(o, 'totalOutstandingAmount'),
    totalIncome: readPaise(o, 'totalIncome'),
    totalExpenses: readPaise(o, 'totalExpenses'),
    contributionBeforeEcl: readPaise(o, 'contributionBeforeEcl'),
    expectedCreditLoss: readPaise(o, 'expectedCreditLoss'),
    contributionAfterEcl: readPaise(o, 'estimatedContribution'),
    positiveCount: readNumber(o, 'positiveContributionLoans'),
    negativeCount: readNumber(o, 'negativeContributionLoans'),
    breakEvenCount: readNumber(o, 'breakEvenLoans'),
    underWatchCount: readNumber(o, 'underWatchLoans'),
    highRiskCount: readNumber(o, 'highRiskLoans'),
  }
  assertEquals(portfolio.contributionAfterEcl, portfolio.totalIncome - portfolio.totalExpenses, 'portfolio contribution')
  if (portfolio.positiveCount + portfolio.negativeCount + portfolio.breakEvenCount !== portfolio.totalLoans) {
    throw new ApiError('invalid-response', 'Inconsistent figures: loan counts')
  }
  return { period, portfolio, trend, attentionLoans }
}

/* ---------------- AI ---------------- */

const AI_TONES = ['income', 'expense', 'estimate', 'total', 'neutral'] as const
const AI_SOURCES = ['demo-scripted', 'llm'] as const

function mapEvidence(data: unknown): AiEvidence {
  const o = asObject(data, 'evidence')
  return {
    title: readString(o, 'title'),
    loanId: readOptionalString(o, 'loanId'),
    periodLabel: readString(o, 'periodLabel'),
    items: asArray(o.items, 'evidence items').map((item) => {
      const i = asObject(item, 'evidence item')
      return {
        label: readString(i, 'label'),
        amount: readOptionalPaise(i, 'amount'),
        text: readOptionalString(i, 'text'),
        tone: readOneOf(i, 'tone', AI_TONES),
        loanId: readOptionalString(i, 'loanId'),
      }
    }),
  }
}

export function mapAiChat(data: unknown): AiChatResponse {
  const o = asObject(data, 'ai response')
  return {
    answer: readString(o, 'answer'),
    source: readOneOf(o, 'source', AI_SOURCES),
    model: readOptionalString(o, 'model'),
    evidence: o.evidence === undefined ? [] : asArray(o.evidence, 'evidence').map(mapEvidence),
    relatedLoanIds: readStringArray(o, 'relatedLoanIds'),
    suggestions: readStringArray(o, 'suggestions'),
  }
}

/* ---------------- Requests (view model -> wire) ---------------- */

export function toLoanInputDto(values: LoanFormValues): LoanInputDto {
  return {
    customerName: values.customerName,
    customerId: values.customerId || undefined,
    loanType: values.loanType,
    originalAmount: toRupees(values.originalAmount),
    outstandingBalance: toRupees(values.outstandingBalance),
    interestRate: values.interestRate,
    durationMonths: values.durationMonths,
    startDate: values.startDate,
    interestIncome: toRupees(values.interestIncome),
    feeIncome: toRupees(values.feeIncome),
    fundingRatePercent: values.fundingRate,
    supportContacts: values.supportContacts,
    probabilityOfDefaultPercent: values.pd,
    lossGivenDefaultPercent: values.lgd,
  }
}
