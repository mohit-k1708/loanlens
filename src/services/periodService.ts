import { env } from '../config/env'
import type { ReportingPeriod } from '../types/loan'
import { REPORTING_PERIODS, yearPeriod } from '../utils/profitability'
import * as live from './live/liveApi'

/**
 * Reporting periods the user can choose from.
 *  - mock mode:    the demo presets (12 / 6 / 3 months)
 *  - backend mode: GET /reporting-periods (the years that have data), newest first
 */
export async function listReportingPeriods(): Promise<ReportingPeriod[]> {
  if (env.useMock) return [...REPORTING_PERIODS]
  return (await live.listReportingPeriods()).map(yearPeriod)
}
