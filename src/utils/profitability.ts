/**
 * Centralised profitability logic for LoanLens.
 *
 * Simplified, educational model (NOT a complete bank accounting or regulatory calculation):
 *
 *   Total Income         = Interest Income + Fee Income
 *   Total Expenses       = Funding Cost + Operational Cost + Expected Credit Loss
 *   Estimated Contribution = Total Income - Total Expenses
 *
 *   Funding Cost  = Average Outstanding Balance x Funding Rate x Time (years)
 *   ECL           = PD x LGD x EAD
 *   Operational   = sum over activities of (driver units x cost per driver unit)  [Activity-Based Costing]
 *
 * All amounts are integer paise. Multiplications that could introduce floating-point error are
 * done on integers (BigInt for ECL) and rounded half-up to the nearest paise exactly once.
 */
import type {
  ActivityCost,
  ContributionStatus,
  Loan,
  LoanFinancials,
  LoanRow,
  MonthlyTrendPoint,
  Paise,
  PeriodId,
  PortfolioSummary,
  ReportingPeriod,
  RiskRating,
} from '../types/loan'

/* ------------------------------------------------------------------ */
/* Reporting periods (all within the demo fiscal year Apr 2025–Mar 2026) */
/* ------------------------------------------------------------------ */

export const FISCAL_MONTHS = [
  'Apr 2025',
  'May 2025',
  'Jun 2025',
  'Jul 2025',
  'Aug 2025',
  'Sep 2025',
  'Oct 2025',
  'Nov 2025',
  'Dec 2025',
  'Jan 2026',
  'Feb 2026',
  'Mar 2026',
] as const

export const REPORTING_PERIODS: readonly ReportingPeriod[] = [
  { id: '12m', label: 'Last 12 months', startMonth: 0, endMonth: 12 },
  { id: '6m', label: 'Last 6 months', startMonth: 6, endMonth: 12 },
  { id: '3m', label: 'Last 3 months', startMonth: 9, endMonth: 12 },
]

export const DEFAULT_PERIOD: ReportingPeriod = REPORTING_PERIODS[0]

const YEAR_PATTERN = /^\d{4}$/

export function isYearPeriod(id: string): boolean {
  return YEAR_PATTERN.test(id)
}

/** Backend mode: a reporting period is one full year, identified by the year ("2026"). */
export function yearPeriod(year: string): ReportingPeriod {
  return { id: year, label: `Year ${year}`, startMonth: 0, endMonth: 12 }
}

export function getPeriod(id: PeriodId): ReportingPeriod {
  return REPORTING_PERIODS.find((p) => p.id === id) ?? (isYearPeriod(id) ? yearPeriod(id) : DEFAULT_PERIOD)
}

export function periodMonths(period: ReportingPeriod): number {
  return period.endMonth - period.startMonth
}

/** e.g. "Apr 2025 – Mar 2026" */
export function periodRangeLabel(period: ReportingPeriod): string {
  if (isYearPeriod(period.id)) return `Year ${period.id}`
  return `${FISCAL_MONTHS[period.startMonth]} – ${FISCAL_MONTHS[period.endMonth - 1]}`
}

/** e.g. "One year" / "6 months" */
export function periodLengthLabel(period: ReportingPeriod): string {
  const months = periodMonths(period)
  return months === 12 ? 'One year (12 months)' : `${months} months`
}

/* ------------------------------------------------------------------ */
/* Month allocation of the 12-month base figures                       */
/* ------------------------------------------------------------------ */

// Relative monthly weights used to spread the 12-month demonstration figures over months.
// Interest accrues smoothly, fee income peaks with disbursement seasonality and ECL is
// provisioned unevenly. Only the shape matters; values are normalised by their sum.
const INTEREST_WEIGHTS = [78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 87, 88] as const
const FEE_WEIGHTS = [60, 70, 75, 80, 85, 90, 95, 100, 95, 90, 80, 80] as const
const ECL_WEIGHTS = [50, 60, 70, 90, 70, 80, 100, 80, 80, 110, 100, 110] as const
const UNIFORM_WEIGHTS = [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1] as const

