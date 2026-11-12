import type { LoanType, OperationalActivityInput, Paise } from '../types/loan'

/** Convert whole/decimal rupees to integer paise. */
export const rupees = (amount: number): Paise => Math.round(amount * 100)

/**
 * DEMONSTRATION Activity-Based Costing rates per loan type.
 * These are assumptions for the prototype, not real bank cost data.
 */
interface ActivityRates {
  /** Cost of processing one loan application. */
  processing: Paise
  /** Cost per customer-service contact. */
  perContact: Paise
  /** Servicing cost per ₹1,000 of average outstanding balance per year. */
  servicingPerThousand: Paise
}

export const ACTIVITY_RATES: Record<LoanType, ActivityRates> = {
  'Personal Loan': { processing: rupees(500), perContact: rupees(50), servicingPerThousand: rupees(12.7) },
  'Home Loan': { processing: rupees(2500), perContact: rupees(50), servicingPerThousand: rupees(2) },
  'Auto Loan': { processing: rupees(800), perContact: rupees(50), servicingPerThousand: rupees(6) },
  'Business Loan': { processing: rupees(5000), perContact: rupees(100), servicingPerThousand: rupees(4) },
  'Education Loan': { processing: rupees(600), perContact: rupees(50), servicingPerThousand: rupees(3.5) },
  'Gold Loan': { processing: rupees(400), perContact: rupees(50), servicingPerThousand: rupees(3) },
}

export const ALLOCATION_METHOD =
  'Activity-Based Costing (ABC): each activity pool is charged to the loan as driver units consumed × cost per driver unit.'

/** Build the three ABC activity lines for a loan (12-month base period). */
export function buildOperationalActivities(
  loanType: LoanType,
  averageOutstandingBalance: Paise,
  supportContacts: number,
): OperationalActivityInput[] {
  const rates = ACTIVITY_RATES[loanType]
  return [
    {
      activity: 'Loan Processing',
      driver: 'Loan applications processed',
      driverUnits: 1,
      ratePerUnit: rates.processing,
    },
    {
      activity: 'Customer Support',
      driver: 'Customer service contacts',
      driverUnits: supportContacts,
      ratePerUnit: rates.perContact,
    },
    {
      activity: 'Loan Servicing',
      driver: '₹1,000 of average outstanding balance',
      driverUnits: averageOutstandingBalance / rupees(1000),
      ratePerUnit: rates.servicingPerThousand,
    },
  ]
}
