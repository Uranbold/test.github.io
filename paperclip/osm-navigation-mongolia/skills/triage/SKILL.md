---
name: triage
description: "Use on every new incoming item (feature, change, bug, question, tech debt) before any delivery work: validate, dedupe, classify, propose priority, pick a lane"
---

Full design: `docs/team/intake-and-triage-flow.md` in the repository.

1. **Validate.** Is it a duplicate (search open and closed issues, stories, `docs/triage/log.md`)? "Works as designed" means a change request, not a bug. Already fixed? A wrong OSM street, road or turn restriction is `area:data-osm`: a mapping task for a human mapper, with no code change and no automatic OSM edit.
2. **Classify.**
   - type: feature, change, bug, spike or tech-debt
   - areas: routing, search, tiles, gateway, android, ios, web, design, data-osm
   - the linked story, if any
3. **Severity (bugs):**
   - **S1** only for an outage, a crash on start, dangerous or illegal guidance, or a data or privacy leak
   - **S2** a core feature is broken
   - **S3** the feature is degraded but has a workaround
   - **S4** cosmetic
4. **Propose** priority P0–P3 and a class (expedite, fixed-date, standard, intangible). Only S1 may be expedite. **The board decides.**
5. **Lane:** hotfix (S1 only), bug, change, feature (tech debt uses feature with the intangible class), spike, or close.
6. **Definition of Ready** missing → `needs_info` with exact questions for the reporter. Never invent repro steps, coordinates or versions.
7. **Record** a row in `docs/triage/log.md` (English). Write `summary_for_po` and any GitHub comment in **Mongolian**, using glossary terms.
