export const meta = {
  name: 'bug-fix',
  description: 'Bug lane: QA reproduces with a failing test -> owner fixes -> QA verifies + regression (-> architect review if the contract is touched) -> fix loop',
  whenToUse: 'After triage routed a bug and the PO confirmed priority. Args: {title, brief, storyId?, issue?, severity?, areas?, mode?: "bug"|"hotfix"}.',
  phases: [
    { title: 'Reproduce', detail: 'qa-engineer writes a failing test and confirms severity' },
    { title: 'Fix', detail: 'owner agent finds the root cause and fixes it' },
    { title: 'Verify', detail: 'failing test passes, regression green; architect review if needed' },
  ],
}

const input = args || {}
if (!input.title || !input.brief) throw new Error('Pass {title, brief, ...} as args (use the triage output).')
const HOTFIX = input.mode === 'hotfix'
const MAX_ROUNDS = 2
const OWNERS = ['backend-engineer', 'mobile-engineer', 'ux-designer']

const HANDOFF_PROPS = {
  status: { type: 'string', enum: ['done', 'partial', 'blocked'] },
  summary: { type: 'string' },
  files_changed: { type: 'array', items: { type: 'string' } },
  verification_ran: { type: 'array', items: { type: 'string' } },
  not_verified: { type: 'array', items: { type: 'string' } },
  open_questions: { type: 'array', items: { type: 'string' } },
  requests_to_other_agents: { type: 'array', items: { type: 'object', properties: { to: { type: 'string' }, request: { type: 'string' } }, required: ['to', 'request'] } },
}
const REPRO = {
  type: 'object',
  properties: {
    ...HANDOFF_PROPS,
    outcome: { type: 'string', enum: ['reproduced', 'cannot_reproduce', 'works_as_designed', 'data_osm'] },
    severity: { type: 'string', enum: ['S1', 'S2', 'S3', 'S4'] },
    severity_rationale: { type: 'string' },
    owners: { type: 'array', items: { type: 'string', enum: OWNERS } },
    failing_test: { type: 'string', description: 'Path + name of the test that fails because of the bug' },
    evidence: { type: 'string' },
    root_cause_hypothesis: { type: 'string' },
    missing_info: { type: 'array', items: { type: 'string' } },
  },
  required: ['summary', 'outcome', 'severity', 'owners', 'files_changed'],
}
const FIX = {
  type: 'object',
  properties: {
    ...HANDOFF_PROPS,
    root_cause: { type: 'string' },
    touched_contract_or_architecture: { type: 'boolean' },
  },
  required: ['status', 'summary', 'files_changed', 'root_cause', 'touched_contract_or_architecture'],
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
          owner: { type: 'string', enum: [...OWNERS, 'architect'] },
          description: { type: 'string' },
        },
        required: ['severity', 'owner', 'description'],
      },
    },
  },
  required: ['summary', 'passed', 'issues'],
}

const ctx = `Bug${input.issue ? ` (GitHub issue #${input.issue})` : ''}: ${input.title}
${input.storyId ? `Related story: ${input.storyId}\n` : ''}Triage severity: ${input.severity || 'unknown'}; areas: ${(input.areas || []).join(', ') || 'unknown'}
Report:
"""
${input.brief}
"""
Lane: ${HOTFIX ? 'HOTFIX (S1, expedite). Minimal, safe change only; no refactoring; no scope additions.' : 'standard bug lane.'}`

// 1. Reproduce
phase('Reproduce')
const repro = await agent(`${ctx}

Reproduce this bug BEFORE anyone fixes it. Write an automated test in tests/ that FAILS because of the bug
(API test, E2E, or GPX simulation, whichever fits), run it and show it failing. Confirm the severity with the matrix in
docs/team/intake-and-triage-flow.md §3.4. Decide the owner(s) by where the defect lives.
- If the behaviour matches the story's acceptance criteria → outcome=works_as_designed (it's a change request).
- If the cause is wrong OpenStreetMap source data → outcome=data_osm.
- If you cannot reproduce with the given info → outcome=cannot_reproduce and list missing_info.`,
  { label: 'qa-engineer: reproduce', phase: 'Reproduce', agentType: 'qa-engineer', schema: REPRO })
if (!repro) throw new Error('qa-engineer returned no result')

if (repro.outcome !== 'reproduced') {
  const next = { cannot_reproduce: 'needs_info', works_as_designed: 'reclassify_as_change_request', data_osm: 'create_osm_mapping_task' }[repro.outcome]
  log(`Stopped after reproduction: ${repro.outcome}`)
  return { outcome: repro.outcome, next, repro }
}
if (HOTFIX && repro.severity !== 'S1') {
  log(`QA rated ${repro.severity}, not S1: leaving the hotfix lane`)
  return { outcome: 'downgraded', next: 'run_bug_fix_lane', severity: repro.severity, repro }
}
const owners = repro.owners.length ? repro.owners : ['backend-engineer']

// 2. Fix, then 3. verify, with a fix loop
const fixPrompt = (owner, extra) => `${ctx}

