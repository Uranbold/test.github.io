export const meta = {
  name: 'hotfix',
  description: 'Hotfix lane (S1 only, WIP limit 1): the bug-fix lane in minimal-change mode with smoke verification and a mandatory follow-up',
  whenToUse: 'Only for triaged S1 items (outage, crash on start, dangerous guidance, data leak) after the PO confirmed P0. Args: same as bug-fix.',
}

const input = args || {}
if (input.severity && input.severity !== 'S1') {
  throw new Error(`Hotfix lane is S1 only (got ${input.severity}). Run the bug-fix workflow instead.`)
}
log('Hotfix lane: other work should wait; only one hotfix at a time')
return await workflow('bug-fix', { ...input, mode: 'hotfix' })
