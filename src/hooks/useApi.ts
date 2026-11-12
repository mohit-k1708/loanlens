import { useCallback, useEffect, useRef, useState } from 'react'
import { useAppData } from '../context/AppDataContext'
import { cachedRequest, invalidateKey } from '../services/queryCache'

interface Result<T> {
  id: string
  data?: T
  error?: unknown
}

export interface ApiState<T> {
  data: T | undefined
  error: unknown
  /** True while a request for the current key is in flight (data may still hold the previous value if keepPrevious). */
  loading: boolean
  /** Discard the cached response and request again (used by Retry buttons). */
  reload: () => void
}

/**
 * Load a resource through the service layer.
 *  - `key` identifies the resource (null = do not fetch yet). Identical keys share one request.
 *  - refetches automatically after any mutation (AppData.dataVersion) and when the key changes.
 *  - never throws: failures are returned in `error` so one failed call can't crash the page.
 *  - keepPrevious: keep showing the last successful data while a new key loads (period switches).
 *  - nothing is requested until the reporting periods are known (backend mode), so a server that is down is reported once.
 */
export function useApi<T>(key: string | null, fetcher: () => Promise<T>, options: { keepPrevious?: boolean } = {}): ApiState<T> {
  const { dataVersion, periodsState } = useAppData()
  const [tick, setTick] = useState(0)
  const [result, setResult] = useState<Result<T> | null>(null)
  const fetcherRef = useRef(fetcher)
  const [lastGood, setLastGood] = useState<T | undefined>(undefined)

  useEffect(() => {
    fetcherRef.current = fetcher
  })

  const activeKey = periodsState.status === 'ready' ? key : null
  const requestId = activeKey === null ? null : `${activeKey}|${dataVersion}|${tick}`

  useEffect(() => {
    if (activeKey === null || requestId === null) return
    let cancelled = false
    cachedRequest(activeKey, () => fetcherRef.current()).then(
      (data) => {
        if (cancelled) return
        setLastGood(data)
        setResult({ id: requestId, data })
      },
      (error: unknown) => {
        if (!cancelled) setResult({ id: requestId, error })
      },
    )
    return () => {
      cancelled = true
    }
  }, [activeKey, requestId])

  const reload = useCallback(() => {
    if (activeKey !== null) invalidateKey(activeKey)
    setTick((t) => t + 1)
  }, [activeKey])

  const current = result && result.id === requestId ? result : null
  const loading = activeKey !== null && current === null
  const data = current ? current.data : options.keepPrevious ? lastGood : undefined

  return { data, error: current?.error, loading, reload }
}
