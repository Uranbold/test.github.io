export const meta = {
  name: 'change-request',
  description: 'Change-request lane: impact analysis -> PO approval -> update story, design/contract, code and tests in dependency order -> regression',
  whenToUse: 'When an existing story must change. Run 1 with {title, brief, storyId, issue?} returns the impact for PO approval; run 2 adds {approvedImpact} (the impact object the PO approved) to apply it.',
  phases: [
    { title: 'Impact', detail: 'business-analyst and architect analyse impact (read-only)' },
    { title: 'Story', detail: 'business-analyst updates the story, AC and change log' },
    { title: 'Design', detail: 'ux-designer and architect update specs and contract' },
    { title: 'Build', detail: 'backend and mobile implement' },
    { title: 'Verify', detail: 'QA updates affected tests + full regression; architect and security review' },
  ],
}

const input = args || {}
if (!input.brief || !input.storyId) throw new Error('Pass {title, brief, storyId, ...} as args.')
const MAX_ROUNDS = 2

const HANDOFF_PROPS = {
  status: { type: 'string', enum: ['done', 'partial', 'blocked'] },
  summary: { type: 'string' },
  files_changed: { type: 'array', items: { type: 'string' } },
  verification_ran: { type: 'array', items: { type: 'string' } },
  not_verified: { type: 'array', items: { type: 'string' } },
  open_questions: { type: 'array', items: { type: 'string' } },
  requests_to_other_agents: { type: 'array', items: { type: 'object', properties: { to: { type: 'string' }, request: { type: 'string' } }, required: ['to', 'request'] } },
}
const HANDOFF = { type: 'object', properties: HANDOFF_PROPS, required: ['status', 'summary', 'files_changed'] }
const IMPACT = {
  type: 'object',
  properties: {
    artifacts: {
      type: 'array',
      items: { type: 'object', properties: { path: { type: 'string' }, change: { type: 'string' } }, required: ['path', 'change'] },
    },
    stories_affected: { type: 'array', items: { type: 'string' } },
    acceptance_criteria_changes: { type: 'array', items: { type: 'string' } },
    api_operations_affected: { type: 'array', items: { type: 'string' } },
    code_areas: { type: 'array', items: { type: 'string' } },
    tests_affected: { type: 'array', items: { type: 'string' } },
    conflicts_with_in_progress: { type: 'array', items: { type: 'string' } },
    needs_design: { type: 'boolean' },
    needs_backend: { type: 'boolean' },
    needs_mobile: { type: 'boolean' },
    size: { type: 'string', enum: ['S', 'M', 'L'] },
    risks: { type: 'array', items: { type: 'string' } },
    open_questions: { type: 'array', items: { type: 'string' } },
    recommendation: { type: 'string' },
  },
  required: ['artifacts', 'stories_affected', 'needs_design', 'needs_backend', 'needs_mobile', 'size', 'recommendation'],
}
const REVIEW = {
  type: 'object',
  properties: {
    ...HANDOFF_PROPS,
    passed: { type: 'boolean' },
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          owner: { type: 'string', enum: ['backend-engineer', 'mobile-engineer', 'ux-designer', 'architect', 'business-analyst'] },
          description: { type: 'string' },
        },
        required: ['severity', 'owner', 'description'],
      },
    },
  },
  required: ['summary', 'passed', 'issues'],
}

