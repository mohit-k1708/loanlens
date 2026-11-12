import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useAppData } from './AppDataContext'
import { askAi } from '../services/aiService'
import { userMessage } from '../services/apiError'
import type { AiConversation, AiMessage } from '../types/ai'

/**
 * AI Insights conversations. Kept in memory for the browser session only: question text can
 * mention customers and financial figures, so it is deliberately NOT written to browser storage.
 */

interface AiChatContextValue {
  conversations: AiConversation[]
  activeId: string
  active: AiConversation
  /** True while a request for the ACTIVE conversation is in flight (blocks duplicate sends). */
  pending: boolean
  ask: (question: string) => void
  retry: (messageId: string) => void
  newConversation: () => void
  /** Empties the active conversation (its messages are discarded). */
  clearConversation: () => void
  /** Discards every conversation of this session and starts a fresh one. */
  clearHistory: () => void
  select: (id: string) => void
  setFocusLoan: (loanId: string | undefined) => void
  /** Backend mode: second loan for comparison questions. */
  setCompareLoan: (loanId: string | undefined) => void
}

const AiChatContext = createContext<AiChatContextValue | null>(null)

let counter = 0
const uid = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${(counter++).toString(36)}`
const emptyConversation = (): AiConversation => ({ id: uid('conv'), title: 'New conversation', messages: [] })

export function AiChatProvider({ children }: { children: ReactNode }) {
  const { period } = useAppData()
  const [conversations, setConversations] = useState<AiConversation[]>(() => [emptyConversation()])
  const [activeId, setActiveId] = useState(() => conversations[0].id)
  const [pendingIds, setPendingIds] = useState<string[]>([])
  const pendingRef = useRef<Set<string>>(new Set())
  const conversationsRef = useRef(conversations)
  useEffect(() => {
    conversationsRef.current = conversations
  })

  const patch = useCallback((id: string, fn: (c: AiConversation) => AiConversation) => {
    setConversations((list) => list.map((c) => (c.id === id ? fn(c) : c)))
  }, [])

  const send = useCallback(
    (conversationId: string, question: string, loanId: string | undefined, compareToLoanId?: string) => {
      if (pendingRef.current.has(conversationId)) return
      pendingRef.current.add(conversationId)
      setPendingIds([...pendingRef.current])

      askAi({ question, loanId, compareToLoanId, reportingPeriod: period.id, conversationId })
        .then((res) => {
          const message: AiMessage = {
            id: uid('msg'),
            role: 'assistant',
            text: res.answer,
            evidence: res.evidence,
            relatedLoanIds: res.relatedLoanIds,
            source: res.source,
            model: res.model,
            explanation: res.explanation,
          }
          patch(conversationId, (c) => ({ ...c, messages: [...c.messages, message] }))
        })
        .catch((error: unknown) => {
          const message: AiMessage = {
            id: uid('msg'),
            role: 'assistant',
            text: '',
            error: { message: userMessage(error, 'an answer'), question, loanId, compareToLoanId },
          }
          patch(conversationId, (c) => ({ ...c, messages: [...c.messages, message] }))
        })
        .finally(() => {
          pendingRef.current.delete(conversationId)
          setPendingIds([...pendingRef.current])
        })
    },
    [period.id, patch],
  )

  const ask = useCallback(
    (question: string) => {
      const text = question.trim()
      if (!text) return
      const conv = conversationsRef.current.find((c) => c.id === activeId)
      if (!conv || pendingRef.current.has(activeId)) return
      const userMsg: AiMessage = { id: uid('msg'), role: 'user', text }
      patch(activeId, (c) => ({
        ...c,
        title: c.messages.length === 0 ? (text.length > 48 ? `${text.slice(0, 48)}…` : text) : c.title,
        messages: [...c.messages, userMsg],
      }))
      send(activeId, text, conv.loanId, conv.compareLoanId)
    },
    [activeId, patch, send],
  )

  const retry = useCallback(
    (messageId: string) => {
      const conv = conversationsRef.current.find((c) => c.id === activeId)
      const failed = conv?.messages.find((m) => m.id === messageId)
      if (!conv || !failed?.error || pendingRef.current.has(activeId)) return
      const { question, loanId, compareToLoanId } = failed.error
      patch(activeId, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== messageId) }))
      send(activeId, question, loanId, compareToLoanId)
    },
    [activeId, patch, send],
  )

  const newConversation = useCallback(() => {
    // Re-use an untouched empty conversation instead of piling up blank ones.
    const blank = conversationsRef.current.find((c) => c.messages.length === 0)
    if (blank) {
      setActiveId(blank.id)
      return
    }
    const fresh = emptyConversation()
    setConversations((list) => [fresh, ...list])
    setActiveId(fresh.id)
  }, [])

  const setFocusLoan = useCallback((loanId: string | undefined) => patch(activeId, (c) => ({ ...c, loanId })), [activeId, patch])
  const setCompareLoan = useCallback((loanId: string | undefined) => patch(activeId, (c) => ({ ...c, compareLoanId: loanId })), [activeId, patch])

  const clearConversation = useCallback(() => {
    if (pendingRef.current.has(activeId)) return
    patch(activeId, (c) => ({ ...c, title: 'New conversation', messages: [] }))
  }, [activeId, patch])

  const clearHistory = useCallback(() => {
    if (pendingRef.current.size > 0) return
    const fresh = emptyConversation()
    setConversations([fresh])
    setActiveId(fresh.id)
  }, [])

  const active = conversations.find((c) => c.id === activeId) ?? conversations[0]
  const value = useMemo<AiChatContextValue>(
    () => ({
      conversations,
      activeId,
      active,
      pending: pendingIds.includes(activeId),
      ask,
      retry,
      newConversation,
      clearConversation,
      clearHistory,
      select: setActiveId,
      setFocusLoan,
      setCompareLoan,
    }),
    [conversations, activeId, active, pendingIds, ask, retry, newConversation, clearConversation, clearHistory, setFocusLoan, setCompareLoan],
  )
  return <AiChatContext.Provider value={value}>{children}</AiChatContext.Provider>
}

export function useAiChat(): AiChatContextValue {
  const ctx = useContext(AiChatContext)
  if (!ctx) throw new Error('useAiChat must be used inside <AiChatProvider>')
  return ctx
}
