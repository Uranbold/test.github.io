---
name: handoff-block
description: "Use at the end of every task: the structured handoff block the CEO reads to route the next step"
---

End every task with this block, filled in (same format as `docs/templates/handoff.md` in the repository):

```yaml
handoff:
  agent: <your slug>
  story: NAV-XXX
  status: done | partial | blocked
  summary: <2-4 sentences>
  files_changed: [<paths>]
  verification:
    ran: ["<command> -> <result>"]
    not_verified: ["<what and why>"]
  open_questions:            # only the board can decide these
    - question: <...>
      options: [<a>, <b>]
      recommendation: <a>
  requests_to_other_agents:  # changes needed in paths you don't own
    - to: <agent slug>
      request: <...>
  next_step: <what should happen next>
```

Report real results only. If something didn't run, say so under `not_verified`.
