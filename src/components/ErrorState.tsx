import { CloudOff, LogIn, RefreshCw, ShieldX, TriangleAlert } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { isApiError, userMessage } from '../services/apiError'

interface ErrorStateProps {
  error: unknown
  onRetry?: () => void
  /** What failed to load, used in the message ("loan information"). */
  subject?: string
  /** Render without the card chrome (for use inside an existing card). */
  bare?: boolean
}

/** Friendly, actionable failure message with a Retry button. Never shows server internals. */
export function ErrorState({ error, onRetry, subject = 'this information', bare = false }: ErrorStateProps) {
  const { logout } = useAuth()
  const kind = isApiError(error) ? error.kind : 'unknown'
  const Icon = kind === 'network' || kind === 'timeout' ? CloudOff : kind === 'forbidden' ? ShieldX : TriangleAlert

  return (
    <div role="alert" className={bare ? '' : 'card'}>
      <div className="flex flex-col items-center px-6 py-12 text-center">
        <span className="flex size-14 items-center justify-center rounded-full bg-negative/10 text-negative">
          <Icon className="size-6" aria-hidden="true" />
        </span>
        <h3 className="mt-4 text-base font-semibold text-ink">Unable to retrieve {subject}</h3>
        <p className="mt-1 max-w-md text-sm text-muted">{userMessage(error, subject)}</p>
        <div className="mt-5 flex flex-wrap justify-center gap-3">
          {kind === 'unauthorized' ? (
            <button type="button" onClick={logout} className="btn-primary">
              <LogIn className="size-4" aria-hidden="true" />
              Sign in again
            </button>
          ) : (
            onRetry && (
              <button type="button" onClick={onRetry} className="btn-primary">
                <RefreshCw className="size-4" aria-hidden="true" />
                Try again
              </button>
            )
          )}
        </div>
      </div>
    </div>
  )
}
