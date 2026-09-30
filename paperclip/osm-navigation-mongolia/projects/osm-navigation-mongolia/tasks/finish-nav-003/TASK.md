---
name: Finish NAV-003 search (live verification and A8 decision)
assignee: qa-engineer
project: osm-navigation-mongolia
---

Run the full live NAV-003 verification: golden tier A and tier B (tier B must be at least 80%), all `tests/e2e/nav003` suites including the accessibility cases, and the NAV-002 regression. Then the CEO asks the board to decide golden row A8 (recommended option b: the name contains «Улсын их дэлгүүр», within 400 m, in the top 3) through the `change-request` skill.

## Deliverables
- Updated `docs/qa/test-plans/NAV-003.md` run log with tier A and tier B results
- The A8 decision recorded in `docs/requirements/decisions.md`
