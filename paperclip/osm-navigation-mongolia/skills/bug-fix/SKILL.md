---
name: bug-fix
description: "Use for a triaged bug after the board confirmed priority: QA reproduces with a failing test first, the owner fixes the root cause, QA verifies with regression"
---

1. **qa-engineer, reproduce first:** write an automated test that **fails because of the bug** and show it failing. Confirm the severity.
   - Behaviour matches the story AC → `works_as_designed`: re-triage as a change request.
   - Wrong OSM source data → `data_osm`: a mapping task.
   - Can't reproduce → `needs_info` with the missing details.
2. **Owner** (backend, mobile or UX, by where the defect lives): find the **root cause**. Fix it so the failing test passes **unchanged**; never weaken or delete it. If a contract change is needed, request it from the architect.
3. **qa-engineer:** the same test passes, and the full regression suite is green.
4. **architect:** reviews only if the contract or architecture was touched.
5. At most 2 fix rounds, then escalate to the board. The failing test stays forever as a regression test.
