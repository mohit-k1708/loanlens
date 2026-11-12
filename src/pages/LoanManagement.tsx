import { CircleCheck, ChevronLeft, ChevronRight, Landmark, Plus, SearchX, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { BackendLoanFormModal } from '../components/BackendLoanFormModal'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { FilterBar, type FilterDefinition } from '../components/FilterBar'
import { LoanFormModal } from '../components/LoanFormModal'
import { LoanTable } from '../components/LoanTable'
import { DemoDataNotice, PageHeader } from '../components/PageHeader'
import { PeriodSelect } from '../components/PeriodSelect'
import { SearchBar } from '../components/SearchBar'
import { LoadingRegion, Skeleton } from '../components/Skeleton'
import { env } from '../config/env'
import { useAppData } from '../context/AppDataContext'
import { useApi } from '../hooks/useApi'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { keys } from '../services/keys'
import { getLoan, listLoansPage } from '../services/loanService'
import { LOAN_TYPES, RISK_RATINGS, type ContributionStatus, type LoanDetail, type LoanQuery, type LoanStatus, type LoanType, type RiskRating } from '../types/loan'
import { periodRangeLabel } from '../utils/profitability'

const PAGE_SIZES = [5, 10, 25] as const
const LOAN_STATUSES: LoanStatus[] = ['Active', 'Under Watch', 'Closed', 'Defaulted']

const CONTRIBUTION_OPTIONS = [
  { value: 'all', label: 'All statuses' },
  { value: 'positive', label: 'Positive' },
  { value: 'negative', label: 'Negative' },
  { value: 'break-even', label: 'Break-even' },
]

interface Filters {
  q: string
  type: 'all' | LoanType
  risk: 'all' | RiskRating
  /** contribution status */
  status: 'all' | ContributionStatus
  loanStatus: 'all' | LoanStatus
}

const pick = <T extends string>(value: string | null, allowed: readonly T[]): 'all' | T => (value && (allowed as readonly string[]).includes(value) ? (value as T) : 'all')

/** Initial search/filters come from the URL (dashboard cards, header search and alerts link here). */
function readFilters(params: URLSearchParams): Filters {
  return {
    q: params.get('q') ?? '',
    type: pick(params.get('type'), LOAN_TYPES),
    risk: pick(params.get('risk'), RISK_RATINGS),
    status: pick(params.get('status'), ['positive', 'negative', 'break-even'] as const),
    loanStatus: pick(params.get('loanStatus'), LOAN_STATUSES),
  }
}

/**
 * Remounts the view on every navigation to this route (new location.key), so a deep link such as
 * /loans?status=negative always re-initialises the filters, while typing stays purely local state.
 */
export default function LoanManagement() {
  const { key } = useLocation()
  return <LoanManagementView key={key} />
}

type ModalState = { mode: 'add' } | { mode: 'edit'; loanId: string } | null

/** Loads the loan to edit (backend mode needs its full record) and shows the right edit form. */
function EditModal({ loanId, onClose, onSaved }: { loanId: string; onClose: () => void; onSaved: (loan: LoanDetail, mode: 'add' | 'edit') => void }) {
  const { period } = useAppData()
  const { data, error, reload } = useApi(keys.loan(loanId, period.id), () => getLoan(loanId, period.id))
  if (error) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-navy/50 p-4" role="dialog" aria-modal="true" aria-label="Edit loan">
        <div className="w-full max-w-md rounded-2xl bg-white p-2 shadow-2xl">
          <ErrorState error={error} onRetry={reload} subject="the loan" bare />
          <div className="flex justify-end px-4 pb-4">
            <button type="button" className="btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        </div>
      </div>
    )
  }
  if (!data) return null
  return env.useMock ? (
    <LoanFormModal open mode="edit" loanId={loanId} onClose={onClose} onSaved={onSaved} />
  ) : (
    <BackendLoanFormModal open mode="edit" existing={data} onClose={onClose} onSaved={onSaved} />
  )
}

