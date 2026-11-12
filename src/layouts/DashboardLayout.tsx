import { Suspense, useEffect, useState, type ReactNode } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { ErrorState } from '../components/ErrorState'
import { useAppData } from '../context/AppDataContext'
import { Navbar } from '../components/Navbar'
import { Sidebar } from '../components/Sidebar'
import { LoadingRegion, Skeleton } from '../components/Skeleton'

/** Backend mode: wait for the reporting periods; if the server can't be reached, say so (with Retry) instead of showing blank pages. */
function PeriodGate({ children }: { children: ReactNode }) {
  const { periodsState, reloadPeriods } = useAppData()
  if (periodsState.status === 'loading') {
    return (
      <LoadingRegion label="Connecting to the server">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="mt-6 h-64" />
      </LoadingRegion>
    )
  }
  if (periodsState.status === 'error') return <ErrorState error={periodsState.error} onRetry={reloadPeriods} subject="reporting periods" />
  return <>{children}</>
}

export function DashboardLayout() {
  const [navOpen, setNavOpen] = useState(false)
  const { pathname } = useLocation()

  // Start each page at the top and close the mobile drawer on navigation.
  useEffect(() => {
    window.scrollTo(0, 0)
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNavOpen(false)
  }, [pathname])

  return (
    <div className="min-h-screen bg-canvas">
      <a
        href="#main"
        className="sr-only z-50 rounded-lg bg-white px-3 py-2 text-sm font-semibold text-primary focus:not-sr-only focus:fixed focus:left-4 focus:top-4"
      >
        Skip to main content
      </a>
      <Sidebar open={navOpen} onClose={() => setNavOpen(false)} />
      <div className="lg:pl-60">
        <Navbar onMenuClick={() => setNavOpen(true)} />
        <main id="main" className="mx-auto max-w-[1440px] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <ErrorBoundary resetKey={pathname}>
            <Suspense
              fallback={
                <LoadingRegion label="Loading page">
                  <Skeleton className="h-10 w-72" />
                  <Skeleton className="mt-6 h-64" />
                </LoadingRegion>
              }
            >
              <PeriodGate>
                <Outlet />
              </PeriodGate>
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
