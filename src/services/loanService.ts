import { env } from '../config/env'
import type { BackendLoanFormValues, LoanDetail, LoanFormValues, LoanPage, LoanQuery, LoanSummary, PeriodId } from '../types/loan'
import { mapLoanDetail, mapLoanSummaryList, toLoanInputDto } from './mappers'
import * as live from './live/liveApi'
import * as mock from './mock/mockBackend'
import { keys } from './keys'
import { cachedRequest } from './queryCache'

/**
 * Loans. In mock mode this talks to the in-memory mock backend; in backend mode to the Spring Boot API.
 * Screens only ever see the view models, so they do not care which one answered.
 */

/** All loans for a period (pickers, profitability index). */
export async function listLoans(period: PeriodId): Promise<LoanSummary[]> {
  if (!env.useMock) return live.listAllLoans(period)
  return mapLoanSummaryList(await mock.listLoans(period))
}

function matchesSearch(loan: LoanSummary, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return [loan.customerName, loan.id, loan.customerId].some((field) => field.toLowerCase().includes(q))
}

/**
 * One page of loans with search and filters.
 *  - backend mode: all of it happens on the server (GET /loans?search=&loanType=&…&page=&size=)
 *  - mock mode:    the mock data is filtered and paged here, with the same semantics
 */
export async function listLoansPage(q: LoanQuery): Promise<LoanPage> {
  if (!env.useMock) return live.listLoansPage(q)

  // The mock list is fetched once per period (and again after any write); filtering and paging are then instant.
  const all = await cachedRequest(keys.loans(q.period), async () => mapLoanSummaryList(await mock.listLoans(q.period)))
  const filtered = all.filter(
    (loan) =>
      matchesSearch(loan, q.search) &&
      (q.loanType === 'all' || loan.loanType === q.loanType) &&
      (q.risk === 'all' || loan.riskRating === q.risk) &&
      (q.contribution === 'all' || loan.contributionStatus === q.contribution) &&
      (q.loanStatus === 'all' || loan.status === q.loanStatus),
  )
  const totalPages = Math.max(1, Math.ceil(filtered.length / q.size))
  const page = Math.min(q.page, totalPages)
  return {
    items: filtered.slice((page - 1) * q.size, page * q.size),
    totalElements: filtered.length,
    totalPages,
    unfilteredTotal: all.length,
  }
}

export async function getLoan(loanId: string, period: PeriodId): Promise<LoanDetail> {
  if (!env.useMock) return live.getLoan(loanId)
  return mapLoanDetail(await mock.getLoan(loanId, period))
}

/* ---- mock-mode writes (the demonstration form) ---- */

export async function createLoan(values: LoanFormValues): Promise<LoanDetail> {
  return mapLoanDetail(await mock.createLoan(toLoanInputDto(values)))
}

export async function updateLoan(loanId: string, values: LoanFormValues): Promise<LoanDetail> {
  return mapLoanDetail(await mock.updateLoan(loanId, toLoanInputDto(values)))
}

/* ---- backend-mode writes (customer + loan, loan fields only) ---- */

export const createBackendLoan = (values: BackendLoanFormValues) => live.createLoan(values)
export const updateBackendLoan = (loanId: string, values: BackendLoanFormValues) => live.updateLoan(loanId, values)
