/**
 * MOCK BACKEND (demonstration mode).
 *
 * Plays the role of the Spring Boot API: it owns an in-memory loan store and answers with the
 * SAME JSON wire format described in docs/API_CONTRACT.md (rupees, DTO field names). The service
 * layer then validates and maps these responses exactly as it would for live responses, so mock
 * mode exercises the real frontend code path. Data lives only for the lifetime of the page.
 *
 * Financial figures come from utils/profitability.ts. In live mode that responsibility belongs
 * to the Spring Boot service.
 */
import { env } from '../../config/env'
import { INITIAL_LOANS } from '../../data/mockData'
import type {
  AiChatRequestDto,
  AiChatResponseDto,
  DashboardSummaryDto,
  LoanDetailDto,
  LoanInputDto,
  LoanSummaryDto,
  ProfitabilityDto,
  RiskDto,
} from '../../types/api'
import { LOAN_TYPES, type Loan, type LoanRow, type LoanType, type ReportingPeriod } from '../../types/loan'
import { buildLoan, nextCustomerId, nextLoanId } from '../../utils/loanFactory'
import {
  FISCAL_MONTHS,
  REPORTING_PERIODS,
  attentionReasons,
  buildLoanRows,
  buildMonthlyTrend,
  computeExpectedCreditLoss,
  computeLoanFinancials,
  summarisePortfolio,
} from '../../utils/profitability'
import { ApiError } from '../apiError'
import { answerDemoQuestion } from './mockAi'

let store: Loan[] = structuredClone(INITIAL_LOANS)

const delay = (ms = env.mockLatencyMs) => new Promise<void>((resolve) => setTimeout(resolve, ms))
const r = (paise: number): number => paise / 100

function resolvePeriod(id: string | undefined): ReportingPeriod {
  const period = REPORTING_PERIODS.find((p) => p.id === id)
  if (!period) throw new ApiError('validation', 'Unknown reporting period', { status: 400, serverMessage: 'Unknown reporting period.' })
  return period
}

function findLoan(id: string): Loan {
  const loan = store.find((l) => l.id === id)
  if (!loan) throw new ApiError('not-found', `Loan ${id} not found`, { status: 404 })
  return loan
}

function toSummaryDto({ loan, financials: f }: LoanRow): LoanSummaryDto {
  return {
    loanId: loan.id,
    customerId: loan.customerId,
    customerName: loan.customerName,
    loanType: loan.loanType,
    originalAmount: r(loan.originalAmount),
    outstandingBalance: r(loan.outstandingBalance),
    interestRate: loan.interestRate,
    durationMonths: loan.durationMonths,
    startDate: loan.startDate,
    status: loan.status,
    riskRating: loan.riskRating,
    totalIncome: r(f.totalIncome),
    totalExpenses: r(f.totalExpenses),
    contributionBeforeEcl: r(f.contributionBeforeEcl),
    expectedCreditLoss: r(f.expectedCreditLoss),
    estimatedContribution: r(f.contributionAfterEcl),
    contributionStatus: f.contributionStatus,
  }
}

/* ---------------- Loans ---------------- */

export async function listLoans(periodId: string): Promise<LoanSummaryDto[]> {
  await delay()
  const period = resolvePeriod(periodId)
  return buildLoanRows(store, period).map(toSummaryDto)
}

function toDetailDto(loan: Loan, period: ReportingPeriod): LoanDetailDto {
  const row: LoanRow = { loan, financials: computeLoanFinancials(loan, period) }
  const support = loan.baseline.activities.find((a) => a.activity === 'Customer Support')
  return {
    ...toSummaryDto(row),
    reportingInputs: {
      interestIncome: r(loan.baseline.interestIncome),
      feeIncome: r(loan.baseline.feeIncome),
      averageOutstandingBalance: r(loan.baseline.averageOutstandingBalance),
      fundingRatePercent: loan.baseline.fundingRate,
      supportContacts: support?.driverUnits ?? 0,
      probabilityOfDefaultPercent: loan.credit.pd,
      lossGivenDefaultPercent: loan.credit.lgd,
    },
  }
}

