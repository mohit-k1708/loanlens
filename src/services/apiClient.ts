import { env } from '../config/env'
import { ApiError } from './apiError'

/**
 * Thin fetch wrapper for the Spring Boot API.
 *  - base URL comes from VITE_API_BASE_URL (e.g. http://localhost:8080/api); paths passed in are relative to it
 *  - every failure becomes an ApiError with a stable `kind`
 *  - request/response bodies are never logged (they contain customer and financial data)
 */

type Query = Record<string, string | number | undefined | null>

interface RequestOptions {
  query?: Query
  body?: unknown
}

function buildUrl(path: string, query?: Query): string {
  const url = new URL(`${env.apiBaseUrl}${path}`, window.location.origin)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

interface ErrorBody {
  message?: string
  fieldErrors?: Record<string, string>
  missingInputs?: string[]
}

/** Pull only the fields we understand out of an error body; ignore everything else (e.g. stack traces). */
async function readErrorBody(response: Response): Promise<ErrorBody> {
  try {
    const data: unknown = await response.json()
    if (typeof data !== 'object' || data === null) return {}
    const record = data as Record<string, unknown>
    const result: ErrorBody = {}
    if (typeof record.message === 'string' && record.message.length <= 300) result.message = record.message
    if (typeof record.fieldErrors === 'object' && record.fieldErrors !== null) {
      const fe: Record<string, string> = {}
      for (const [k, v] of Object.entries(record.fieldErrors as Record<string, unknown>)) {
        if (typeof v === 'string') fe[k] = v
      }
      result.fieldErrors = fe
    }
    if (Array.isArray(record.missingInputs)) result.missingInputs = record.missingInputs.filter((m): m is string => typeof m === 'string')
    return result
  } catch {
    return {}
  }
}

async function request(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, options: RequestOptions = {}): Promise<unknown> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), env.requestTimeoutMs)

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers: {
        Accept: 'application/json',
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      credentials: env.withCredentials ? 'include' : 'same-origin',
      signal: controller.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new ApiError('timeout', `${method} ${path} timed out`)
    throw new ApiError('network', `${method} ${path} could not reach the server`)
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const status = response.status
    if (status === 401) throw new ApiError('unauthorized', 'HTTP 401', { status })
    if (status === 403) throw new ApiError('forbidden', 'HTTP 403', { status })
    if (status === 404) throw new ApiError('not-found', 'HTTP 404', { status })
    if (status === 429) throw new ApiError('rate-limited', 'HTTP 429', { status })
    if (status === 409) {
      const body = await readErrorBody(response)
      throw new ApiError('conflict', 'HTTP 409', { status, serverMessage: body.message })
    }
    if (status === 400 || status === 413 || status === 422) {
      const body = await readErrorBody(response)
      // 422 with a list of missing inputs = "this calculation cannot be done yet", not a form error.
      if (status === 422 && body.missingInputs && body.missingInputs.length > 0) {
        throw new ApiError('missing-inputs', 'HTTP 422', { status, missingInputs: body.missingInputs, serverMessage: body.message })
      }
      throw new ApiError('validation', `HTTP ${status}`, { status, fieldErrors: body.fieldErrors, serverMessage: body.message })
    }
    // 5xx and anything else: never surface server-provided text.
    throw new ApiError(status >= 500 ? 'server' : 'unknown', `HTTP ${status}`, { status })
  }

  if (response.status === 204) return undefined
  try {
    return await response.json()
  } catch {
    throw new ApiError('invalid-response', `${method} ${path} returned a body that is not valid JSON`, { status: response.status })
  }
}

export const apiClient = {
  get: (path: string, query?: Query) => request('GET', path, { query }),
  post: (path: string, body: unknown) => request('POST', path, { body }),
  put: (path: string, body: unknown) => request('PUT', path, { body }),
  delete: (path: string) => request('DELETE', path),
}