function LoanManagementView() {
  const { period } = useAppData()
  const [searchParams] = useSearchParams()
  const [filters, setFilters] = useState<Filters>(() => readFilters(searchParams))
  const [pageSize, setPageSize] = useState<number>(PAGE_SIZES[0])
  const [page, setPage] = useState(1)
  const [modal, setModal] = useState<ModalState>(null)
  const [notice, setNotice] = useState<{ loan: LoanDetail; mode: 'add' | 'edit' } | null>(null)

  const debouncedSearch = useDebouncedValue(filters.q, 300)
  const query: LoanQuery = {
    period: period.id,
    search: debouncedSearch,
    loanType: filters.type,
    risk: filters.risk,
    contribution: filters.status,
    loanStatus: filters.loanStatus,
    page,
    size: pageSize,
  }
  // Backend mode: the server does the searching, filtering and paging. Mock mode: the mock data is filtered in the service.
  const { data, error, loading, reload } = useApi(keys.loansPage(query), () => listLoansPage(query), { keepPrevious: true })

  const update = <K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }))
    setPage(1)
  }
  const clearFilters = () => {
    setFilters({ q: '', type: 'all', risk: 'all', status: 'all', loanStatus: 'all' })
    setPage(1)
  }
  const hasActiveFilters = filters.q !== '' || filters.type !== 'all' || filters.risk !== 'all' || filters.status !== 'all' || filters.loanStatus !== 'all'

  const items = data?.items ?? []
  const total = data?.totalElements ?? 0
  const totalPages = Math.max(1, data?.totalPages ?? 1)
  const currentPage = Math.min(page, totalPages)
  const startIndex = (currentPage - 1) * pageSize

  const filterDefs: FilterDefinition[] = [
    { id: 'type', label: 'Loan type', value: filters.type, onChange: (v) => update('type', v as Filters['type']), options: [{ value: 'all', label: 'All loan types' }, ...LOAN_TYPES.map((t) => ({ value: t, label: t }))] },
    { id: 'loanStatus', label: 'Loan status', value: filters.loanStatus, onChange: (v) => update('loanStatus', v as Filters['loanStatus']), options: [{ value: 'all', label: 'All loan statuses' }, ...LOAN_STATUSES.map((s) => ({ value: s, label: s }))] },
    { id: 'risk', label: 'Risk rating', value: filters.risk, onChange: (v) => update('risk', v as Filters['risk']), options: [{ value: 'all', label: 'All ratings' }, ...RISK_RATINGS.map((r) => ({ value: r, label: r }))] },
    { id: 'status', label: 'Contribution status', value: filters.status, onChange: (v) => update('status', v as Filters['status']), options: CONTRIBUTION_OPTIONS },
  ]

  const handleSaved = (loan: LoanDetail, mode: 'add' | 'edit') => {
    setNotice({ loan, mode })
    if (mode === 'add') clearFilters()
  }

  // Only once loading has finished: while a new result is on its way, `data` may still hold the previous (empty) one.
  // The result on screen was fetched with the DEBOUNCED search text, so judge "no filters" by the query actually sent.
  const queryHasFilters = query.search !== '' || query.loanType !== 'all' || query.risk !== 'all' || query.contribution !== 'all' || query.loanStatus !== 'all'
  const noLoansAtAll = !queryHasFilters && !hasActiveFilters && !loading && data !== undefined && total === 0

  return (
    <>
      <PageHeader
        title="Loan Management"
        subtitle="Search, view and analyse individual customer loans."
        actions={
          <button type="button" onClick={() => setModal({ mode: 'add' })} className="btn-primary">
            <Plus className="size-4" aria-hidden="true" />
            Add New Loan
          </button>
        }
      />
      <DemoDataNotice className="-mt-3 mb-6" />

      {notice && (
        <div role="status" className="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-positive/30 bg-positive/5 px-4 py-3 text-sm text-ink">
          <CircleCheck className="size-5 shrink-0 text-positive" aria-hidden="true" />
          <span className="flex-1">
            Loan <strong>{notice.loan.id}</strong> for {notice.loan.customerName} was {notice.mode === 'add' ? 'added' : 'updated'}.
          </span>
          <Link to={`/loans/${notice.loan.id}`} className="font-semibold text-primary hover:underline">
            View details
          </Link>
          <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss message" className="rounded p-1 text-muted hover:text-ink">
            <X className="size-4" />
          </button>
        </div>
      )}

      <section className="card overflow-hidden" aria-label="Loan list">
        <div className="space-y-4 border-b border-line p-5 sm:p-6">
          <SearchBar value={filters.q} onChange={(v) => update('q', v)} />
          <FilterBar filters={filterDefs} trailing={<PeriodSelect className="min-w-64 flex-1 sm:flex-none" />} hasActiveFilters={hasActiveFilters} onClear={clearFilters} />
          <p className="text-xs text-muted">Estimated contribution is shown for {periodRangeLabel(period)}.</p>
        </div>

        {error ? (
          <ErrorState error={error} onRetry={reload} subject="the loan list" bare />
        ) : !data && loading ? (
          <LoadingRegion label="Loading loans">
            <div className="space-y-3 p-6">
              {[0, 1, 2, 3, 4].map((i) => (
                <Skeleton key={i} className="h-14" />
              ))}
            </div>
          </LoadingRegion>
        ) : noLoansAtAll ? (
          <EmptyState
            icon={Landmark}
            title="No loans available"
            description="There are no loans in the system yet. Add the first loan to start analysing profitability."
            action={
              <button type="button" onClick={() => setModal({ mode: 'add' })} className="btn-primary">
                <Plus className="size-4" aria-hidden="true" />
                Add New Loan
              </button>
            }
          />
        ) : items.length === 0 ? (
          <EmptyState
            icon={SearchX}
            title="No loans found"
            description="No loans match your search and filters. Try a different customer name, loan ID or customer ID, or clear the filters."
            action={
              <button type="button" onClick={clearFilters} className="btn-secondary">
                Clear search &amp; filters
              </button>
            }
          />
        ) : (
          <div aria-busy={loading} className={loading ? 'opacity-60 transition' : 'transition'}>
            <LoanTable
              loans={items}
              columns={['loanId', 'customer', 'loanType', 'principal', 'outstanding', 'rate', 'risk', 'contribution', 'action']}
              caption="Customer loans"
              onEdit={(loan) => setModal({ mode: 'edit', loanId: loan.id })}
            />
            <div className="flex flex-wrap items-center justify-between gap-4 border-t border-line px-5 py-4 text-sm sm:px-6">
              <p className="text-muted" aria-live="polite">
                Showing <span className="font-semibold text-ink">{startIndex + 1}–{startIndex + items.length}</span> of{' '}
                <span className="font-semibold text-ink">{total}</span> loans
                {data?.unfilteredTotal !== undefined && total !== data.unfilteredTotal && <> (filtered from {data.unfilteredTotal})</>}
              </p>
              <div className="flex flex-wrap items-center gap-4">
                <label className="flex items-center gap-2 text-muted">
                  Rows per page
                  <select
                    value={pageSize}
                    onChange={(e) => {
                      setPageSize(Number(e.target.value))
                      setPage(1)
                    }}
                    className="input w-auto py-1.5"
                  >
                    {PAGE_SIZES.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </label>
                <nav aria-label="Pagination" className="flex items-center gap-1">
                  <button type="button" onClick={() => setPage(currentPage - 1)} disabled={currentPage === 1} aria-label="Previous page" className="btn-secondary px-2.5 py-1.5">
                    <ChevronLeft className="size-4" />
                  </button>
                  <span className="px-3 text-muted">
                    Page <span className="font-semibold text-ink">{currentPage}</span> of {totalPages}
                  </span>
                  <button type="button" onClick={() => setPage(currentPage + 1)} disabled={currentPage === totalPages} aria-label="Next page" className="btn-secondary px-2.5 py-1.5">
                    <ChevronRight className="size-4" />
                  </button>
                </nav>
              </div>
            </div>
          </div>
        )}
      </section>

      {modal?.mode === 'add' &&
        (env.useMock ? (
          <LoanFormModal open mode="add" onClose={() => setModal(null)} onSaved={handleSaved} />
        ) : (
          <BackendLoanFormModal open mode="add" onClose={() => setModal(null)} onSaved={handleSaved} />
        ))}
      {modal?.mode === 'edit' && <EditModal loanId={modal.loanId} onClose={() => setModal(null)} onSaved={handleSaved} />}
    </>
  )
}