/**
 * Allocate `total` paise to the months [start, end) using cumulative rounding, so that
 * any contiguous set of months sums exactly to the allocation of their union and the
 * full 12 months always sum exactly to `total`.
 */
function allocate(total: Paise, weights: readonly number[], start: number, end: number): Paise {
  const weightSum = weights.reduce((a, b) => a + b, 0)
  const cumulative = (months: number): Paise => {
    const w = weights.slice(0, months).reduce((a, b) => a + b, 0)
    return Math.round((total * w) / weightSum)
  }
  return cumulative(end) - cumulative(start)
}

/* ------------------------------------------------------------------ */
/* Core formulas                                                       */
/* ------------------------------------------------------------------ */

const pctToBasisPoints = (pct: number): number => Math.round(pct * 100)

/** ECL = PD x LGD x EAD, computed with BigInt basis-point arithmetic and rounded half-up to a paisa. */
export function computeExpectedCreditLoss(ead: Paise, pdPct: number, lgdPct: number): Paise {
  const numerator = BigInt(Math.round(ead)) * BigInt(pctToBasisPoints(pdPct)) * BigInt(pctToBasisPoints(lgdPct))
  const denominator = 100_000_000n // 10,000 x 10,000
  return Number((numerator + denominator / 2n) / denominator)
}

/**
 * Funding Cost = Average Outstanding Balance x Funding Rate x Time.
 * Time is the fraction of a year covered by months [start, end).
 */
export function computeFundingCost(
  averageOutstandingBalance: Paise,
  fundingRatePct: number,
  startMonth: number,
  endMonth: number,
): Paise {
  const rateBp = pctToBasisPoints(fundingRatePct)
  const cumulative = (months: number): Paise =>
    Math.round((averageOutstandingBalance * rateBp * months) / (10_000 * 12))
  return cumulative(endMonth) - cumulative(startMonth)
}

export function classifyContribution(contribution: Paise): ContributionStatus {
  if (Math.abs(contribution) <= BREAK_EVEN_THRESHOLD) return 'break-even'
  return contribution > 0 ? 'positive' : 'negative'
}

/** Loans within ±₹500 of zero are shown as break-even. */
export const BREAK_EVEN_THRESHOLD: Paise = 50_000

/** Demo rule linking a supplied PD to a rating band: <3% Low, 3–<8% Medium, >=8% High. */
export function deriveRiskRating(pdPct: number): RiskRating {
  if (pdPct < 3) return 'Low'
  if (pdPct < 8) return 'Medium'
  return 'High'
}

/** Annual allocated cost of one activity line (driver units x cost per unit). */
export function activityAnnualCost(units: number, ratePerUnit: Paise): Paise {
  return Math.round(units * ratePerUnit)
}

/* ------------------------------------------------------------------ */
/* Per-loan financials                                                 */
/* ------------------------------------------------------------------ */

