import { Banknote, Calculator, LayoutDashboard, LogOut, Sparkles, X, type LucideIcon } from 'lucide-react'
import { Link, matchPath, useLocation } from 'react-router-dom'
import { env } from '../config/env'
import { useAuth } from '../context/AuthContext'
import { Logo } from './Logo'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  isActive: (pathname: string) => boolean
}

const isProfitabilityPath = (p: string) => p === '/profitability' || !!matchPath('/loans/:loanId/profitability', p)

const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, isActive: (p) => p === '/dashboard' },
  {
    to: '/loans',
    label: 'Loan Management',
    icon: Banknote,
    isActive: (p) => (p === '/loans' || !!matchPath('/loans/:loanId', p)) && !isProfitabilityPath(p),
  },
  { to: '/profitability', label: 'Profitability Analysis', icon: Calculator, isActive: isProfitabilityPath },
  { to: '/ai-insights', label: 'AI Insights', icon: Sparkles, isActive: (p) => p === '/ai-insights' },
]

interface SidebarProps {
  open: boolean
  onClose: () => void
}

export function Sidebar({ open, onClose }: SidebarProps) {
  const { user, logout } = useAuth()
  const { pathname } = useLocation()

  return (
    <>
      {/* Mobile / tablet backdrop */}
      {open && <div className="fixed inset-0 z-30 bg-navy/50 lg:hidden" onClick={onClose} aria-hidden="true" />}

      <aside
        aria-label="Primary"
        className={`fixed inset-y-0 left-0 z-40 flex w-60 flex-col bg-navy text-slate-300 transition-transform duration-200 lg:translate-x-0 ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex h-16 items-center justify-between px-5">
          <Link to="/dashboard" onClick={onClose} aria-label="LoanLens home">
            <Logo />
          </Link>
          <button type="button" onClick={onClose} aria-label="Close navigation" className="rounded-lg p-1.5 text-slate-400 hover:bg-white/10 lg:hidden">
            <X className="size-5" />
          </button>
        </div>

        <nav className="mt-4 flex-1 space-y-1 px-3" aria-label="Main navigation">
          <p className="px-3 pb-2 text-[0.68rem] font-semibold uppercase tracking-wider text-slate-500">Menu</p>
          {NAV_ITEMS.map((item) => {
            const active = item.isActive(pathname)
            return (
              <Link
                key={item.to}
                to={item.to}
                onClick={onClose}
                aria-current={active ? 'page' : undefined}
                className={`relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition ${
                  active ? 'bg-white/10 text-white' : 'hover:bg-white/5 hover:text-white'
                }`}
              >
                {active && <span className="absolute inset-y-2 left-0 w-1 rounded-r-full bg-primary" aria-hidden="true" />}
                <item.icon className={`size-[1.1rem] ${active ? 'text-blue-400' : ''}`} aria-hidden="true" />
                {item.label}
              </Link>
            )
          })}
        </nav>

        <div className="mx-3 mb-3 rounded-xl bg-white/5 p-3.5 text-xs leading-relaxed text-slate-400">
          {env.useMock ? (
            <>
              <p className="font-semibold text-slate-200">Mock mode · demo data</p>
              Sample data only. No real customer or banking data is used.
            </>
          ) : (
            <>
              <p className="font-semibold text-slate-200">Live API mode</p>
              <span className="break-all">{env.apiBaseUrl}</span>
            </>
          )}
        </div>

        <div className="border-t border-white/10 p-3">
          <div className="flex items-center gap-3 rounded-lg px-2 py-2">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-white">
              {user?.initials}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-white">{user?.name}</p>
              <p className="truncate text-xs text-slate-400">{user?.title}</p>
            </div>
            <button
              type="button"
              onClick={logout}
              aria-label="Log out"
              title="Log out"
              className="rounded-lg p-2 text-slate-400 transition hover:bg-white/10 hover:text-white"
            >
              <LogOut className="size-4" />
            </button>
          </div>
        </div>
      </aside>
    </>
  )
}
