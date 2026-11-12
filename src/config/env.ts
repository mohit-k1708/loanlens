/**
 * Central, typed access to build-time configuration (Vite env variables).
 * Only VITE_* values are exposed to the browser, so never put secrets here
 * (no AI provider keys, no database credentials). Those live in the Spring Boot backend.
 */
// `?? {}` keeps this module importable outside Vite (unit tests, scripts).
const raw: ImportMetaEnv = (import.meta as { env?: ImportMetaEnv }).env ?? ({} as ImportMetaEnv)

const useMock = String(raw.VITE_USE_MOCK_DATA ?? 'true').trim().toLowerCase() !== 'false'

export const env = {
  /** true  -> in-memory mock backend with demonstration data (default).
   *  false -> the real Spring Boot API at apiBaseUrl. No silent fallback to sample data. */
  useMock,
  /** Spring Boot API base, including the /api prefix, e.g. http://localhost:8080/api */
  apiBaseUrl: String(raw.VITE_API_BASE_URL ?? 'http://localhost:8080/api').trim().replace(/\/+$/, ''),
  /** Simulated latency for the mock backend, in ms. */
  mockLatencyMs: Number(raw.VITE_MOCK_LATENCY_MS ?? 450),
  /** Abort live requests that take longer than this. */
  requestTimeoutMs: Number(raw.VITE_API_TIMEOUT_MS ?? 15000),
  /** Send cookies with cross-origin requests (only if the backend uses cookie sessions). */
  withCredentials: String(raw.VITE_API_WITH_CREDENTIALS ?? 'false').trim().toLowerCase() === 'true',
  /**
   * AI Insights. Demo mode has a scripted (non-AI) responder. In backend mode the page calls POST /ai/explain, which
   * answers with an AI explanation when the SERVER has a provider configured and otherwise with a clearly labelled
   * deterministic explanation. Set VITE_AI_ENABLED=false to switch the page off entirely.
   */
  aiEnabled: useMock || String(raw.VITE_AI_ENABLED ?? 'true').trim().toLowerCase() !== 'false',
} as const
