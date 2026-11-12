import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { DEMO_CREDENTIALS, DEMO_MANAGER } from '../data/mockData'
import type { ManagerProfile } from '../types/loan'

/**
 * MOCK AUTHENTICATION for the Phase 1 prototype.
 * Credentials are checked against a hard-coded demo account in the browser. This is NOT secure
 * and must be replaced by real server-side authentication before any production use.
 */

const STORAGE_KEY = 'loanlens.demo-session'

export type LoginResult = { ok: true } | { ok: false; message: string }

interface AuthContextValue {
  user: ManagerProfile | null
  login: (email: string, password: string, remember: boolean) => Promise<LoginResult>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function readStoredSession(): ManagerProfile | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY) ?? sessionStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as ManagerProfile) : null
  } catch {
    return null
  }
}

function clearStoredSession() {
  try {
    localStorage.removeItem(STORAGE_KEY)
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    /* storage unavailable – ignore */
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<ManagerProfile | null>(readStoredSession)

  const login = useCallback<AuthContextValue['login']>(async (email, password, remember) => {
    // Simulated network latency so the loading state is visible.
    await new Promise((resolve) => setTimeout(resolve, 700))
    const emailOk = email.trim().toLowerCase() === DEMO_CREDENTIALS.email
    if (!emailOk || password !== DEMO_CREDENTIALS.password) {
      return { ok: false, message: 'The email or password is incorrect. Use the demo account shown on this page.' }
    }
    clearStoredSession()
    try {
      const store = remember ? localStorage : sessionStorage
      store.setItem(STORAGE_KEY, JSON.stringify(DEMO_MANAGER))
    } catch {
      /* storage unavailable – session lives in memory only */
    }
    setUser(DEMO_MANAGER)
    return { ok: true }
  }, [])

  const logout = useCallback(() => {
    clearStoredSession()
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, login, logout }), [user, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
