import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { listReportingPeriods } from '../services/periodService'
import { invalidateAll } from '../services/queryCache'
import type { PeriodId, ReportingPeriod } from '../types/loan'
import { REPORTING_PERIODS, getPeriod, yearPeriod } from '../utils/profitability'
import { env } from '../config/env'

/**
 * Shared UI state for the data layer:
 *  - the selected reporting period (every screen shows figures for the same period)
 *  - the periods that can be chosen: the demo presets in mock mode, or the years the backend has data for
 *  - a data version that is bumped after any change (add/edit) so every screen refetches from the single source
 *    of truth instead of keeping its own copy.
 */

export type PeriodsState = { status: 'loading' } | { status: 'ready' } | { status: 'error'; error: unknown }

interface AppDataContextValue {
  period: ReportingPeriod
  setPeriodId: (id: PeriodId) => void
  availablePeriods: ReportingPeriod[]
  periodsState: PeriodsState
  reloadPeriods: () => void
  dataVersion: number
  /** Call after a successful write: clears the request cache, refreshes the periods and makes all screens refetch. */
  invalidate: () => void
}

const AppDataContext = createContext<AppDataContextValue | null>(null)

const currentYear = () => String(new Date().getFullYear())

export function AppDataProvider({ children }: { children: ReactNode }) {
  const [periodId, setPeriodId] = useState<PeriodId>(env.useMock ? '12m' : currentYear())
  const [availablePeriods, setAvailablePeriods] = useState<ReportingPeriod[]>(env.useMock ? [...REPORTING_PERIODS] : [])
  const [periodsState, setPeriodsState] = useState<PeriodsState>(env.useMock ? { status: 'ready' } : { status: 'loading' })
  const [periodsTick, setPeriodsTick] = useState(0)
  const [dataVersion, setDataVersion] = useState(0)

  useEffect(() => {
    if (env.useMock) return
    let cancelled = false
    listReportingPeriods().then(
      (periods) => {
        if (cancelled) return
        // No data anywhere yet: still offer the current year so the empty states and the add forms work.
        const options = periods.length > 0 ? periods : [yearPeriod(currentYear())]
        setAvailablePeriods(options)
        setPeriodId((selected) => (options.some((p) => p.id === selected) ? selected : options[0].id))
        setPeriodsState({ status: 'ready' })
      },
      (error: unknown) => {
        if (!cancelled) setPeriodsState({ status: 'error', error })
      },
    )
    return () => {
      cancelled = true
    }
  }, [periodsTick, dataVersion])

  const reloadPeriods = useCallback(() => {
    setPeriodsState({ status: 'loading' })
    setPeriodsTick((t) => t + 1)
  }, [])

  const invalidate = useCallback(() => {
    invalidateAll()
    setDataVersion((v) => v + 1)
  }, [])

  const period = useMemo(() => getPeriod(periodId), [periodId])
  const value = useMemo(
    () => ({ period, setPeriodId, availablePeriods, periodsState, reloadPeriods, dataVersion, invalidate }),
    [period, availablePeriods, periodsState, reloadPeriods, dataVersion, invalidate],
  )
  return <AppDataContext.Provider value={value}>{children}</AppDataContext.Provider>
}

export function useAppData(): AppDataContextValue {
  const ctx = useContext(AppDataContext)
  if (!ctx) throw new Error('useAppData must be used inside <AppDataProvider>')
  return ctx
}
