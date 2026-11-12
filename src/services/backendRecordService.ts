import type { CustomerOption, FinancialRecordFormValues, FinancialRecordView, RiskAssessmentFormValues, RiskDetails } from '../types/loan'
import * as live from './live/liveApi'

/**
 * Records that exist only in backend mode (the mock demo keeps these inside the loan itself).
 * Reached only from UI that is shown in backend mode.
 */

/** GET /loans/{id}/financial-records */
export const listFinancialRecords = (loanId: string): Promise<FinancialRecordView[]> => live.listFinancialRecords(loanId)

/** POST (new period) or PUT (correct an existing period) /loans/{id}/financial-records */
export const saveFinancialRecord = (loanId: string, values: FinancialRecordFormValues, exists: boolean): Promise<void> =>
  live.saveFinancialRecord(loanId, values, exists)

/** POST (new period) or PUT (correct an existing period) /loans/{id}/risk-assessments. The ECL in the result is the backend's. */
export const saveRiskAssessment = (loanId: string, values: RiskAssessmentFormValues, exists: boolean): Promise<RiskDetails> =>
  live.saveRiskAssessment(loanId, values, exists)

/** GET /customers */
export const listCustomers = (): Promise<CustomerOption[]> => live.listCustomers()
