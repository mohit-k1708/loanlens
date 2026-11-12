import { buildOperationalActivities } from '../data/costModel'
import type { Loan, LoanStatus, LoanType, Paise } from '../types/loan'
import { deriveRiskRating } from './profitability'

/** Everything needed to create a Loan record. Money in paise, rates in percent. */
export interface LoanInput {
  id: string
  customerId: string
  customerName: string
  loanType: LoanType
  originalAmount: Paise
  outstandingBalance: Paise
  interestRate: number
  durationMonths: number
  startDate: string
  status?: LoanStatus
  /** Supplied reporting-period interest income. */
  interestIncome: Paise
  /** Supplied reporting-period fee income. */
  feeIncome: Paise
  /** Defaults to the outstanding balance when not supplied. */
  averageOutstandingBalance?: Paise
  fundingRate: number
  /** Customer-service contacts in the period (ABC cost driver). */
  supportContacts: number
  pd: number
  lgd: number
}

/** Create a Loan. Risk rating is derived from PD; EAD is taken as the outstanding balance. */
export function buildLoan(input: LoanInput): Loan {
  const averageOutstandingBalance = input.averageOutstandingBalance ?? input.outstandingBalance
  return {
    id: input.id,
    customerId: input.customerId,
    customerName: input.customerName,
    loanType: input.loanType,
    originalAmount: input.originalAmount,
    outstandingBalance: input.outstandingBalance,
    interestRate: input.interestRate,
    durationMonths: input.durationMonths,
    startDate: input.startDate,
    status: input.status ?? 'Active',
    riskRating: deriveRiskRating(input.pd),
    baseline: {
      interestIncome: input.interestIncome,
      feeIncome: input.feeIncome,
      averageOutstandingBalance,
      fundingRate: input.fundingRate,
      activities: buildOperationalActivities(input.loanType, averageOutstandingBalance, input.supportContacts),
    },
    credit: {
      pd: input.pd,
      lgd: input.lgd,
      ead: input.outstandingBalance,
    },
  }
}

/** Next sequential loan ID, e.g. L1013. */
export function nextLoanId(loans: readonly Loan[]): string {
  const max = loans.reduce((highest, l) => {
    const n = Number(l.id.replace(/\D/g, ''))
    return Number.isFinite(n) ? Math.max(highest, n) : highest
  }, 1000)
  return `L${max + 1}`
}

/** Next sequential customer ID, e.g. C2013. */
export function nextCustomerId(loans: readonly Loan[]): string {
  const max = loans.reduce((highest, l) => {
    const n = Number(l.customerId.replace(/\D/g, ''))
    return Number.isFinite(n) ? Math.max(highest, n) : highest
  }, 2000)
  return `C${max + 1}`
}
