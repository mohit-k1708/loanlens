/**
 * Domain types for LoanLens.
 *
 * MONEY RULE: every monetary amount is an integer number of paise (1 INR = 100 paise).
 * Percentages (interest rate, funding rate, PD, LGD) are plain percent numbers, e.g. 10 = 10%.
 */

/** Integer paise. Never store fractional paise. */
export type Paise = number

export const LOAN_TYPES = [
  'Personal Loan',
  'Home Loan',
  'Auto Loan',
  'Business Loan',
  'Education Loan',
  'Gold Loan',
] as const
export type LoanType = (typeof LOAN_TYPES)[number]

export const RISK_RATINGS = ['Low', 'Medium', 'High'] as const
export type RiskRating = (typeof RISK_RATINGS)[number]

export type LoanStatus = 'Active' | 'Under Watch' | 'Closed' | 'Defaulted'

export type ContributionStatus = 'positive' | 'negative' | 'break-even'

/** Mock mode: '12m' | '6m' | '3m'.  Backend mode: a four-digit reporting year such as '2026'. */
export type PeriodId = string

/** One Activity-Based-Costing line as supplied for the 12-month base period. */
export interface OperationalActivityInput {
  activity: string
  /** What drives the cost of this activity, e.g. "Customer service contacts". */
  driver: string
  /** Number of driver units consumed in the 12-month base period. */
  driverUnits: number
  /** Cost per driver unit. */
  ratePerUnit: Paise
}

/**
 * Financial inputs for the 12-month base reporting period (Apr 2025 - Mar 2026).
 * In this prototype interest and fee income are SUPPLIED inputs, not derived from rate x principal.
 */
export interface BaselinePeriodInputs {
  interestIncome: Paise
  feeIncome: Paise
  averageOutstandingBalance: Paise
  /** Annual funding rate in percent. */
  fundingRate: number
  activities: OperationalActivityInput[]
}

/** Supplied credit-risk model inputs. ECL is derived: PD x LGD x EAD. */
export interface CreditRiskInputs {
  /** Probability of Default, percent. */
  pd: number
  /** Loss Given Default, percent. */
  lgd: number
  /** Exposure at Default. */
  ead: Paise
}

export interface Loan {
  id: string
  customerId: string
  customerName: string
  loanType: LoanType
  originalAmount: Paise
  outstandingBalance: Paise
  /** Customer interest rate, percent per annum. */
  interestRate: number
  durationMonths: number
  /** ISO date (YYYY-MM-DD). */
  startDate: string
  status: LoanStatus
  riskRating: RiskRating
  baseline: BaselinePeriodInputs
  credit: CreditRiskInputs
}

export interface ReportingPeriod {
  id: PeriodId
  label: string
  /** Inclusive month index into the fiscal year (0 = Apr 2025). */
  startMonth: number
  /** Exclusive month index. */
  endMonth: number
}

export interface ActivityCost {
  activity: string
  driver: string
  /** Not available from the backend, which records only the allocated amount per cost category. */
  driverUnits?: number
  ratePerUnit?: Paise
  /** Allocated cost for the selected reporting period. */
  allocatedCost: Paise
}

export interface FundingBreakdown {
  /** null when the average balance behind the funding cost was not recorded. */
  averageOutstandingBalance: Paise | null
  fundingRatePct: number
  months: number
  /** months / 12 */
  timeInYears: number
  fundingCost: Paise
}

export interface CreditBreakdown {
  pdPct: number
  lgdPct: number
  ead: Paise
  /** Full 12-month ECL = PD x LGD x EAD. */
  eclTwelveMonth: Paise
  /** ECL attributed to the selected reporting period (equals eclTwelveMonth for 12 months). */
  eclForPeriod: Paise
}

export interface LoanFinancials {
  period: ReportingPeriod
  months: number
  interestIncome: Paise
  feeIncome: Paise
  totalIncome: Paise
  fundingCost: Paise
  operationalCost: Paise
  expectedCreditLoss: Paise
  totalExpenses: Paise
  contributionBeforeEcl: Paise
  contributionAfterEcl: Paise
  contributionStatus: ContributionStatus
  funding: FundingBreakdown
  activities: ActivityCost[]
  credit: CreditBreakdown
  /** Backend explanations of the result (backend mode only). */
  notes?: string[]
  /** The stored calculation behind these figures (backend mode only). */
  snapshot?: { calculatedAt: string; version: string; newlyCreated: boolean }
}

export interface LoanRow {
  loan: Loan
  financials: LoanFinancials
}

export interface PortfolioSummary {
  totalLoans: number
  totalOutstanding: Paise
  totalIncome: Paise
  totalExpenses: Paise
  contributionBeforeEcl: Paise
  expectedCreditLoss: Paise
  contributionAfterEcl: Paise
  positiveCount: number
  negativeCount: number
  breakEvenCount: number
  underWatchCount: number
  highRiskCount: number
  /** Active loans with no financial record / risk assessment for the period (backend mode). Never counted as zero. */
  noDataCount?: number
}

export interface MonthlyTrendPoint {
  monthIndex: number
  label: string
  shortLabel: string
  contributionBeforeEcl: Paise
  expectedCreditLoss: Paise
  contributionAfterEcl: Paise
}

export interface ManagerProfile {
  name: string
  title: string
  email: string
  initials: string
}

