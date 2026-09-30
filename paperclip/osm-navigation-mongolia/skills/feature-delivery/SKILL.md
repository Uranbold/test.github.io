---
name: feature-delivery
description: "Use to deliver one triaged, board-prioritised feature: BA -> (UX || Architect) -> (Backend || Mobile) -> (QA || Architect review) -> fix loop"
---

The CEO creates one issue per step, with blocker dependencies:

1. **business-analyst:** write or update `docs/requirements/stories/NAV-XXX-*.md` with testable Given/When/Then AC, edge cases and data risks, plus the flags `needs_design`, `needs_backend` and `needs_mobile`. Blocking questions go to the board, and the flow **stops** until they're answered.
2. In parallel:
   - **ux-designer** (if `needs_design`): flows and screen specs with every state (loading, empty, error, offline, GPS lost) and copy in mn and en.
   - **architect:** update `openapi.yaml` for every endpoint, write an ADR if the decision is significant, and give a task breakdown for backend and mobile.
3. In parallel:
   - **backend-engineer** (if `needs_backend`)
   - **mobile-engineer** (if `needs_mobile`)

   Each implements against the contract and specs, then builds and tests.
4. In parallel:
   - **qa-engineer:** test plan, then tests (API, E2E, GPX), run them, and report pass or fail with defects by severity and owner.
   - **architect:** integration review covering contract conformance, errors, localisation, attribution, privacy and performance.
5. **Fix loop:** blocker and major issues go back to their owners, for at most 2 rounds. If an issue belongs to the BA, UX or Architect (a spec or decision problem), **stop and ask the board** instead of looping.
6. **Done** when all AC pass, the review has no blocker or major issues, and the board accepts. Record the outcome and commit.
