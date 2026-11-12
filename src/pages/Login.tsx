import { CircleAlert, Eye, EyeOff, Loader2, Lock, Mail, ShieldAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Logo } from '../components/Logo'
import { useAuth } from '../context/AuthContext'
import { DEMO_CREDENTIALS } from '../data/mockData'

interface FieldErrors {
  email?: string
  password?: string
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function Login() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [remember, setRemember] = useState(false)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState('')
  const [notice, setNotice] = useState('')
  const [submitting, setSubmitting] = useState(false)

  if (user) return <Navigate to={redirectTo} replace />

  const validate = (): FieldErrors => {
    const next: FieldErrors = {}
    if (!email.trim()) next.email = 'Email address is required.'
    else if (!EMAIL_PATTERN.test(email.trim())) next.email = 'Enter a valid email address, e.g. name@bank.com.'
    if (!password) next.password = 'Password is required.'
    else if (password.length < 6) next.password = 'Password must be at least 6 characters.'
    return next
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setFormError('')
    setNotice('')
    const next = validate()
    setErrors(next)
    if (Object.keys(next).length > 0) {
      document.getElementById(next.email ? 'email' : 'password')?.focus()
      return
    }
    setSubmitting(true)
    const result = await login(email, password, remember)
    setSubmitting(false)
    if (result.ok) navigate(redirectTo, { replace: true })
    else setFormError(result.message)
  }

  const useDemoAccount = () => {
    setEmail(DEMO_CREDENTIALS.email)
    setPassword(DEMO_CREDENTIALS.password)
    setErrors({})
    setFormError('')
  }

  return (
    <div className="grid min-h-screen bg-canvas lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      {/* Brand panel */}
      <section className="relative hidden flex-col justify-between overflow-hidden bg-navy p-12 text-white lg:flex" aria-label="About LoanLens">
        <div className="pointer-events-none absolute -right-24 -top-24 size-96 rounded-full bg-primary/20 blur-3xl" aria-hidden="true" />
        <div className="pointer-events-none absolute -bottom-32 -left-16 size-96 rounded-full bg-blue-400/10 blur-3xl" aria-hidden="true" />
        <Logo className="relative" />
        <div className="relative max-w-lg">
          <h1 className="text-4xl font-bold leading-tight tracking-tight">Understand Every Loan. Make Better Financial Decisions.</h1>
          <p className="mt-5 text-base leading-relaxed text-slate-300">
            LoanLens brings interest and fee income, funding cost, operational cost and expected credit loss together, so managers can see what each loan is estimated to contribute.
          </p>
          <ul className="mt-8 space-y-3 text-sm text-slate-300">
            {['Portfolio-level contribution at a glance', 'Loan-by-loan income and cost breakdown', 'Expected credit loss kept separate from actual results'].map((t) => (
              <li key={t} className="flex items-center gap-3">
                <span className="size-1.5 rounded-full bg-blue-400" aria-hidden="true" />
                {t}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-slate-400">Phase 1 prototype · demonstration data only</p>
      </section>

      {/* Form panel */}
      <main className="flex items-center justify-center px-4 py-10 sm:px-8">
        <div className="w-full max-w-md">
          <Logo tone="dark" className="mb-8 lg:hidden" />
          <p className="mb-6 text-sm font-medium text-primary lg:hidden">Understand Every Loan. Make Better Financial Decisions.</p>

          <div className="card p-6 sm:p-8">
            <h2 className="text-2xl font-bold tracking-tight text-ink">Welcome back</h2>
            <p className="mt-1 text-sm text-muted">Sign in to your manager dashboard.</p>

            {formError && (
              <div role="alert" className="mt-5 flex items-start gap-2.5 rounded-lg border border-negative/30 bg-negative/5 px-3.5 py-3 text-sm text-negative">
                <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                {formError}
              </div>
            )}
            {notice && (
              <div role="status" className="mt-5 rounded-lg border border-primary/20 bg-primary/5 px-3.5 py-3 text-sm text-primary">
                {notice}
              </div>
            )}

            <form onSubmit={handleSubmit} noValidate className="mt-6 space-y-5">
              <div>
                <label htmlFor="email" className="mb-1.5 block text-sm font-medium text-ink">
                  Email address
                </label>
                <div className="relative">
                  <Mail className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
                  <input
                    id="email"
                    type="email"
                    autoComplete="username"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    aria-invalid={Boolean(errors.email)}
                    aria-describedby={errors.email ? 'email-error' : undefined}
                    placeholder="manager@loanlens.demo"
                    className={`input pl-9 ${errors.email ? 'input-error' : ''}`}
                  />
                </div>
                {errors.email && (
                  <p id="email-error" role="alert" className="mt-1.5 text-xs text-negative">
                    {errors.email}
                  </p>
                )}
              </div>

              <div>
                <label htmlFor="password" className="mb-1.5 block text-sm font-medium text-ink">
                  Password
                </label>
                <div className="relative">
                  <Lock className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
                  <input
                    id="password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    aria-invalid={Boolean(errors.password)}
                    aria-describedby={errors.password ? 'password-error' : undefined}
                    placeholder="Enter your password"
                    className={`input pl-9 pr-11 ${errors.password ? 'input-error' : ''}`}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((s) => !s)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    aria-pressed={showPassword}
                    className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1.5 text-slate-400 hover:text-ink"
                  >
                    {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                </div>
                {errors.password && (
                  <p id="password-error" role="alert" className="mt-1.5 text-xs text-negative">
                    {errors.password}
                  </p>
                )}
              </div>

              <div className="flex items-center justify-between gap-3">
                <label className="flex cursor-pointer items-center gap-2 text-sm text-ink">
                  <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="size-4 rounded border-line accent-primary" />
                  Remember me
                </label>
                <button
                  type="button"
                  onClick={() => setNotice('Password reset is not available in this demo. Use the demo account below.')}
                  className="text-sm font-medium text-primary hover:underline"
                >
                  Forgot password?
                </button>
              </div>

              <button type="submit" disabled={submitting} className="btn-primary w-full py-3">
                {submitting ? (
                  <>
                    <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                    Signing in…
                  </>
                ) : (
                  'Log in'
                )}
              </button>
            </form>
          </div>

          <div className="mt-5 rounded-xl border border-line bg-white p-4 text-sm">
            <p className="font-semibold text-ink">Demo account</p>
            <dl className="mt-2 space-y-1 text-muted">
              <div className="flex gap-2">
                <dt className="w-20 shrink-0">Email</dt>
                <dd className="font-mono text-ink">{DEMO_CREDENTIALS.email}</dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-20 shrink-0">Password</dt>
                <dd className="font-mono text-ink">{DEMO_CREDENTIALS.password}</dd>
              </div>
            </dl>
            <button type="button" onClick={useDemoAccount} className="btn-secondary mt-3 w-full py-2">
              Fill demo credentials
            </button>
          </div>

          <p className="mt-5 flex items-start gap-2 text-xs leading-relaxed text-muted">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
            Prototype sign-in only. This mock login runs entirely in your browser and is not real or secure authentication.
          </p>
        </div>
      </main>
    </div>
  )
}