export async function getLoan(loanId: string, periodId: string): Promise<LoanDetailDto> {
  await delay()
  return toDetailDto(findLoan(loanId), resolvePeriod(periodId))
}

/** Server-side validation, mirroring what a Spring @Valid DTO would reject. */
function validateInput(dto: LoanInputDto): void {
  const fieldErrors: Record<string, string> = {}
  if (!dto.customerName || dto.customerName.trim().length < 2) fieldErrors.customerName = 'Customer name is required.'
  if (!(LOAN_TYPES as readonly string[]).includes(dto.loanType)) fieldErrors.loanType = 'Unsupported loan type.'
  if (!(dto.originalAmount > 0)) fieldErrors.originalAmount = 'Principal amount must be greater than zero.'
  if (!(dto.outstandingBalance > 0)) fieldErrors.outstandingBalance = 'Outstanding balance must be greater than zero.'
  else if (dto.outstandingBalance > dto.originalAmount) fieldErrors.outstandingBalance = 'Cannot exceed the principal amount.'
  if (!(dto.interestRate > 0 && dto.interestRate <= 60)) fieldErrors.interestRate = 'Interest rate must be between 0 and 60.'
  if (!Number.isInteger(dto.durationMonths) || dto.durationMonths < 1) fieldErrors.durationMonths = 'Duration must be at least 1 month.'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dto.startDate)) fieldErrors.startDate = 'Start date must be YYYY-MM-DD.'
  if (!(dto.probabilityOfDefaultPercent >= 0 && dto.probabilityOfDefaultPercent <= 100)) fieldErrors.probabilityOfDefaultPercent = 'PD must be between 0 and 100.'
  if (!(dto.lossGivenDefaultPercent >= 0 && dto.lossGivenDefaultPercent <= 100)) fieldErrors.lossGivenDefaultPercent = 'LGD must be between 0 and 100.'
  if (Object.keys(fieldErrors).length > 0) {
    throw new ApiError('validation', 'Validation failed', { status: 400, fieldErrors, serverMessage: 'Please correct the highlighted fields.' })
  }
}

const rupeesToPaise = (n: number) => Math.round(n * 100)

function loanFromDto(dto: LoanInputDto, id: string, customerId: string, status?: Loan['status']): Loan {
  return buildLoan({
    id,
    customerId,
    customerName: dto.customerName.trim(),
    loanType: dto.loanType as LoanType,
    originalAmount: rupeesToPaise(dto.originalAmount),
    outstandingBalance: rupeesToPaise(dto.outstandingBalance),
    interestRate: dto.interestRate,
    durationMonths: dto.durationMonths,
    startDate: dto.startDate,
    status,
    interestIncome: rupeesToPaise(dto.interestIncome),
    feeIncome: rupeesToPaise(dto.feeIncome),
    fundingRate: dto.fundingRatePercent,
    supportContacts: dto.supportContacts,
    pd: dto.probabilityOfDefaultPercent,
    lgd: dto.lossGivenDefaultPercent,
  })
}

export async function createLoan(dto: LoanInputDto, periodId = '12m'): Promise<LoanDetailDto> {
  await delay()
  validateInput(dto)
  const loan = loanFromDto(dto, nextLoanId(store), dto.customerId?.trim().toUpperCase() || nextCustomerId(store))
  store = [...store, loan]
  return toDetailDto(loan, resolvePeriod(periodId))
}

export async function updateLoan(loanId: string, dto: LoanInputDto, periodId = '12m'): Promise<LoanDetailDto> {
  await delay()
  const existing = findLoan(loanId)
  validateInput(dto)
  const updated = loanFromDto(dto, existing.id, existing.customerId, existing.status)
  store = store.map((l) => (l.id === loanId ? updated : l))
  return toDetailDto(updated, resolvePeriod(periodId))
}

/* ---------------- Profitability & risk ---------------- */

