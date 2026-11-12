import { Component, type ErrorInfo, type ReactNode } from 'react'
import { TriangleAlert } from 'lucide-react'

interface Props {
  children: ReactNode
  /** Change this (e.g. the pathname) to clear a caught error when the user navigates away. */
  resetKey?: string
}
interface State {
  failed: boolean
  resetKey?: string
}

/** Last line of defence: a rendering bug on one screen shows a message instead of a blank app. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, resetKey: this.props.resetKey }

  static getDerivedStateFromError(): Partial<State> {
    return { failed: true }
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    if (props.resetKey !== state.resetKey) return { failed: false, resetKey: props.resetKey }
    return null
  }

  componentDidCatch(_error: Error, _info: ErrorInfo) {
    // Intentionally not logging: render errors may include customer or financial values.
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div role="alert" className="card flex flex-col items-center px-6 py-14 text-center">
        <span className="flex size-14 items-center justify-center rounded-full bg-negative/10 text-negative">
          <TriangleAlert className="size-6" aria-hidden="true" />
        </span>
        <h2 className="mt-4 text-base font-semibold text-ink">This page ran into a problem</h2>
        <p className="mt-1 max-w-md text-sm text-muted">The rest of LoanLens is unaffected. Try again, or use the navigation to open another page.</p>
        <button type="button" onClick={() => this.setState({ failed: false })} className="btn-primary mt-5">
          Try again
        </button>
      </div>
    )
  }
}
