import { lazy } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { DashboardLayout } from './layouts/DashboardLayout'
import LoanDetails from './pages/LoanDetails'
import LoanManagement from './pages/LoanManagement'
import Login from './pages/Login'
import ProfitabilityIndex from './pages/ProfitabilityIndex'

// Chart pages pull in Recharts; load them on demand to keep the initial bundle small.
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Profitability = lazy(() => import('./pages/Profitability'))
const AiInsights = lazy(() => import('./pages/AiInsights'))

/** Mock route guard: redirects to /login when there is no demo session. Real access control belongs to Spring Boot. */
function RequireAuth() {
  const { user } = useAuth()
  const location = useLocation()
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <Outlet />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<DashboardLayout />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/loans" element={<LoanManagement />} />
          <Route path="/loans/:loanId" element={<LoanDetails />} />
          <Route path="/loans/:loanId/profitability" element={<Profitability />} />
          <Route path="/profitability" element={<ProfitabilityIndex />} />
          <Route path="/ai-insights" element={<AiInsights />} />
        </Route>
      </Route>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  )
}
