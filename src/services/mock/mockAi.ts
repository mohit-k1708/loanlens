/**
 * DEMONSTRATION RESPONDER. This is NOT an AI model.
 *
 * It matches a question to a small set of scripted explanations and fills them in with figures
 * read from the loan records, so the AI Insights screen can be built and tested without a backend.
 * Every response is labelled source = "demo-scripted". It never invents values: every number in
 * the text and the evidence panel comes from the same calculation engine the rest of the app uses.
 *
 * In live mode this file is not used: POST /api/ai/chat goes to the Spring Boot AI service.
 */
import type { AiChatRequestDto, AiChatResponseDto, AiEvidenceDto } from '../../types/api'
import type { Loan, LoanFinancials, LoanType, ReportingPeriod } from '../../types/loan'
import { formatINR } from '../../utils/formatCurrency'
import { computeLoanFinancials, periodRangeLabel } from '../../utils/profitability'

const r = (paise: number): number => paise / 100

interface LoanCtx {
  loan: Loan
  f: LoanFinancials
}

function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Loans named in the question (full name, first name or loan ID), in order of appearance. */
function findMentionedLoans(question: string, ctxs: LoanCtx[]): LoanCtx[] {
  const found: { ctx: LoanCtx; index: number }[] = []
  for (const ctx of ctxs) {
    const name = ctx.loan.customerName
    const first = name.split(' ')[0]
    const patterns = [name, first, ctx.loan.id].map((p) => new RegExp(`\\b${escapeRegExp(p)}(?:'s)?\\b`, 'i'))
    const hits = patterns.map((p) => question.search(p)).filter((i) => i >= 0)
    if (hits.length > 0) found.push({ ctx, index: Math.min(...hits) })
  }
  return found.sort((a, b) => a.index - b.index).map((x) => x.ctx)
}

const FOLLOW_UPS = [
  'What is the biggest expense in this loan?',
  'Which loans have negative estimated contributions?',
  'Which loan products have the highest average contribution?',
  'Compare Rahul’s loan with Aman’s loan.',
]

const base = (answer: string, evidence: AiEvidenceDto[], related: string[], suggestions = FOLLOW_UPS.slice(0, 3)): AiChatResponseDto => ({
  answer,
  source: 'demo-scripted',
  evidence,
  relatedLoanIds: related,
  suggestions,
})

function breakdownEvidence({ loan, f }: LoanCtx, period: ReportingPeriod): AiEvidenceDto {
  return {
    title: `${loan.customerName} · ${loan.id}`,
    loanId: loan.id,
    periodLabel: periodRangeLabel(period),
    items: [
      { label: 'Interest income', amount: r(f.interestIncome), tone: 'income' },
      { label: 'Fee income', amount: r(f.feeIncome), tone: 'income' },
      { label: 'Funding cost', amount: -r(f.fundingCost), tone: 'expense' },
      { label: 'Operational cost', amount: -r(f.operationalCost), tone: 'expense' },
      { label: 'Contribution before ECL', amount: r(f.contributionBeforeEcl), tone: 'total' },
      { label: 'Expected credit loss (estimate)', amount: -r(f.expectedCreditLoss), tone: 'estimate' },
      { label: 'Estimated contribution', amount: r(f.contributionAfterEcl), tone: 'total' },
    ],
  }
}

/* ---------------- Scripted explanations ---------------- */