export function computeLoanFinancials(loan: Loan, period: ReportingPeriod): LoanFinancials {
  const { startMonth, endMonth } = period
  const months = endMonth - startMonth
  const base = loan.baseline

  const interestIncome = allocate(base.interestIncome, INTEREST_WEIGHTS, startMonth, endMonth)
  const feeIncome = allocate(base.feeIncome, FEE_WEIGHTS, startMonth, endMonth)
  const totalIncome = interestIncome + feeIncome

  const fundingCost = computeFundingCost(
    base.averageOutstandingBalance,
    base.fundingRate,
    startMonth,
    endMonth,
  )

  const activities: ActivityCost[] = base.activities.map((a) => ({
    ...a,
    allocatedCost: allocate(activityAnnualCost(a.driverUnits, a.ratePerUnit), UNIFORM_WEIGHTS, startMonth, endMonth),
  }))
  const operationalCost = activities.reduce((sum, a) => sum + a.allocatedCost, 0)

  const eclTwelveMonth = computeExpectedCreditLoss(loan.credit.ead, loan.credit.pd, loan.credit.lgd)
  const expectedCreditLoss = allocate(eclTwelveMonth, ECL_WEIGHTS, startMonth, endMonth)

  const totalExpenses = fundingCost + operationalCost + expectedCreditLoss
  const contributionBeforeEcl = totalIncome - fundingCost - operationalCost
  const contributionAfterEcl = totalIncome - totalExpenses

  return {
    period,
    months,
    interestIncome,
    feeIncome,
    totalIncome,
    fundingCost,
    operationalCost,
    expectedCreditLoss,
    totalExpenses,
    contributionBeforeEcl,
    contributionAfterEcl,
    contributionStatus: classifyContribution(contributionAfterEcl),
    funding: {
      averageOutstandingBalance: base.averageOutstandingBalance,
      fundingRatePct: base.fundingRate,
      months,
      timeInYears: months / 12,
      fundingCost,
    },
    activities,
    credit: {
      pdPct: loan.credit.pd,
      lgdPct: loan.credit.lgd,
      ead: loan.credit.ead,
      eclTwelveMonth,
      eclForPeriod: expectedCreditLoss,
    },
  }
}

export function buildLoanRows(loans: readonly Loan[], period: ReportingPeriod): LoanRow[] {
  return loans.map((loan) => ({ loan, financials: computeLoanFinancials(loan, period) }))
}

/* ------------------------------------------------------------------ */
/* Portfolio level                                                     */
/* ------------------------------------------------------------------ */

export function summarisePortfolio(rows: readonly LoanRow[]): PortfolioSummary {
  const sum = (pick: (r: LoanRow) => Paise) => rows.reduce((total, r) => total + pick(r), 0)
  return {
    totalLoans: rows.length,
    totalOutstanding: sum((r) => r.loan.outstandingBalance),
    totalIncome: sum((r) => r.financials.totalIncome),
    totalExpenses: sum((r) => r.financials.totalExpenses),
    contributionBeforeEcl: sum((r) => r.financials.contributionBeforeEcl),
    expectedCreditLoss: sum((r) => r.financials.expectedCreditLoss),
    contributionAfterEcl: sum((r) => r.financials.contributionAfterEcl),
    positiveCount: rows.filter((r) => r.financials.contributionStatus === 'positive').length,
    negativeCount: rows.filter((r) => r.financials.contributionStatus === 'negative').length,
    breakEvenCount: rows.filter((r) => r.financials.contributionStatus === 'break-even').length,
    underWatchCount: rows.filter((r) => r.loan.status === 'Under Watch').length,
    highRiskCount: rows.filter((r) => r.loan.riskRating === 'High').length,
  }
}

/** Month-by-month portfolio contribution for the whole 12-month fiscal year. */
export function buildMonthlyTrend(loans: readonly Loan[]): MonthlyTrendPoint[] {
  return FISCAL_MONTHS.map((label, monthIndex) => {
    const monthPeriod: ReportingPeriod = {
      id: '12m',
      label,
      startMonth: monthIndex,
      endMonth: monthIndex + 1,
    }
    let before = 0
    let ecl = 0
    let after = 0
    for (const loan of loans) {
      const f = computeLoanFinancials(loan, monthPeriod)
      before += f.contributionBeforeEcl
      ecl += f.expectedCreditLoss
      after += f.contributionAfterEcl
    }
    return {
      monthIndex,
      label,
      shortLabel: label.slice(0, 3),
      contributionBeforeEcl: before,
      expectedCreditLoss: ecl,
      contributionAfterEcl: after,
    }
  })
}

/** Why a loan is listed under "Loans Requiring Attention". Empty array = no attention needed. */
export function attentionReasons(row: LoanRow): string[] {
  const reasons: string[] = []
  if (row.financials.contributionStatus === 'negative') reasons.push('Negative estimated contribution')
  if (row.loan.riskRating === 'High') reasons.push('High risk rating')
  if (row.loan.status === 'Under Watch') reasons.push('Under watch')
  return reasons
}