const ctx = `Change request${input.issue ? ` (GitHub issue #${input.issue})` : ''} for story ${input.storyId}: ${input.title || ''}
"""
${input.brief}
"""`
const uniq = a => [...new Set(a.filter(Boolean))]

// Run 1: impact analysis only, then stop for PO approval
if (!input.approvedImpact) {
  phase('Impact')
  const ask = role => `${ctx}

IMPACT ANALYSIS ONLY. Do not modify any files in this step.
From the ${role} point of view, list every artifact this change touches (story/AC, other stories, screen specs, openapi.yaml
operations, code modules, tests), what changes in each, conflicts with work currently in progress (check git status/log and
story statuses), risks, size, and your recommendation.`
  const [ba, arch] = await parallel([
    () => agent(ask('requirements (stories, AC, backlog, personas)'), { label: 'business-analyst: impact', phase: 'Impact', agentType: 'business-analyst', schema: IMPACT }),
    () => agent(ask('technical (contract, architecture, backend, mobile, tests)'), { label: 'architect: impact', phase: 'Impact', agentType: 'architect', schema: IMPACT }),
  ])
  const parts = [ba, arch].filter(Boolean)
  if (!parts.length) throw new Error('No impact analysis returned')
  const sizes = ['S', 'M', 'L']
  const impact = {
    artifacts: parts.flatMap(p => p.artifacts),
    stories_affected: uniq(parts.flatMap(p => p.stories_affected)),
    acceptance_criteria_changes: uniq(parts.flatMap(p => p.acceptance_criteria_changes || [])),
    api_operations_affected: uniq(parts.flatMap(p => p.api_operations_affected || [])),
    code_areas: uniq(parts.flatMap(p => p.code_areas || [])),
    tests_affected: uniq(parts.flatMap(p => p.tests_affected || [])),
    conflicts_with_in_progress: uniq(parts.flatMap(p => p.conflicts_with_in_progress || [])),
    needs_design: parts.some(p => p.needs_design),
    needs_backend: parts.some(p => p.needs_backend),
    needs_mobile: parts.some(p => p.needs_mobile),
    size: sizes[Math.max(...parts.map(p => sizes.indexOf(p.size)))],
    risks: uniq(parts.flatMap(p => p.risks || [])),
    open_questions: uniq(parts.flatMap(p => p.open_questions || [])),
    recommendations: { business_analyst: ba && ba.recommendation, architect: arch && arch.recommendation },
  }
  log(`Impact: size ${impact.size}, ${impact.artifacts.length} artifact(s), ${impact.conflicts_with_in_progress.length} in-progress conflict(s). Waiting for PO approval.`)
  return { stage: 'awaiting_po_approval', impact, rerun_with: { ...input, approvedImpact: impact } }
}

// Run 2: apply the approved change in dependency order
const impact = input.approvedImpact
const ictx = `${ctx}\n\nPO-APPROVED IMPACT (stay within it; anything beyond goes in open_questions):\n${JSON.stringify(impact, null, 2)}`
const h = (n, x) => x ? `### ${n}\n${JSON.stringify(x, null, 2)}` : `### ${n}: no result`

phase('Story')
const story = await agent(`${ictx}\n\nUpdate story ${input.storyId} (and other affected stories) to the new behaviour: revise the AC, add a
Change log entry (date, issue, what changed, why), and update the traceability table. Do not create a parallel story.`,
  { label: 'business-analyst: update story', phase: 'Story', agentType: 'business-analyst', schema: HANDOFF })

phase('Design')
const [ux, arch] = await parallel([
  () => impact.needs_design
    ? agent(`${ictx}\n\n${h('story update', story)}\n\nUpdate the affected flows and screen specs for this change only.`,
      { label: 'ux-designer: update', phase: 'Design', agentType: 'ux-designer', schema: HANDOFF })
    : Promise.resolve(null),
  () => agent(`${ictx}\n\n${h('story update', story)}\n\nUpdate openapi.yaml / ADRs for this change (keep backward compatibility or
document the breaking change), and give backend/mobile task lists in your summary.`,
    { label: 'architect: update', phase: 'Design', agentType: 'architect', schema: HANDOFF }),
])
const dctx = [ictx, h('story update', story), h('ux-designer', ux), h('architect', arch)].join('\n\n')

phase('Build')
const build = (owner, needed) => needed
  ? agent(`${dctx}\n\nImplement this change in the code you own. Update or remove code for the old behaviour; build and test.`,
    { label: owner, phase: 'Build', agentType: owner, schema: HANDOFF })
  : Promise.resolve(null)
let [be, mo] = await parallel([() => build('backend-engineer', impact.needs_backend), () => build('mobile-engineer', impact.needs_mobile)])

const verify = async round => {
  const bctx = `${dctx}\n\n${h('backend-engineer', be)}\n\n${h('mobile-engineer', mo)}`
  const res = await parallel([
    () => agent(`${bctx}\n\nUpdate every test affected by this change (tests for the OLD behaviour must be changed, not left failing
or deleted without replacement), then run the FULL regression suite. passed=true only if the changed AC pass and regression is green.`,
      { label: `qa-engineer: regression (round ${round})`, phase: 'Verify', agentType: 'qa-engineer', schema: REVIEW }),
    () => agent(`${bctx}\n\nReview: story, specs, contract, code and tests must now be consistent with each other. passed=true only
if there are no blocker/major issues.`,
      { label: `architect: review (round ${round})`, phase: 'Verify', agentType: 'architect', schema: REVIEW }),
    () => agent(`${bctx}\n\nSecurity and privacy review of this change (secrets, input validation, transport security, permissions,
location data, pack integrity, dependencies). Update docs/security/threat-model.md if a data flow or endpoint changed. Map
critical/high to blocker, medium to major, low/info to minor. passed=true only if there are no blocker/major issues.`,
      { label: `security-engineer: review (round ${round})`, phase: 'Verify', agentType: 'security-engineer', schema: REVIEW }),
  ])
  if (!res[0]) res[0] = { passed: false, issues: [], summary: 'qa-engineer returned no result; not verified' }
  return res.filter(Boolean)
}

phase('Verify')
let reviews = await verify(0)
const open = () => reviews.flatMap(r => r.issues || []).filter(i => i.severity !== 'minor')
let round = 0
while ((reviews.some(r => !r.passed) || open().length) && round < MAX_ROUNDS) {
  const issues = open()
  if (issues.length && !issues.some(i => ['backend-engineer', 'mobile-engineer'].includes(i.owner))) {
    log(`Escalating: blocker/major issue(s) need a decision or spec change (${[...new Set(issues.map(i => i.owner))].join(', ')})`)
    break
  }
  round++
  log(`Fix round ${round}: ${issues.length} blocker/major issue(s)`)
  const fix = (owner, prev) => issues.some(i => i.owner === owner)
    ? agent(`${dctx}\n\nFix these review issues:\n${JSON.stringify(issues.filter(i => i.owner === owner), null, 2)}`,
      { label: `${owner}: fix (round ${round})`, phase: 'Verify', agentType: owner, schema: HANDOFF })
    : Promise.resolve(prev)
  ;[be, mo] = await parallel([() => fix('backend-engineer', be), () => fix('mobile-engineer', mo)])
  reviews = await verify(round)
}

const accepted = reviews.every(r => r.passed) && open().length === 0
log(accepted ? `Change to ${input.storyId} applied and verified` : `NOT accepted: ${open().length} blocker/major issue(s) remain`)
return {
  stage: 'applied',
  accepted,
  fix_rounds: round,
  remaining_issues: open(),
  handoffs: { story, ux, arch, backend: be, mobile: mo, reviews },
}
