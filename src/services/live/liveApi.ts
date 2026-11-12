import type {
  BackendLoanFormValues,
  CustomerOption,
  DashboardSummary,
  FinancialRecordFormValues,
  FinancialRecordView,
  LoanDetail,
  LoanFinancials,
  LoanPage,
  LoanQuery,
  PeriodId,
  RiskAssessmentFormValues,
  RiskDetails,
} from '../../types/loan'
import { apiClient } from '../apiClient'
import { isApiError } from '../apiError'
import {
  CONTRIBUTION_CODE,
  LOAN_STATUS_CODE,
  LOAN_TYPE_CODE,
  RISK_RATING_CODE,
  mapCreatedId,
  mapCustomerOptions,
  mapDashboard,
  mapFinancialRecords,
  mapLoan,
  mapLoanPage,
  mapPeriods,
  mapProfitability,
  mapRisk,
  toCustomerBody,
  toFinancialRecordBody,
  toLoanCreateBody,
  toLoanUpdateBody,
  toRiskAssessmentBody,
} from './liveMappers'

/**
 * The real Spring Boot endpoints (relative to VITE_API_BASE_URL, which already ends in /api).
 * Every function here maps to an endpoint that exists in backend/. Nothing is faked.
 */

const enc = encodeURIComponent
const MAX_PAGE_SIZE = 100

/** GET /reporting-periods */
export async function listReportingPeriods(): Promise<string[]> {
  return mapPeriods(await apiClient.get('/reporting-periods'))
}

/** GET /loans  (server-side search, filters and paging) */
export async function listLoansPage(q: LoanQuery): Promise<LoanPage> {
  return mapLoanPage(
    await apiClient.get('/loans', {
      period: q.period,
      search: q.search.trim() || undefined,
      loanType: q.loanType === 'all' ? undefined : LOAN_TYPE_CODE[q.loanType],
      riskRating: q.risk === 'all' ? undefined : RISK_RATING_CODE[q.risk],
      contributionStatus: q.contribution === 'all' ? undefined : CONTRIBUTION_CODE[q.contribution],
      loanStatus: q.loanStatus === 'all' ? undefined : LOAN_STATUS_CODE[q.loanStatus],
      page: q.page - 1,
      size: q.size,
      sort: 'loanCode,asc',
    }),
  )
}

/** Every loan for a period, by walking the pages (used by pickers and the profitability index). */
export async function listAllLoans(period: PeriodId): Promise<LoanPage['items']> {
  const items: LoanPage['items'] = []
  for (let page = 0; page < 50; page++) {
    const data = mapLoanPage(await apiClient.get('/loans', { period, page, size: MAX_PAGE_SIZE, sort: 'loanCode,asc' }))
    items.push(...data.items)
    if (page + 1 >= data.totalPages) break
  }
  return items
}

/** GET /loans/{id} */
export async function getLoan(loanId: string): Promise<LoanDetail> {
  return mapLoan(await apiClient.get(`/loans/${enc(loanId)}`))
}

/** GET /customers  (for the customer picker; first page of up to 100 customers, by name) */
export async function listCustomers(): Promise<CustomerOption[]> {
  return mapCustomerOptions(await apiClient.get('/customers', { size: MAX_PAGE_SIZE, sort: 'fullName,asc' }))
}

/**
 * POST /customers (when a new customer is entered) then POST /loans.
 * If the loan is rejected after the customer was created, the new customer is removed again (it has no loans,
 * so the backend allows it) so a failed attempt leaves no orphan behind.
 */
export async function createLoan(values: BackendLoanFormValues): Promise<LoanDetail> {
  let customerId: number
  let createdCustomerId: number | null = null
  if (values.customer.kind === 'existing') {
    customerId = values.customer.id
  } else {
    createdCustomerId = mapCreatedId(await apiClient.post('/customers', toCustomerBody(values.customer)))
    customerId = createdCustomerId
  }
  try {
    return mapLoan(await apiClient.post('/loans', toLoanCreateBody(values, customerId)))
  } catch (error) {
    if (createdCustomerId !== null) {
      try {
        await apiClient.delete(`/customers/${createdCustomerId}`)
      } catch {
        /* best effort: the original error below is what the user needs to see */
      }
    }
    throw error
  }
}

/** PUT /loans/{id} */
export async function updateLoan(loanId: string, values: BackendLoanFormValues): Promise<LoanDetail> {
  return mapLoan(await apiClient.put(`/loans/${enc(loanId)}`, toLoanUpdateBody(values)))
}

/** GET /dashboard/summary?period= */
export async function getDashboardSummary(period: PeriodId): Promise<DashboardSummary> {
  return mapDashboard(await apiClient.get('/dashboard/summary', { period }))
}

/** GET /loans/{id}/profitability?period=  (422 with missingInputs when a financial record / risk assessment is missing) */
export async function getProfitability(loanId: string, period: PeriodId): Promise<LoanFinancials> {
  return mapProfitability(await apiClient.get(`/loans/${enc(loanId)}/profitability`, { period }))
}

/** GET /loans/{id}/risk-assessments/{period}  (404 when there is none for that period) */
export async function getRisk(loanId: string, period: PeriodId): Promise<RiskDetails> {
  return mapRisk(await apiClient.get(`/loans/${enc(loanId)}/risk-assessments/${enc(period)}`))
}

/** POST /loans/{id}/risk-assessments, or PUT .../{period} to correct an existing one. */
export async function saveRiskAssessment(loanId: string, values: RiskAssessmentFormValues, exists: boolean): Promise<RiskDetails> {
  const body = toRiskAssessmentBody(values)
  return mapRisk(
    exists
      ? await apiClient.put(`/loans/${enc(loanId)}/risk-assessments/${enc(values.reportingPeriod)}`, body)
      : await apiClient.post(`/loans/${enc(loanId)}/risk-assessments`, body),
  )
}

/** GET /loans/{id}/financial-records */
export async function listFinancialRecords(loanId: string): Promise<FinancialRecordView[]> {
  return mapFinancialRecords(await apiClient.get(`/loans/${enc(loanId)}/financial-records`))
}

/** POST /loans/{id}/financial-records, or PUT .../{period} to correct an existing one. */
export async function saveFinancialRecord(loanId: string, values: FinancialRecordFormValues, exists: boolean): Promise<void> {
  const body = toFinancialRecordBody(values)
  if (exists) await apiClient.put(`/loans/${enc(loanId)}/financial-records/${enc(values.reportingPeriod)}`, body)
  else await apiClient.post(`/loans/${enc(loanId)}/financial-records`, body)
}

/** True when the error means "this loan has no data for that period" (a normal state, not a failure). */
export function isMissingData(error: unknown): boolean {
  return isApiError(error) && (error.kind === 'missing-inputs' || error.kind === 'not-found')
}
