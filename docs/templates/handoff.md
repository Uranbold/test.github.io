# Handoff block (end of every agent task)

Every agent ends its final response with this block, filled in. The orchestrator reads it to decide the next step.

```yaml
handoff:
  agent: <architect|business-analyst|ux-designer|backend-engineer|mobile-engineer|qa-engineer>
  story: NAV-XXX
  status: done | partial | blocked
  summary: <2–4 sentences on what was done>
  files_changed:
    - path/to/file
  verification:
    ran:
      - "<command>  →  <result>"
    not_verified:
      - "<what was not checked and why>"
  open_questions:            # decisions only the user can make
    - question: <...>
      options: [<a>, <b>]
      recommendation: <a>
  requests_to_other_agents:  # changes needed in paths you don't own
    - to: <agent>
      request: <...>
  next_step: <what should happen next>
```
