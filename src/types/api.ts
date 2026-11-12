/**
 * Wire format (JSON) of the Spring Boot REST API. See docs/API_CONTRACT.md.
 * Money is a JSON number in RUPEES (BigDecimal, max 2 decimals); percentages are plain percent numbers.
 * The frontend converts to integer paise at the boundary (services/mappers.ts).
 */

export interface LoanSummaryDto {
  loanId: string
  customerId: string
  customerName: string
  loanType: string
  originalAmount: number
  outstandingBalance: number
  interestRate: number
  durationMonths: number
  startDate: string
  status: string
  riskRating: string
  totalIncome: number
  totalExpenses: number
  contributionBeforeEcl: number
  expectedCreditLoss: number
  estimatedContribution: number
  contributionStatus: string
}

export interface LoanDetailDto extends LoanSummaryDto {
  reportingInputs: {
    interestIncome: number
    feeIncome: number
    averageOutstandingBalance: number
    fundingRatePercent: number
    supportContacts: number
    probabilityOfDefaultPercent: number
    lossGivenDefaultPercent: number
  }
}

/** Request body for POST /api/loans and PUT /api/loans/{loanId}. */
export interface LoanInputDto {
  customerName: string
  customerId?: string
  loanType: string
  originalAmount: number
  outstandingBalance: number
  interestRate: number
  durationMonths: number
  startDate: string
  interestIncome: number
  feeIncome: number
  fundingRatePercent: number
  supportContacts: number
  probabilityOfDefaultPercent: number
  lossGivenDefaultPercent: number
}

export interface ProfitabilityDto {
  loanId: string
  reportingPeriod: string
  months: number
  interestIncome: number
  feeIncome: number
  totalIncome: number
  fundingCost: number
  operationalCost: number
  expectedCreditLoss: number
  totalExpenses: number
  contributionBeforeEcl: number
  estimatedContribution: number
  contributionStatus: string
  funding: {
    averageOutstandingBalance: number
    fundingRatePercent: number
    timeInYears: number
  }
  operationalActivities: {
    activity: string
    costDriver: string
    driverUnits: number
    costPerUnit: number
    allocatedCost: number
  }[]
  creditRisk: {
    probabilityOfDefaultPercent: number
    lossGivenDefaultPercent: number
    exposureAtDefault: number
    expectedCreditLoss12Month: number
    expectedCreditLossForPeriod: number
  }
}

export interface RiskDto {
  loanId: string
  riskRating: string
  probabilityOfDefaultPercent: number
  lossGivenDefaultPercent: number
  exposureAtDefault: number
  expectedCreditLoss12Month: number
}

export interface DashboardSummaryDto {
  reportingPeriod: string
  totalActiveLoans: number
  totalOutstandingAmount: number
  totalIncome: number
  totalExpenses: number
  contributionBeforeEcl: number
  expectedCreditLoss: number
  estimatedContribution: number
  positiveContributionLoans: number
  negativeContributionLoans: number
  breakEvenLoans: number
  underWatchLoans: number
  highRiskLoans: number
  monthlyTrend: {
    month: string
    contributionBeforeEcl: number
    expectedCreditLoss: number
    estimatedContribution: number
  }[]
  attentionLoans: (LoanSummaryDto & { reasons: string[] })[]
}

export interface AiChatRequestDto {
  loanId: string | null
  question: string
  reportingPeriod: string
  conversationId: string | null
}

export interface AiEvidenceDto {
  title: string
  loanId?: string
  periodLabel: string
  items: {
    label: string
    /** Signed rupees (costs negative). */
    amount?: number
    text?: string
    tone: string
    loanId?: string
  }[]
}

export interface AiChatResponseDto {
  answer: string
  source: string
  model?: string
  evidence: AiEvidenceDto[]
  relatedLoanIds?: string[]
  suggestions?: string[]
}

/** Error body the frontend understands (all fields optional; anything else is ignored). */
export interface ApiErrorBodyDto {
  message?: string
  fieldErrors?: Record<string, string>
}
