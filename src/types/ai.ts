import type { Paise, PeriodId } from './loan'

export type AiEvidenceTone = 'income' | 'expense' | 'estimate' | 'total' | 'neutral'

export interface AiEvidenceItem {
  label: string
  /** Signed amount in paise (costs negative). Either amount or text is shown. */
  amount?: Paise
  text?: string
  tone: AiEvidenceTone
  /** Makes the row a link to that loan. */
  loanId?: string
}

/** Financial evidence supplied by the backend alongside an explanation. */
export interface AiEvidence {
  title: string
  loanId?: string
  periodLabel: string
  items: AiEvidenceItem[]
}

/**
 * 'demo-scripted' = built-in demonstration responder (NOT an AI model).
 * 'llm'           = wording written by a real AI model behind the Spring Boot AI service (validated by the backend).
 * 'fallback'      = deterministic explanation filled from the backend's figures by fixed rules. NO AI model was used.
 */
export type AiSource = 'demo-scripted' | 'llm' | 'fallback'

export type AiImpact = 'NEGATIVE' | 'POSITIVE' | 'NEUTRAL'

/** Why an explanation is not AI-generated (backend codes). */
export type AiFallbackReason = 'NOT_CONFIGURED' | 'PROVIDER_ERROR' | 'PROVIDER_AUTH' | 'PROVIDER_BILLING' | 'TIMEOUT' | 'RATE_LIMITED' | 'INVALID_AI_RESPONSE' | 'INSUFFICIENT_DATA'

/** A number calculated by the backend. `display` is the exact text to show (e.g. "₹5,00,000", "10%"). */
export interface AiFact {
  key: string
  label: string
  display: string
  unit: 'RUPEES' | 'PERCENT'
  /** Rupees, or a percentage number (10 = 10%). Used only to colour the sign; never recalculated. */
  value: number
}

export interface AiReason {
  factorKey: string
  factor: string
  /** Amount taken from the backend fact for this factor. */
  amountDisplay: string
  impact: AiImpact
  explanation: string
}

/** The structured answer of POST /api/ai/explain. */
export interface AiExplanation {
  loanId: string
  reportingPeriod: string
  source: 'llm' | 'fallback'
  fallbackReason?: AiFallbackReason
  provider?: string
  model?: string
  dataStatus: 'complete' | 'insufficient'
  summary: string
  facts: AiFact[]
  reasons: AiReason[]
  /** For a human reviewer: never a decision. */
  recommendations: string[]
  limitations: string[]
  missingInputs: string[]
  generatedAt: string
}

export interface AiStatus {
  providerConfigured: boolean
  provider?: string
  model?: string
  mode: 'AI' | 'FALLBACK_ONLY'
  message: string
}

export interface AiChatRequest {
  loanId?: string
  /** Backend mode only: a second loan for comparison questions. */
  compareToLoanId?: string
  question: string
  reportingPeriod: PeriodId
  conversationId?: string
}

export interface AiChatResponse {
  answer: string
  source: AiSource
  model?: string
  evidence: AiEvidence[]
  relatedLoanIds: string[]
  suggestions: string[]
  /** Backend mode: the structured explanation behind `answer` (facts, factors, recommendations, limitations). */
  explanation?: AiExplanation
}

export interface AiMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  evidence?: AiEvidence[]
  relatedLoanIds?: string[]
  source?: AiSource
  model?: string
  explanation?: AiExplanation
  /** Set on a failed assistant turn; the question allows a retry. */
  error?: { message: string; question: string; loanId?: string; compareToLoanId?: string }
}

export interface AiConversation {
  id: string
  title: string
  loanId?: string
  compareLoanId?: string
  messages: AiMessage[]
}