QA reproduction:
${JSON.stringify({ failing_test: repro.failing_test, evidence: repro.evidence, hypothesis: repro.root_cause_hypothesis }, null, 2)}
${extra || ''}
Find the ROOT CAUSE in the code you own and fix it${HOTFIX ? ' with the smallest safe change' : ''}. Make the failing test pass
without weakening or deleting it. Run your build/tests. If the fix needs an API-contract change, do NOT edit openapi.yaml.
Set touched_contract_or_architecture=true and request it from the architect.`

phase('Fix')
let fixes = (await parallel(owners.map(o => () =>
  agent(fixPrompt(o), { label: `${o}: fix`, phase: 'Fix', agentType: o, schema: FIX })))).filter(Boolean)

const verify = async (round) => {
  const needArch = !HOTFIX && fixes.some(f => f.touched_contract_or_architecture ||
    (f.requests_to_other_agents || []).some(r => r.to === 'architect'))
  const fixText = JSON.stringify(fixes.map(f => ({ summary: f.summary, root_cause: f.root_cause, files: f.files_changed })), null, 2)
  const res = await parallel([
    () => agent(`${ctx}\n\nFixes:\n${fixText}\n\nVerify: the failing test ${repro.failing_test || ''} must now pass unchanged, then run the
${HOTFIX ? 'smoke tests' : 'full regression suite'}. Set passed=true only if both are green. List any new defect with owner.`,
      { label: `qa-engineer: verify (round ${round})`, phase: 'Verify', agentType: 'qa-engineer', schema: REVIEW }),
    () => needArch
      ? agent(`${ctx}\n\nFixes:\n${fixText}\n\nReview these fixes: contract conformance, architecture, side effects. Apply any contract change
the owners requested if it is correct. passed=true only if there are no blocker/major issues.`,
        { label: `architect: review (round ${round})`, phase: 'Verify', agentType: 'architect', schema: REVIEW })
      : Promise.resolve(null),
  ])
  if (!res[0]) res[0] = { passed: false, issues: [], summary: 'qa-engineer returned no result; not verified' }
  return res.filter(Boolean)
}

phase('Verify')
let reviews = await verify(0)
const open = () => reviews.flatMap(r => r.issues || []).filter(i => i.severity !== 'minor')
const failed = () => reviews.some(r => !r.passed)
let round = 0
while ((failed() || open().length) && round < MAX_ROUNDS) {
  round++
  const issues = open()
  const targets = issues.length ? [...new Set(issues.map(i => i.owner).filter(o => OWNERS.includes(o)))] : owners
  log(`Fix round ${round}: ${issues.length} blocker/major issue(s), owners: ${targets.join(', ')}`)
  const newFixes = (await parallel(targets.map(o => () =>
    agent(fixPrompt(o, `\nVerification failed. Issues:\n${JSON.stringify(issues.filter(i => i.owner === o), null, 2)}\nQA summary: ${reviews.map(r => r.summary).join(' | ')}`),
      { label: `${o}: fix (round ${round})`, phase: 'Fix', agentType: o, schema: FIX })))).filter(Boolean)
  fixes = [...fixes, ...newFixes]
  reviews = await verify(round)
}

const accepted = !failed() && open().length === 0
log(accepted ? 'Bug fixed and verified' : `NOT verified: ${open().length} blocker/major issue(s) remain`)
return {
  outcome: accepted ? 'fixed' : 'not_fixed',
  accepted,
  severity: repro.severity,
  failing_test: repro.failing_test,
  root_causes: fixes.map(f => f.root_cause),
  fix_rounds: round,
  remaining_issues: open(),
  follow_up: HOTFIX
    ? ['Within 48 h: write a root-cause note, keep the regression test, and check whether the story AC had a gap (business-analyst)']
    : [],
  handoffs: { repro, fixes, reviews },
}
