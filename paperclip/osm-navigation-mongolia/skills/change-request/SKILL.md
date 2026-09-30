---
name: change-request
description: "Use when an existing story must change: impact analysis, board approval, then update story -> design/contract -> code -> tests in dependency order, then regression"
---

1. **Impact analysis (read-only, in parallel):**
   - **business-analyst:** stories, AC and backlog.
   - **architect:** contract, ADRs, code areas, tests, and conflicts with in-progress work.

   List every affected artifact, the size and the risks. **Stop and ask the board to approve** the impact.
2. **business-analyst:** update the **existing** story (never create a parallel one). Revise the AC, add a Change log row, and update Traceability.
3. In parallel:
   - **ux-designer:** update affected specs.
   - **architect:** update `openapi.yaml` and ADRs, keeping backward compatibility or documenting the break.
4. In parallel, **backend-engineer** and **mobile-engineer** implement.
5. **qa-engineer:** update every test of the old behaviour (never leave one failing or delete it without a replacement), then run the **full** regression. At the same time, the **architect** reviews consistency.
6. Fix loop as in `feature-delivery`, with escalation for spec or decision issues.
