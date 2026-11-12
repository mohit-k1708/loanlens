import type { LoanQuery, PeriodId } from '../types/loan'

/** Cache keys shared by every screen, so identical requests are made once and show identical data. */
export const keys = {
  dashboard: (period: PeriodId) => `dashboard:${period}`,
  loans: (period: PeriodId) => `loans:${period}`,
  loansPage: (q: LoanQuery) => `loans-page:${JSON.stringify(q)}`,
  loan: (id: string, period: PeriodId) => `loan:${id}:${period}`,
  profitability: (id: string, period: PeriodId) => `profitability:${id}:${period}`,
  risk: (id: string, period: PeriodId) => `risk:${id}:${period}`,
  financialRecords: (id: string) => `financial-records:${id}`,
  customers: () => 'customers',
  aiStatus: () => 'ai-status',
}
