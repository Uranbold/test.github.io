export const meta = {
  name: 'spike',
  description: 'Spike lane: timeboxed research by the architect (technical) or business analyst (product), challenged by a skeptic, producing a recommendation and follow-up items',
  whenToUse: 'For triaged questions that must be answered before stories can be written. Args: {title, brief, kind: "technical"|"product", issue?}.',
  phases: [
    { title: 'Research', detail: 'one timeboxed research pass that writes a spike doc' },
    { title: 'Challenge', detail: 'an independent reviewer tries to refute the recommendation' },
  ],
}

const input = args || {}
if (!input.brief) throw new Error('Pass {title, brief, kind} as args.')
const owner = input.kind === 'product' ? 'business-analyst' : 'architect'
const docDir = owner === 'architect' ? 'docs/architecture/spikes/' : 'docs/requirements/spikes/'

const SPIKE = {
  type: 'object',
  properties: {
    answer: { type: 'string' },
    options: {
      type: 'array',
      items: { type: 'object', properties: { name: { type: 'string' }, pros: { type: 'string' }, cons: { type: 'string' } }, required: ['name'] },
    },
    recommendation: { type: 'string' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
    doc_path: { type: 'string' },
    sources: { type: 'array', items: { type: 'string' } },
    follow_up_items: {
      type: 'array',
      items: {
        type: 'object',
        properties: { type: { type: 'string', enum: ['feature', 'change', 'spike', 'tech-debt', 'adr'] }, title: { type: 'string' }, brief: { type: 'string' } },
        required: ['type', 'title', 'brief'],
      },
    },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['answer', 'recommendation', 'confidence', 'doc_path', 'follow_up_items'],
}
const CHALLENGE = {
  type: 'object',
  properties: {
    holds: { type: 'boolean' },
    concerns: { type: 'array', items: { type: 'string' } },
    missing_evidence: { type: 'array', items: { type: 'string' } },
    suggested_change: { type: 'string' },
  },
  required: ['holds', 'concerns'],
}

const ctx = `Spike${input.issue ? ` (GitHub issue #${input.issue})` : ''}: ${input.title || ''}
"""
${input.brief}
"""`

phase('Research')
const s = await agent(`${ctx}

Answer this question with a TIMEBOXED research pass (${input.timebox || 'one run'}): compare realistic options, cite sources,
and write the result to ${docDir}<slug>.md. If it's a significant technical decision, also draft an ADR with status "proposed".
No production code. List follow-up items (stories, change requests, ADRs) for triage. Don't make the product decision
yourself; recommend one.`,
  { label: `${owner}: research`, phase: 'Research', agentType: owner, schema: SPIKE })
if (!s) throw new Error(`${owner} returned no result`)

phase('Challenge')
const c = await agent(`${ctx}

Another agent answered this spike (doc: ${s.doc_path}):
${JSON.stringify({ answer: s.answer, recommendation: s.recommendation, options: s.options, sources: s.sources }, null, 2)}

Act as an independent skeptic. Read the doc and try to REFUTE the recommendation: wrong facts, missing options, unsupported
claims, a poor fit for Mongolia or our stack (ADR-0001). Do not modify files. holds=false if a concern would change the recommendation.`,
  { label: 'skeptic: challenge', phase: 'Challenge', agentType: owner === 'architect' ? 'business-analyst' : 'architect', schema: CHALLENGE })

log(`Spike answered (${s.confidence} confidence); recommendation ${c && c.holds ? 'held up' : 'was challenged'}`)
return { spike: s, challenge: c, next: 'present to PO; send follow_up_items through triage' }
