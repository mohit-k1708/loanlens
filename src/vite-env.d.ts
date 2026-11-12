/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_USE_MOCK_DATA?: string
  readonly VITE_API_BASE_URL?: string
  readonly VITE_MOCK_LATENCY_MS?: string
  readonly VITE_API_TIMEOUT_MS?: string
  readonly VITE_API_WITH_CREDENTIALS?: string
  readonly VITE_AI_ENABLED?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
