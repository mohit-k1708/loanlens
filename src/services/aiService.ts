import { env } from '../config/env'
import type { AiChatRequest, AiChatResponse, AiStatus } from '../types/ai'
import type { AiChatRequestDto } from '../types/api'
import { apiClient } from './apiClient'
import { ApiError } from './apiError'
import { mapAiExplanation, mapAiStatus } from './live/liveMappers'
import { mapAiChat } from './mappers'
import * as mock from './mock/mockBackend'

/**
 * AI Insights.
 *  - mock mode:    a scripted responder, labelled "not an AI model".
 *  - backend mode: POST /ai/explain. The backend validates the request, calculates the figures itself, sends ONLY a
 *                  sanitized fact list (no customer name, phone or e-mail) to the AI provider, validates the answer and
 *                  returns a structured explanation. If no provider is configured or it fails, the backend answers with a
 *                  deterministic explanation that is labelled as such (source 'fallback'). The browser never holds an AI
 *                  key and never sees a prompt. When the manager's loan has missing data the answer says so.
 */
export async function askAi(req: AiChatRequest): Promise<AiChatResponse> {
  if (!env.aiEnabled) {
    throw new ApiError('unavailable', 'AI Insights is switched off in this build (VITE_AI_ENABLED=false)')
  }
  if (env.useMock) {
    const body: AiChatRequestDto = {
      loanId: req.loanId ?? null,
      question: req.question,
      reportingPeriod: req.reportingPeriod,
      conversationId: req.conversationId ?? null,
    }
    return mapAiChat(await mock.chat(body))
  }

  if (!req.loanId) throw new ApiError('validation', 'A loan must be selected', { serverMessage: 'Select a loan first, then ask your question.' })
  const data = await apiClient.post('/ai/explain', {
    loanId: req.loanId,
    reportingPeriod: req.reportingPeriod,
    question: req.question,
    ...(req.compareToLoanId && req.compareToLoanId !== req.loanId ? { compareToLoanId: req.compareToLoanId } : {}),
  })
  const explanation = mapAiExplanation(data)
  return {
    answer: explanation.summary,
    source: explanation.source,
    model: explanation.model,
    evidence: [],
    relatedLoanIds: [explanation.loanId],
    suggestions: [],
    explanation,
  }
}

/** Whether the server has an AI provider configured (backend mode only). Contains no secret. */
export async function fetchAiStatus(): Promise<AiStatus> {
  if (env.useMock) {
    return { providerConfigured: false, mode: 'FALLBACK_ONLY', message: 'Demonstration mode: answers come from a scripted responder, not an AI model.' }
  }
  return mapAiStatus(await apiClient.get('/ai/status'))
}