/* ------------------------------------------------------------------ */
/* Phase 2 view models: what the UI receives from the service layer    */
/* (identical shape whether the data came from mock mode or Spring)    */
/* ------------------------------------------------------------------ */

/** One row of the loan list / dashboard attention table. */
export interface LoanSummary {
  id: string
  customerId: string
  customerName: string
  loanType: LoanType
  originalAmount: Paise
  outstandingBalance: Paise
  interestRate: number
  durationMonths: number
  startDate: string
  status: LoanStatus
  /** null = no risk assessment yet (backend mode). */
  riskRating: RiskRating | null
  /**
   * Figures for the selected reporting period. All null when the period has no financial record and/or risk
   * assessment (backend mode); `missingInputs` then says which. They are never shown as zero.
   */
  totalIncome: Paise | null
  totalExpenses: Paise | null
  contributionBeforeEcl: Paise | null
  expectedCreditLoss: Paise | null
  estimatedContribution: Paise | null
  contributionStatus: ContributionStatus | null
  missingInputs?: string[]
}

/** Supplied 12-month reporting inputs, editable through the loan form. */
export interface LoanReportingInputs {
  interestIncome: Paise
  feeIncome: Paise
  averageOutstandingBalance: Paise
  fundingRate: number
  supportContacts: number
  pd: number
  lgd: number
}

export interface LoanDetail extends LoanSummary {
  /** Mock mode only. In backend mode financial and risk inputs are separate records. */
  inputs?: LoanReportingInputs
  maturityDate?: string
}

/** Values collected by the Add / Edit loan form (money in paise, rates in percent). */
export interface LoanFormValues {
  customerName: string
  /** Blank on create = let the backend generate one. */
  customerId?: string
  loanType: LoanType
  originalAmount: Paise
  outstandingBalance: Paise
  interestRate: number
  durationMonths: number
  startDate: string
  interestIncome: Paise
  feeIncome: Paise
  fundingRate: number
  supportContacts: number
  pd: number
  lgd: number
}

export interface AttentionLoan
  extends Pick<
    LoanSummary,
    'id' | 'customerId' | 'customerName' | 'loanType' | 'outstandingBalance' | 'status' | 'riskRating' | 'estimatedContribution' | 'contributionStatus'
  > {
  reasons: string[]
}

export interface DashboardSummary {
  period: ReportingPeriod
  portfolio: PortfolioSummary
  trend: MonthlyTrendPoint[]
  attentionLoans: AttentionLoan[]
}

export interface RiskDetails {
  loanId: string
  riskRating: RiskRating
  pdPct: number
  lgdPct: number
  ead: Paise
  /** Expected credit loss as calculated by the data source (never by the UI). An ESTIMATE. */
  expectedCreditLoss12Month: Paise
  reportingPeriod?: string
  assessedAt?: string
}

/* ------------------------------------------------------------------ */
/* Backend-mode view models                                            */
/* ------------------------------------------------------------------ */

/** Search, filters and paging for the loan list. Filter values are 'all' or the display label. */
export interface LoanQuery {
  period: PeriodId
  search: string
  loanType: 'all' | LoanType
  risk: 'all' | RiskRating
  /** Contribution status filter. */
  contribution: 'all' | ContributionStatus
  loanStatus: 'all' | LoanStatus
  /** 1-based page number. */
  page: number
  size: number
}

export interface LoanPage {
  items: LoanSummary[]
  totalElements: number
  totalPages: number
  /** Total loans in the system matching nothing but the period (for "filtered from N"). Optional. */
  unfilteredTotal?: number
}

export interface CustomerOption {
  /** Backend database id (used when creating a loan). */
  id: number
  customerCode: string
  fullName: string
}

export const EMPLOYMENT_TYPES = ['SALARIED', 'SELF_EMPLOYED', 'BUSINESS_OWNER', 'PROFESSIONAL', 'RETIRED', 'OTHER'] as const
export type EmploymentType = (typeof EMPLOYMENT_TYPES)[number]

/** Add / Edit loan form in backend mode. Money in paise, rates in percent. */
export interface BackendLoanFormValues {
  loanCode: string
  /** Either an existing customer's backend id, or the details of a new customer to create first. */
  customer: { kind: 'existing'; id: number } | { kind: 'new'; customerCode: string; fullName: string; email: string; employmentType: EmploymentType; annualIncome: Paise }
  loanType: LoanType
  originalAmount: Paise
  outstandingBalance: Paise
  interestRate: number
  durationMonths: number
  startDate: string
  loanStatus: LoanStatus
}

/** One stored financial record for a loan and reporting period (backend mode). */
export interface FinancialRecordView {
  reportingPeriod: string
  interestIncome: Paise
  feeIncome: Paise
  totalIncome: Paise
  averageOutstandingBalance: Paise | null
  fundingRatePct: number
  fundingCost: Paise
  operationalCost: Paise
  updatedAt: string
}

export interface FinancialRecordFormValues {
  reportingPeriod: string
  interestIncome: Paise
  feeIncome: Paise
  averageOutstandingBalance: Paise | null
  fundingRate: number
  fundingCost: Paise
  operationalCost: Paise
}

export interface RiskAssessmentFormValues {
  reportingPeriod: string
  pd: number
  lgd: number
  ead: Paise
}
