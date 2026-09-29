export const meta = {
  name: 'feature-delivery',
  description: 'Run one feature through the agent team: BA -> UX + Architect -> Backend + Mobile -> QA + review -> fix loop',
  whenToUse: 'Deliver a single navigation feature end to end. Pass the feature description (string) or {feature, storyId} as args.',
  phases: [
    { title: 'Requirements', detail: 'business-analyst writes the story and acceptance criteria' },
    { title: 'Design', detail: 'ux-designer and architect in parallel' },
    { title: 'Build', detail: 'backend-engineer and mobile-engineer in parallel' },
    { title: 'Verify', detail: 'qa-engineer tests, architect reviews integration' },
    { title: 'Fix', detail: 'owners fix blocker/major issues, then re-verify (max 2 rounds)' },
  ],
}

const input = typeof args === 'string' ? { feature: args } : (args || {})
if (!input.feature) throw new Error('Pass the feature description as args (string or {feature, storyId}).')
const MAX_FIX_ROUNDS = 2

const QUESTION = {
  type: 'object',
  properties: {
    question: { type: 'string' },
    options: { type: 'array', items: { type: 'string' } },
    recommendation: { type: 'string' },
    blocking: { type: 'boolean' },
  },
  required: ['question', 'blocking'],
}

const HANDOFF = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['done', 'partial', 'blocked'] },
    summary: { type: 'string' },
    files_changed: { type: 'array', items: { type: 'string' } },
    verification_ran: { type: 'array', items: { type: 'string' } },
    not_verified: { type: 'array', items: { type: 'string' } },
    open_questions: { type: 'array', items: QUESTION },
    requests_to_other_agents: {
      type: 'array',
      items: {
        type: 'object',
        properties: { to: { type: 'string' }, request: { type: 'string' } },
        required: ['to', 'request'],
      },
    },
  },
  required: ['status', 'summary', 'files_changed', 'open_questions'],
}

const BA_RESULT = {
  type: 'object',
  properties: {
    ...HANDOFF.properties,
    story_id: { type: 'string' },
    story_path: { type: 'string' },
    needs_design: { type: 'boolean' },
    needs_backend: { type: 'boolean' },
    needs_mobile: { type: 'boolean' },
  },
  required: [...HANDOFF.required, 'story_id', 'story_path', 'needs_design', 'needs_backend', 'needs_mobile'],
}

const REVIEW = {
  type: 'object',
  properties: {
    ...HANDOFF.properties,
    passed: { type: 'boolean' },
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          owner: { type: 'string', enum: ['backend-engineer', 'mobile-engineer', 'ux-designer', 'architect', 'business-analyst'] },
          file: { type: 'string' },
          description: { type: 'string' },
        },
        required: ['severity', 'owner', 'description'],
      },
    },
  },
  required: [...HANDOFF.required, 'passed', 'issues'],
}

const handoffText = (name, h) => h
  ? `### ${name} handoff\n${JSON.stringify(h, null, 2)}`
  : `### ${name}: no result (agent skipped or failed)`

// 1. Requirements
phase('Requirements')
const story = await agent(
  `Feature request: "${input.feature}"${input.storyId ? ` (use story ID ${input.storyId})` : ''}.
Write or update the user story in docs/requirements/stories/ with Given/When/Then acceptance criteria,
edge cases and data risks, and add it to docs/requirements/backlog.md.
Mark any question that must be answered by the user before design can start as blocking=true.`,
  { label: 'business-analyst', phase: 'Requirements', agentType: 'business-analyst', schema: BA_RESULT },
)
if (!story) throw new Error('business-analyst returned no result')

const blocking = (story.open_questions || []).filter(q => q.blocking)
if (blocking.length) {
  log(`Stopped: ${blocking.length} blocking question(s) need the user's decision`)
  return { stopped_at: 'Requirements', story, blocking_questions: blocking }
}
const ctx = `Story ${story.story_id}: ${story.story_path}\nFeature: ${input.feature}`

