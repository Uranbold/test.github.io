---
name: OSM Navigation Mongolia
description: Phase 0-1 delivery of the OSM-based navigation product for Mongolia (backend stack, web demo, search, route preview, Android navigation, staging and production hosting)
slug: osm-navigation-mongolia
owner: ceo
---

Deliver the Phase 0 proof of concept and the Phase 1 MVP from the repository backlog (`docs/requirements/backlog.md`). Research and roadmap: `docs/osm-navigation-research.md`. Decisions: `docs/requirements/decisions.md`.

## Status when imported (2026-09-30)
- **NAV-001** backend stack (tiles, Valhalla, Photon, gateway): implemented and verified.
- **NAV-002** web demo map: accepted.
- **NAV-003** search with Cyrillic/Latin autocomplete: implemented; live verification and the A8 golden-row decision are pending.
- **NAV-008** staging: infra-as-code and runbook ready. Waiting for a Hostinger **VPS KVM 4 (Singapore)** and the domain.

## Milestones
1. **Phase 0 PoC:** NAV-001 to NAV-004 on the local stack.
2. **Staging live:** NAV-008 on a VPS with HTTPS; the web demo is shareable with the project team.
3. **Phase 1 MVP:** NAV-005 Android navigation with Mongolian voice and rerouting, NAV-006 daily rebuild, NAV-007 native-speaker review.
4. **Production path:** NAV-009 hosting in Mongolia, NAV-010 Hamuga data evaluation.
