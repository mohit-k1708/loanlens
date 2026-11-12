export type ApiErrorKind =
  | 'network'
  | 'timeout'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'validation'
  | 'conflict'
  | 'missing-inputs'
  | 'rate-limited'
  | 'server'
  | 'invalid-response'
  | 'unavailable'
  | 'unknown'

/**
 * Error raised by the service layer. `message` is developer-oriented and never shown to
 * managers; use userMessage(). The raw server body / stack trace is never kept.
 */
export class ApiError extends Error {
  kind: ApiErrorKind
  status?: number
  /** Server-side validation messages keyed by request field name (400/422 validation only). */
  fieldErrors?: Record<string, string>
  /** Safe, server-supplied message for validation and conflict errors. */
  serverMessage?: string
  /** For 'missing-inputs': what the backend says is missing (FINANCIAL_RECORD, RISK_ASSESSMENT, or a description). */
  missingInputs?: string[]

  constructor(
    kind: ApiErrorKind,
    message: string,
    options: { status?: number; fieldErrors?: Record<string, string>; serverMessage?: string; missingInputs?: string[] } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = options.status
    this.fieldErrors = options.fieldErrors
    this.serverMessage = options.serverMessage
    this.missingInputs = options.missingInputs
  }
}

export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError
}

/** Plain-language message safe to show to a bank manager. */
export function userMessage(err: unknown, _subject = 'this information'): string {
  const kind: ApiErrorKind = isApiError(err) ? err.kind : 'unknown'
  switch (kind) {
    case 'network':
      return 'Unable to connect to the server. Please try again.'
    case 'timeout':
      return 'The server took too long to respond. Please try again.'
    case 'unauthorized':
      return 'Your session has expired or you are not signed in. Please sign in again.'
    case 'forbidden':
      return (isApiError(err) && err.serverMessage) || 'You do not have permission to view or change this information.'
    case 'not-found':
      return 'Loan information could not be found.'
    case 'validation':
      return (isApiError(err) && err.serverMessage) || 'Some of the information provided is not valid. Please review it and try again.'
    case 'conflict':
      return (isApiError(err) && err.serverMessage) || 'This conflicts with existing information. Please refresh and try again.'
    case 'missing-inputs':
      return 'Some information needed for this calculation has not been recorded yet.'
    case 'rate-limited':
      return 'Too many questions in a short time. Please wait a moment and try again.'
    case 'server':
      return 'The server ran into a problem. Please try again in a moment.'
    case 'invalid-response':
      return 'The server returned information in an unexpected format, so it was not displayed. Please try again or contact support.'
    case 'unavailable':
      return 'This feature is not available yet.'
    default:
      return 'Something went wrong. Please try again.'
  }
}