// 2. Design (UX and architect in parallel)
phase('Design')
const [ux, arch] = await parallel([
  () => story.needs_design
    ? agent(`${ctx}\nDesign the user flow and screen specs for this story (all states, mn + en copy).
Update tokens / map style / navigation UX docs only if this story needs it.`,
      { label: 'ux-designer', phase: 'Design', agentType: 'ux-designer', schema: HANDOFF })
    : Promise.resolve(null),
  () => agent(`${ctx}\nDesign this story: update docs/architecture/api/openapi.yaml for every endpoint it needs,
write an ADR if a significant decision is involved, and include a task breakdown for backend-engineer and
mobile-engineer (each task with acceptance checks) in your summary.`,
    { label: 'architect: design', phase: 'Design', agentType: 'architect', schema: HANDOFF }),
])
const designCtx = [ctx, handoffText('ux-designer', ux), handoffText('architect', arch)].join('\n\n')

// 3. Build (backend and mobile in parallel; they own disjoint paths)
phase('Build')
const build = async (owner, needed, what, extra = '') => needed
  ? agent(`${designCtx}\n\n${extra}Implement the ${what} part of this story against openapi.yaml${owner === 'mobile-engineer' ? ' and the UX specs' : ''}.
Build and test what you change, and report the exact commands and results.`,
    { label: owner, phase: 'Build', agentType: owner, schema: HANDOFF })
  : null
let [be, mo] = await parallel([
  () => build('backend-engineer', story.needs_backend, 'backend'),
  () => build('mobile-engineer', story.needs_mobile, 'client (mobile/web)'),
])

// 4. Verify, and 5. fix loop
const verify = (round) => parallel([
  () => agent(`${designCtx}\n\n${handoffText('backend-engineer', be)}\n\n${handoffText('mobile-engineer', mo)}
Write or update the test plan for ${story.story_id}, add GPX / API / E2E tests as relevant, run them, and report.
Set passed=true only if every acceptance criterion passed. List each defect with severity and owner.`,
    { label: `qa-engineer (round ${round})`, phase: round ? 'Fix' : 'Verify', agentType: 'qa-engineer', schema: REVIEW }),
  () => agent(`${designCtx}\n\n${handoffText('backend-engineer', be)}\n\n${handoffText('mobile-engineer', mo)}
Review the integration for ${story.story_id}: contract conformance, error handling, localisation, OSM attribution,
privacy and performance targets. Set passed=true only if there are no blocker or major issues.`,
    { label: `architect: review (round ${round})`, phase: round ? 'Fix' : 'Verify', agentType: 'architect', schema: REVIEW }),
])

phase('Verify')
let reviews = (await verify(0)).filter(Boolean)
const openIssues = () => reviews.flatMap(r => r.issues || []).filter(i => i.severity !== 'minor')

let round = 0
while (openIssues().length && round < MAX_FIX_ROUNDS) {
  round++
  phase('Fix')
  const issues = openIssues()
  log(`Fix round ${round}: ${issues.length} blocker/major issue(s)`)
  const forOwner = o => issues.filter(i => i.owner === o)
  const fix = (owner, prev) => forOwner(owner).length
    ? agent(`${designCtx}\n\nFix these issues found in review of ${story.story_id}, then rebuild and retest:\n${JSON.stringify(forOwner(owner), null, 2)}`,
      { label: `${owner} fix (round ${round})`, phase: 'Fix', agentType: owner, schema: HANDOFF })
    : Promise.resolve(prev)
  ;[be, mo] = await parallel([() => fix('backend-engineer', be), () => fix('mobile-engineer', mo)])
  reviews = (await verify(round)).filter(Boolean)
}

const remaining = openIssues()
const accepted = remaining.length === 0 && reviews.length === 2 && reviews.every(r => r.passed)
log(accepted ? `${story.story_id} accepted` : `${story.story_id} NOT accepted: ${remaining.length} blocker/major issue(s) remain`)

return {
  story_id: story.story_id,
  accepted,
  fix_rounds: round,
  remaining_issues: remaining,
  minor_issues: reviews.flatMap(r => r.issues || []).filter(i => i.severity === 'minor'),
  open_questions: [story, ux, arch, be, mo, ...reviews].filter(Boolean).flatMap(h => h.open_questions || []),
  cross_agent_requests: [story, ux, arch, be, mo, ...reviews].filter(Boolean).flatMap(h => h.requests_to_other_agents || []),
  handoffs: { story, ux, arch, backend: be, mobile: mo, reviews },
}