function explainContribution(c: LoanCtx, period: ReportingPeriod): AiChatResponseDto {
  const { loan, f } = c
  const name = loan.customerName
  const range = periodRangeLabel(period)
  const paragraphs: string[] = []

  if (f.contributionStatus === 'negative') {
    paragraphs.push(`${name}'s loan (${loan.id}) has a negative estimated contribution of ${formatINR(-f.contributionAfterEcl)} for the selected reporting period (${range}).`)
    if (f.contributionBeforeEcl >= 0) {
      paragraphs.push(
        `Its contribution before expected credit loss was positive at ${formatINR(f.contributionBeforeEcl)}: total income of ${formatINR(f.totalIncome)} less funding cost of ${formatINR(f.fundingCost)} and operational cost of ${formatINR(f.operationalCost)}.`,
      )
      paragraphs.push(`However, the expected credit loss estimate is ${formatINR(f.expectedCreditLoss)}, which reduces the final estimated contribution to ${formatINR(f.contributionAfterEcl)}.`)
    } else {
      paragraphs.push(
        `The contribution is negative even before expected credit loss, at ${formatINR(f.contributionBeforeEcl)}: total income of ${formatINR(f.totalIncome)} does not cover funding cost of ${formatINR(f.fundingCost)} and operational cost of ${formatINR(f.operationalCost)}.`,
      )
      paragraphs.push(`The expected credit loss estimate of ${formatINR(f.expectedCreditLoss)} reduces it further to ${formatINR(f.contributionAfterEcl)}.`)
    }
    paragraphs.push(`This does not confirm that ${name} has defaulted or that the bank has already lost this amount. Expected credit loss is a model-based estimate (PD × LGD × EAD).`)
  } else if (f.contributionStatus === 'positive') {
    paragraphs.push(`${name}'s loan (${loan.id}) has a positive estimated contribution of ${formatINR(f.contributionAfterEcl)} for the selected reporting period (${range}), so it is not showing a negative contribution.`)
    paragraphs.push(
      `Contribution before expected credit loss is ${formatINR(f.contributionBeforeEcl)}. The expected credit loss estimate of ${formatINR(f.expectedCreditLoss)} is smaller than that, which leaves ${formatINR(f.contributionAfterEcl)}.`,
    )
  } else {
    paragraphs.push(`${name}'s loan (${loan.id}) is at break-even for the selected reporting period (${range}), with an estimated contribution of ${formatINR(f.contributionAfterEcl)}.`)
    paragraphs.push(`Contribution before expected credit loss is ${formatINR(f.contributionBeforeEcl)}; the expected credit loss estimate of ${formatINR(f.expectedCreditLoss)} brings it to about zero.`)
  }
  return base(paragraphs.join('\n\n'), [breakdownEvidence(c, period)], [loan.id])
}

function explainBiggestExpense(c: LoanCtx, period: ReportingPeriod): AiChatResponseDto {
  const { loan, f } = c
  const expenses = [
    { label: 'Funding cost', value: f.fundingCost },
    { label: 'Operational cost', value: f.operationalCost },
    { label: 'Expected credit loss (an estimate)', value: f.expectedCreditLoss },
  ].sort((a, b) => b.value - a.value)
  const top = expenses[0]
  const share = f.totalExpenses ? Math.round((top.value * 1000) / f.totalExpenses) / 10 : 0
  const paragraphs = [
    `The largest expense on ${loan.customerName}'s loan (${loan.id}) is ${top.label.toLowerCase()} at ${formatINR(top.value)}, about ${share}% of total expenses of ${formatINR(f.totalExpenses)} for ${periodRangeLabel(period)}.`,
    `The others are ${expenses[1].label.toLowerCase()} at ${formatINR(expenses[1].value)} and ${expenses[2].label.toLowerCase()} at ${formatINR(expenses[2].value)}.`,
  ]
  if (top.label.startsWith('Expected')) paragraphs.push('Note that expected credit loss is an estimate of potential future loss, not an expense the bank has actually recorded.')
  return base(
    paragraphs.join('\n\n'),
    [
      {
        title: `Expenses · ${loan.customerName} · ${loan.id}`,
        loanId: loan.id,
        periodLabel: periodRangeLabel(period),
        items: [
          { label: 'Funding cost', amount: -r(f.fundingCost), tone: 'expense' },
          { label: 'Operational cost', amount: -r(f.operationalCost), tone: 'expense' },
          { label: 'Expected credit loss (estimate)', amount: -r(f.expectedCreditLoss), tone: 'estimate' },
          { label: 'Total expenses', amount: -r(f.totalExpenses), tone: 'total' },
        ],
      },
    ],
    [loan.id],
  )
}

function explainPortfolioExpenses(ctxs: LoanCtx[], period: ReportingPeriod): AiChatResponseDto {
  const sum = (pick: (f: LoanFinancials) => number) => ctxs.reduce((t, c) => t + pick(c.f), 0)
  const funding = sum((f) => f.fundingCost)
  const ops = sum((f) => f.operationalCost)
  const ecl = sum((f) => f.expectedCreditLoss)
  const total = funding + ops + ecl
  const ranked = [
    { label: 'Funding cost', value: funding },
    { label: 'Operational cost', value: ops },
    { label: 'Expected credit loss (an estimate)', value: ecl },
  ].sort((a, b) => b.value - a.value)
  return base(
    `Across the portfolio, the biggest expense is ${ranked[0].label.toLowerCase()} at ${formatINR(ranked[0].value)}, about ${Math.round((ranked[0].value * 1000) / total) / 10}% of total expenses of ${formatINR(total)} for ${periodRangeLabel(period)}.\n\nSelect a loan, or name one in your question, and I can break down that loan's expenses.`,
    [
      {
        title: 'Portfolio expenses',
        periodLabel: periodRangeLabel(period),
        items: [
          { label: 'Funding cost', amount: -r(funding), tone: 'expense' },
          { label: 'Operational cost', amount: -r(ops), tone: 'expense' },
          { label: 'Expected credit loss (estimate)', amount: -r(ecl), tone: 'estimate' },
          { label: 'Total expenses', amount: -r(total), tone: 'total' },
        ],
      },
    ],
    [],
  )
}

