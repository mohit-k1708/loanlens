import { Bell, ChevronRight, LogOut, Menu, Search } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, matchPath, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { getDashboardSummary } from '../services/dashboardService'
import { keys } from '../services/keys'
import { getLoan } from '../services/loanService'

interface Crumb {
  label: string
  to?: string
}

function useBreadcrumbs(): Crumb[] {
  const { pathname } = useLocation()
  const { period } = useAppData()
  const profitability = matchPath('/loans/:loanId/profitability', pathname)
  const details = matchPath('/loans/:loanId', pathname)
  const loanId = (profitability ?? details)?.params.loanId ?? null
  // Same cache key as the loan pages use, so this adds no extra request.
  const { data: loan } = useApi(loanId ? keys.loan(loanId, period.id) : null, () => getLoan(loanId ?? '', period.id))
  const name = loan?.customerName ?? loanId ?? ''

  if (pathname === '/dashboard') return [{ label: 'Dashboard' }]
  if (pathname === '/loans') return [{ label: 'Loan Management' }]
  if (pathname === '/profitability') return [{ label: 'Profitability Analysis' }]
  if (pathname === '/ai-insights') return [{ label: 'AI Insights' }]
  if (profitability) {
    return [
      { label: 'Loan Management', to: '/loans' },
      { label: name, to: `/loans/${loanId}` },
      { label: 'Profitability Analysis' },
    ]
  }
  if (details) return [{ label: 'Loan Management', to: '/loans' }, { label: name }]
  return [{ label: 'LoanLens' }]
}

/** Close a popover on outside click or Escape. */
function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onPointer = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])
  return ref
}

export function Navbar({ onMenuClick }: { onMenuClick: () => void }) {
  const crumbs = useBreadcrumbs()
  const navigate = useNavigate()
  const { user, logout } = useAuth()
  const { period } = useAppData()
  // Alerts come from the dashboard summary (shared cache). If it fails, the bell simply shows no alerts.
  const { data: dashboard } = useApi(keys.dashboard(period.id), () => getDashboardSummary(period.id), { keepPrevious: true })
  const summary = dashboard?.portfolio ?? { negativeCount: 0, highRiskCount: 0, underWatchCount: 0 }
  const [query, setQuery] = useState('')
  const [notifOpen, setNotifOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const notifRef = useDismiss(notifOpen, () => setNotifOpen(false))
  const profileRef = useDismiss(profileOpen, () => setProfileOpen(false))

  // Notifications are derived from the loan data, not hard-coded.
  const notifications = [
    summary.negativeCount > 0 && {
      id: 'neg',
      text: `${summary.negativeCount} loan${summary.negativeCount > 1 ? 's have' : ' has'} a negative estimated contribution`,
      to: '/loans?status=negative',
    },
    summary.highRiskCount > 0 && {
      id: 'risk',
      text: `${summary.highRiskCount} loan${summary.highRiskCount > 1 ? 's carry' : ' carries'} a High risk rating`,
      to: '/loans?risk=High',
    },
    summary.underWatchCount > 0 && {
      id: 'watch',
      text: `${summary.underWatchCount} loan${summary.underWatchCount > 1 ? 's are' : ' is'} under watch`,
      to: '/loans',
    },
  ].filter((n): n is { id: string; text: string; to: string } => Boolean(n))

  const onSearch = (e: FormEvent) => {
    e.preventDefault()
    const q = query.trim()
    navigate(q ? `/loans?q=${encodeURIComponent(q)}` : '/loans')
    setQuery('')
  }

  return (
    <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-line bg-white/90 px-4 backdrop-blur sm:px-6 lg:px-8">
      <button type="button" onClick={onMenuClick} aria-label="Open navigation" className="rounded-lg p-2 text-ink hover:bg-slate-100 lg:hidden">
        <Menu className="size-5" />
      </button>

      <nav aria-label="Breadcrumb" className="hidden min-w-0 sm:block">
        <ol className="flex items-center gap-1.5 text-sm">
          {crumbs.map((c, i) => {
            const last = i === crumbs.length - 1
            return (
              <li key={c.label + i} className="flex items-center gap-1.5">
                {i > 0 && <ChevronRight className="size-3.5 text-slate-300" aria-hidden="true" />}
                {c.to && !last ? (
                  <Link to={c.to} className="text-muted hover:text-primary">
                    {c.label}
                  </Link>
                ) : (
                  <span aria-current={last ? 'page' : undefined} className={last ? 'font-semibold text-ink' : 'text-muted'}>
                    {c.label}
                  </span>
                )}
              </li>
            )
          })}
        </ol>
      </nav>

      <form onSubmit={onSearch} role="search" className="ml-auto w-full max-w-xs sm:max-w-sm">
        <label htmlFor="global-search" className="sr-only">
          Search loans by customer name, loan ID or customer ID
        </label>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
          <input
            id="global-search"
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search loans…  (press Enter)"
            className="input bg-slate-50 py-2 pl-9"
          />
        </div>
      </form>

      <div ref={notifRef} className="relative">
        <button
          type="button"
          onClick={() => {
            setNotifOpen((o) => !o)
            setProfileOpen(false)
          }}
          aria-label={`Notifications${notifications.length ? `, ${notifications.length} items` : ''}`}
          aria-expanded={notifOpen}
          className="relative rounded-lg p-2 text-muted hover:bg-slate-100 hover:text-ink"
        >
          <Bell className="size-5" />
          {notifications.length > 0 && <span className="absolute right-1.5 top-1.5 size-2 rounded-full bg-negative ring-2 ring-white" aria-hidden="true" />}
        </button>
        {notifOpen && (
          <div className="card absolute right-0 mt-2 w-80 max-w-[calc(100vw-2rem)] p-2 shadow-xl">
            <p className="px-3 py-2 text-xs font-semibold uppercase tracking-wide text-muted">Portfolio alerts</p>
            {notifications.length === 0 ? (
              <p className="px-3 py-3 text-sm text-muted">No alerts for the current data.</p>
            ) : (
              <ul>
                {notifications.map((n) => (
                  <li key={n.id}>
                    <Link to={n.to} onClick={() => setNotifOpen(false)} className="block rounded-lg px-3 py-2.5 text-sm text-ink hover:bg-slate-50">
                      {n.text}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>

      <div ref={profileRef} className="relative">
        <button
          type="button"
          onClick={() => {
            setProfileOpen((o) => !o)
            setNotifOpen(false)
          }}
          aria-expanded={profileOpen}
          aria-label="Account menu"
          className="flex items-center gap-2.5 rounded-lg p-1 pr-2 hover:bg-slate-100"
        >
          <span className="flex size-9 items-center justify-center rounded-full bg-primary text-sm font-semibold text-white">{user?.initials}</span>
          <span className="hidden text-left md:block">
            <span className="block text-sm font-semibold leading-tight text-ink">{user?.name}</span>
            <span className="block text-xs text-muted">{user?.title}</span>
          </span>
        </button>
        {profileOpen && (
          <div className="card absolute right-0 mt-2 w-64 p-2 shadow-xl">
            <div className="px-3 py-2">
              <p className="text-sm font-semibold text-ink">{user?.name}</p>
              <p className="truncate text-xs text-muted">{user?.email}</p>
            </div>
            <button type="button" onClick={logout} className="flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-sm text-negative hover:bg-negative/5">
              <LogOut className="size-4" aria-hidden="true" />
              Log out
            </button>
          </div>
        )}
      </div>
    </header>
  )
}
