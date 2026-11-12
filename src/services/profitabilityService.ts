import { env } from '../config/env'
import type { LoanFinancials, PeriodId, RiskDetails } from '../types/loan'
import * as live from './live/liveApi'
import { mapProfitability, mapRisk } from './mappers'
import * as mock from './mock/mockBackend'

/**
 * Backend mode: GET /loans/{id}/profitability?period=. The figures are the backend's; the UI never recalculates them.
 * Throws ApiError('missing-inputs') when the period has no financial record and/or risk assessment.
 */
export async function getProfitability(loanId: string, period: PeriodId): Promise<LoanFinancials> {
  if (!env.useMock) return live.getProfitability(loanId, period)
  return mapProfitability(await mock.getProfitability(loanId, period))
}

/** Backend mode: GET /loans/{id}/risk-assessments/{period} (ApiError 'not-found' when there is none). */
export async function getRisk(loanId: string, period: PeriodId): Promise<RiskDetails> {
  if (!env.useMock) return live.getRisk(loanId, period)
  return mapRisk(await mock.getRisk(loanId))
}