function explainEcl(c: LoanCtx, period: ReportingPeriod): AiChatResponseDto {
  const { loan, f } = c
  const k = f.credit
  const text = [
    `The expected credit loss for ${loan.customerName}'s loan (${loan.id}) is ${formatINR(f.expectedCreditLoss)} for ${periodRangeLabel(period)}. It comes from ECL = PD × LGD × EAD = ${k.pdPct}% × ${k.lgdPct}% × ${formatINR(k.ead)} = ${formatINR(k.eclTwelveMonth)} on a 12-month basis.`,
    'I cannot say why it has changed: this demonstration data holds only the current PD, LGD and exposure, with no history of earlier estimates. What I can show is what drives it. ECL rises when the probability of default or the loss given default rises, or when the exposure grows.',
    'Expected credit loss is an estimate of potential future loss. It does not mean the borrower has defaulted or that the bank has lost this amount.',
  ]
  return base(
    text.join('\n\n'),
    [
      {
        title: `Credit risk inputs · ${loan.customerName} · ${loan.id}`,
        loanId: loan.id,
        periodLabel: periodRangeLabel(period),
        items: [
          { label: 'Probability of Default (PD)', text: `${k.pdPct}%`, tone: 'neutral' },
          { label: 'Loss Given Default (LGD)', text: `${k.lgdPct}%`, tone: 'neutral' },
          { label: 'Exposure at Default (EAD)', amount: r(k.ead), tone: 'neutral' },
          { label: 'Expected credit loss, 12 months (estimate)', amount: -r(k.eclTwelveMonth), tone: 'estimate' },
        ],
      },
    ],
    [loan.id],
  )
}

function explainPortfolioEcl(ctxs: LoanCtx[], period: ReportingPeriod): AiChatResponseDto {
  const total = ctxs.reduce((t, c) => t + c.f.expectedCreditLoss, 0)
  const top = [...ctxs].sort((a, b) => b.f.expectedCreditLoss - a.f.expectedCreditLoss).slice(0, 4)
  return base(
    `Portfolio expected credit loss is ${formatINR(total)} for ${periodRangeLabel(period)}. I cannot say why it has changed, because this demonstration data has no history of earlier estimates. It is driven by each loan's PD, LGD and exposure.\n\nThe four largest contributors are shown below. These are estimates, not recorded losses.`,
    [
      {
        title: 'Largest expected credit loss estimates',
        periodLabel: periodRangeLabel(period),
        items: top.map((c) => ({ label: `${c.loan.customerName} (${c.loan.id})`, amount: -r(c.f.expectedCreditLoss), tone: 'estimate' as const, loanId: c.loan.id })),
      },
    ],
    top.map((c) => c.loan.id),
  )
}

function compareLoans(a: LoanCtx, b: LoanCtx, period: ReportingPeriod): AiChatResponseDto {
  const diffs = [
    { label: 'interest income', x: a.f.interestIncome, y: b.f.interestIncome },
    { label: 'funding cost', x: a.f.fundingCost, y: b.f.fundingCost },
    { label: 'operational cost', x: a.f.operationalCost, y: b.f.operationalCost },
    { label: 'expected credit loss', x: a.f.expectedCreditLoss, y: b.f.expectedCreditLoss },
  ].sort((p, q) => Math.abs(q.x - q.y) - Math.abs(p.x - p.y))
  const biggest = diffs[0]
  const range = periodRangeLabel(period)
  const text = [
    `For ${range}, ${a.loan.customerName}'s loan (${a.loan.id}) has an estimated contribution of ${formatINR(a.f.contributionAfterEcl)}, compared with ${formatINR(b.f.contributionAfterEcl)} for ${b.loan.customerName}'s loan (${b.loan.id}).`,
    `Before expected credit loss they are ${formatINR(a.f.contributionBeforeEcl)} and ${formatINR(b.f.contributionBeforeEcl)}. The expected credit loss estimates are ${formatINR(a.f.expectedCreditLoss)} and ${formatINR(b.f.expectedCreditLoss)}.`,
    `The largest difference between the two is ${biggest.label}: ${formatINR(biggest.x)} for ${a.loan.customerName} against ${formatINR(biggest.y)} for ${b.loan.customerName}.`,
    'Expected credit loss is an estimate, not a recorded loss.',
  ]
  return base(text.join('\n\n'), [breakdownEvidence(a, period), breakdownEvidence(b, period)], [a.loan.id, b.loan.id])
}