export async function getProfitability(loanId: string, periodId: string): Promise<ProfitabilityDto> {
  await delay()
  const loan = findLoan(loanId)
  const f = computeLoanFinancials(loan, resolvePeriod(periodId))
  return {
    loanId: loan.id,
    reportingPeriod: f.period.id,
    months: f.months,
    interestIncome: r(f.interestIncome),
    feeIncome: r(f.feeIncome),
    totalIncome: r(f.totalIncome),
    fundingCost: r(f.fundingCost),
    operationalCost: r(f.operationalCost),
    expectedCreditLoss: r(f.expectedCreditLoss),
    totalExpenses: r(f.totalExpenses),
    contributionBeforeEcl: r(f.contributionBeforeEcl),
    estimatedContribution: r(f.contributionAfterEcl),
    contributionStatus: f.contributionStatus,
    funding: {
      averageOutstandingBalance: r(f.funding.averageOutstandingBalance ?? 0),
      fundingRatePercent: f.funding.fundingRatePct,
      timeInYears: f.funding.timeInYears,
    },
    operationalActivities: f.activities.map((a) => ({
      activity: a.activity,
      costDriver: a.driver,
      driverUnits: a.driverUnits ?? 0,
      costPerUnit: r(a.ratePerUnit ?? 0),
      allocatedCost: r(a.allocatedCost),
    })),
    creditRisk: {
      probabilityOfDefaultPercent: f.credit.pdPct,
      lossGivenDefaultPercent: f.credit.lgdPct,
      exposureAtDefault: r(f.credit.ead),
      expectedCreditLoss12Month: r(f.credit.eclTwelveMonth),
      expectedCreditLossForPeriod: r(f.credit.eclForPeriod),
    },
  }
}

export async function getRisk(loanId: string): Promise<RiskDto> {
  await delay()
  const loan = findLoan(loanId)
  return {
    loanId: loan.id,
    riskRating: loan.riskRating,
    probabilityOfDefaultPercent: loan.credit.pd,
    lossGivenDefaultPercent: loan.credit.lgd,
    exposureAtDefault: r(loan.credit.ead),
    expectedCreditLoss12Month: r(computeExpectedCreditLoss(loan.credit.ead, loan.credit.pd, loan.credit.lgd)),
  }
}

/* ---------------- Dashboard ---------------- */

export async function getDashboardSummary(periodId: string): Promise<DashboardSummaryDto> {
  await delay()
  const period = resolvePeriod(periodId)
  const rows = buildLoanRows(store, period)
  const s = summarisePortfolio(rows)
  const attention = rows
    .filter((row) => attentionReasons(row).length > 0)
    .sort((a, b) => a.financials.contributionAfterEcl - b.financials.contributionAfterEcl)
    .map((row) => ({ ...toSummaryDto(row), reasons: attentionReasons(row) }))
  const trend = buildMonthlyTrend(store).slice(period.startMonth, period.endMonth)

  return {
    reportingPeriod: period.id,
    totalActiveLoans: s.totalLoans,
    totalOutstandingAmount: r(s.totalOutstanding),
    totalIncome: r(s.totalIncome),
    totalExpenses: r(s.totalExpenses),
    contributionBeforeEcl: r(s.contributionBeforeEcl),
    expectedCreditLoss: r(s.expectedCreditLoss),
    estimatedContribution: r(s.contributionAfterEcl),
    positiveContributionLoans: s.positiveCount,
    negativeContributionLoans: s.negativeCount,
    breakEvenLoans: s.breakEvenCount,
    underWatchLoans: s.underWatchCount,
    highRiskLoans: s.highRiskCount,
    monthlyTrend: trend.map((t) => ({
      month: FISCAL_MONTHS[t.monthIndex],
      contributionBeforeEcl: r(t.contributionBeforeEcl),
      expectedCreditLoss: r(t.expectedCreditLoss),
      estimatedContribution: r(t.contributionAfterEcl),
    })),
    attentionLoans: attention,
  }
}

/* ---------------- AI (demonstration responder) ---------------- */

export async function chat(req: AiChatRequestDto): Promise<AiChatResponseDto> {
  await delay(750 + Math.round(Math.random() * 450))
  if (!req.question || req.question.trim().length === 0) {
    throw new ApiError('validation', 'Empty question', { status: 400, serverMessage: 'Please enter a question.' })
  }
  return answerDemoQuestion(req, store, resolvePeriod(req.reportingPeriod))
}
