import { env } from '../config/env'
import type { DashboardSummary, PeriodId } from '../types/loan'
import * as live from './live/liveApi'
import { mapDashboard } from './mappers'
import * as mock from './mock/mockBackend'

/** Backend mode: GET /dashboard/summary?period=  ·  Mock mode: the mock backend. */
export async function getDashboardSummary(period: PeriodId): Promise<DashboardSummary> {
  if (!env.useMock) return live.getDashboardSummary(period)
  return mapDashboard(await mock.getDashboardSummary(period))
}