function listNegative(ctxs: LoanCtx[], period: ReportingPeriod): AiChatResponseDto {
  const negative = ctxs.filter((c) => c.f.contributionStatus === 'negative').sort((x, y) => x.f.contributionAfterEcl - y.f.contributionAfterEcl)
  const range = periodRangeLabel(period)
  if (negative.length === 0) {
    return base(`No loans have a negative estimated contribution for ${range}.`, [], [])
  }
  const eclDriven = negative.filter((c) => c.f.contributionBeforeEcl >= 0).length
  return base(
    `${negative.length} of ${ctxs.length} loans have a negative estimated contribution for ${range}. For ${eclDriven} of them the contribution before expected credit loss is positive, so the estimate of credit loss is what turns it negative.\n\nA negative estimated contribution does not mean the borrower has defaulted or that the bank has already lost money.`,
    [
      {
        title: 'Negative estimated contribution',
        periodLabel: range,
        items: negative.map((c) => ({ label: `${c.loan.customerName} (${c.loan.id})`, amount: r(c.f.contributionAfterEcl), tone: 'total' as const, loanId: c.loan.id })),
      },
    ],
    negative.map((c) => c.loan.id),
  )
}

function productAverages(ctxs: LoanCtx[], period: ReportingPeriod): AiChatResponseDto {
  const groups = new Map<LoanType, LoanCtx[]>()
  for (const c of ctxs) groups.set(c.loan.loanType, [...(groups.get(c.loan.loanType) ?? []), c])
  const rows = [...groups.entries()]
    .map(([type, list]) => ({ type, count: list.length, average: Math.round(list.reduce((t, c) => t + c.f.contributionAfterEcl, 0) / list.length) }))
    .sort((x, y) => y.average - x.average)
  const top = rows[0]
  const bottom = rows[rows.length - 1]
  return base(
    `For ${periodRangeLabel(period)}, ${top.type}s have the highest average estimated contribution at ${formatINR(top.average)} per loan (${top.count} loan${top.count > 1 ? 's' : ''}). The lowest is ${bottom.type}s at ${formatINR(bottom.average)} per loan.\n\nThese are averages over the small number of loans in the demonstration data, after expected credit loss estimates.`,
    [
      {
        title: 'Average estimated contribution by loan product',
        periodLabel: periodRangeLabel(period),
        items: rows.map((row) => ({ label: `${row.type} · ${row.count} loan${row.count > 1 ? 's' : ''}`, amount: r(row.average), tone: 'total' as const })),
      },
    ],
    [],
  )
}

function fallback(): AiChatResponseDto {
  return base(
    'I can explain how a loan’s estimated contribution is built up (income, funding cost, operational cost and expected credit loss), compare two loans, list loans with a negative estimated contribution, and show average contribution by loan product.\n\nTry one of the suggested questions, or name a customer or loan ID.',
    [],
    [],
    FOLLOW_UPS,
  )
}

/* ---------------- Question routing ---------------- */

export function answerDemoQuestion(req: AiChatRequestDto, loans: Loan[], period: ReportingPeriod): AiChatResponseDto {
  const ctxs: LoanCtx[] = loans.map((loan) => ({ loan, f: computeLoanFinancials(loan, period) }))
  const q = req.question.toLowerCase()
  const mentioned = findMentionedLoans(req.question, ctxs)
  const focused = ctxs.find((c) => c.loan.id === req.loanId)
  const target = mentioned[0] ?? focused

  if (/\b(compare|comparison|versus|vs)\b/.test(q)) {
    const pair = [...mentioned]
    if (pair.length < 2 && focused && !pair.includes(focused)) pair.unshift(focused)
    if (pair.length >= 2) return compareLoans(pair[0], pair[1], period)
    return base('Tell me which two loans to compare, for example “Compare Rahul’s loan with Aman’s loan”.', [], [], FOLLOW_UPS)
  }
  if (/(which|what|list|show).*loans?.*negative|negative.*loans?/.test(q) && mentioned.length === 0) return listNegative(ctxs, period)
  if (/(product|loan type|loan products?|by type)/.test(q) || /highest average/.test(q)) return productAverages(ctxs, period)
  if (/(biggest|largest|main|top|highest).*(expense|cost)|expense/.test(q)) return target ? explainBiggestExpense(target, period) : explainPortfolioExpenses(ctxs, period)
  if (/\becl\b|expected credit loss|credit loss/.test(q)) return target ? explainEcl(target, period) : explainPortfolioEcl(ctxs, period)
  if (target && /(why|explain|negative|contribution|profit|loss|positive|how)/.test(q)) return explainContribution(target, period)
  if (target) return explainContribution(target, period)
  return fallback()
}
