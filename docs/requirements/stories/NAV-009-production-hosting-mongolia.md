---
id: NAV-009
title: Production hosting in Mongolia (ICT Group servers), with a phone latency trial
phase: 1          # BA proposal: production must exist before NAV-005 is released to external users. PO to confirm
priority: TBD     # BA proposal: must. PO to confirm
size: L           # latency trial (about S) + production setup with two hosts, TLS, monitoring, backups, cut-over (about M–L)
needs_design: false
needs_backend: true
needs_mobile: false
status: draft     # created 2026-09-30 after PO decision D25. Blocked on host details and Hamuga/ICT Group platform info from the PO (D10)
---

# NAV-009: Production hosting in Mongolia (ICT Group servers), with a phone latency trial

## Story
As a **UB commuter by car** (and a **taxi / delivery driver** who reroutes often), I want **the map, search and routing services to answer quickly from any Mongolian mobile operator and keep working when Mongolia's international links are degraded**, so that **navigation feels instant and reliable in everyday use, and my location data stays in Mongolia**.

> **Enabler story.** It follows NAV-008 (staging on Hostinger Singapore, D25) and takes over the parts that D25 moved to production: the in-country **phone latency trial** (C1 < 50 ms) and the production host itself.

## Context
- **PO decision D25 (2026-09-30):** production runs on servers **in Mongolia**, most likely **ICT Group's own servers**. ICT Group makes Hamuga. The PO provides host details and the Hamuga tile/API docs later (D10, "open: partly answered").
- **D26:** the < 50 ms C1 target, waived for staging, **applies here**. The D4 preference (in Mongolia or own servers if the median RTT is < 50 ms) carries over to production selection.
- **Evidence so far** (`docs/architecture/spikes/nav-008-hosting.md`): domestic peering decides latency inside Mongolia. RIPE Atlas shows about 1 ms between locally interconnected UB networks, but **58–104 ms** across the MobiCom (AS55805) → Gemnet (AS45204) boundary. "Hosted in Mongolia" is therefore not automatically "fast from every operator". The spike §7 proposes two VMs that cover both upstream sides.
- **Reusable assets:** spike §5 (C1 protocol), NAV-008 criteria C1–C10, the provider-agnostic IaC from NAV-008 (ADR-0005 point 4) and the NAV-001 smoke suite.
- **Law:** in-country hosting avoids the transfer-abroad question for real users' coordinates (spike §3.3). Any component abroad (DR copy, ops VM) still needs the D9 review.

## Acceptance criteria (draft, to refine once the PO answers the open questions)
1. **Given** ICT Group host details (location, specification, public IP/hostname, upstream ISP or ASN) and **≥ 1** other in-country candidate (for example Cloud.mn or Datacom from ADR-0005, or the National Data Center / MIX), **When** the phone latency trial runs with the spike §5 protocol, **Then** for each candidate and each of **≥ 2** Mongolian mobile operators (all 4 of Unitel, MobiCom, Skytel and G-Mobile if SIMs are available), the report gives n **≥ 20** TCP/443 connect samples, median, p95, timeouts, network type, egress ASN, place (district only) and date. If ICT Group details have not arrived by the trial start, the report says "ICT Group: no details received by <date>".
2. **Given** the trial results, **When** the production host is recommended, **Then** the recommended setup has a **median < 50 ms on each of ≥ 2 operators** (C1). Any operator measured at ≥ 50 ms is listed with a mitigation (for example a second host on the other upstream, or IXP connectivity) or accepted by the PO as a recorded exception in `decisions.md`.
3. **Given** the production host, **When** `mtr --tcp --port 443` (≥ 20 cycles) runs from a phone on each operator tested in AC 1, **Then** no hop belongs to an ASN registered outside Mongolia, so user traffic does not leave the country. The result is recorded per operator.
4. **Given** production is live, **When** the data flows are reviewed, **Then** route and search requests are processed, and logs are stored, **only** on hosts physically in Mongolia. Any component abroad (DR copy, uptime checker, backup target) is listed with what it holds, and it has a D9 legal-review record dated before it goes live. The NAV-008 AC 14 log rules (no IPs, query strings or coordinates) hold on production.
5. **Given** the production base URL, **When** NAV-008 AC 6, 7, 9, 10, 11, 12, 13, 15 and 16 are re-run against it, **Then** all pass. AC 9 (postRoute p95 ≤ 500 ms tethered) is measured on each operator from AC 1.
6. **Given** clients built against staging, **When** they are switched to production, **Then** only configuration changes (base URL in resource or config files, `servers` in `openapi.yaml`). No client code changes, and the NAV-001 smoke suite exits **0** against production.
7. **Given** the provider or ICT Group, **When** operations terms are collected, **Then** the ADR records in writing: power redundancy (UPS and generator, with generator runtime in hours, which matters for the UB winter peak), SLA, maintenance windows, backup and restore, support hours and language, and who is on call. The availability target is the value the PO sets (Open question 2).

## Edge cases
- **Operator on the "other" upstream side:** AC 2 fails for that operator. Mitigate with a second host or IXP connectivity (spike §7).
- **International link degradation:** tiles, search and routing keep working for Mongolian users. Only the daily OSM download (NAV-006) pauses, and the old data keeps serving.
- **Winter power cuts in UB:** covered by AC 7 (generator runtime).
- **Countryside / weak signal:** RTT from aimag centres is recorded for information, not as a pass criterion.
- **Carrier-grade NAT:** production rate limits must not block many users behind one operator IP (see NAV-008 AC 13).
- **GPS loss, off-route, no result, Cyrillic/Latin search:** client or search behaviour, unchanged by hosting. The smoke suite covers search (AC 6).

## Data dependencies & risks
- **R1: ICT Group details not delivered** (D10). The trial runs without them, and a late answer reopens the choice.
- **R2: Vendor concentration.** If ICT Group is both the production host and a data source (Hamuga, NAV-010), one vendor outage or contract change affects hosting and data at once. The ADR records an exit path: the IaC is provider-agnostic, and the OSM stack is self-contained. PO, architect.
- **R3: Domestic peering** (spike §3.2) may force two hosts, which roughly doubles the cost (spike estimate: USD 200–260/month).
- **R4: Data quality:** production exposes OSM gaps (addresses, `maxspeed`, lanes, `name:mn`) to real users (NAV-008 R7).

## Out of scope
- Staging (NAV-008).
- Evaluating Hamuga APIs as data sources (NAV-010).
- The scheduled rebuild and blue/green switch mechanics (NAV-006). This story only provides hosts able to run them.
- Authentication and user accounts (decided before public release, D8).

## Open questions
1. **Candidate set for the trial.** Options: (a) ICT Group servers only; (b) ICT Group plus ≥ 1 commercial UB provider (Cloud.mn or Datacom); (c) (b) plus a National Data Center / MIX quote. *Recommendation: (b). One extra candidate costs ≤ USD 50 (spike §5.7) and protects against a single-upstream result.*
2. **Production availability target.** Options: (a) 99.5 % per month (about 3.6 h of downtime); (b) 99.9 % (about 43 min); (c) best effort until public launch. *Recommendation: (a) for MVP with two hosts, revisit before public launch.*
3. **Readiness deadline** (the D4 "10 working days" condition, moot for staging). Options: (a) production ready ≤ 20 working days after host details arrive; (b) no deadline, tied to the NAV-005 external release date. *Recommendation: (b).*
4. **Who operates production and on-call** (D7 covered staging only). Options: (a) ICT Group operations team; (b) named PO-side person as for staging; (c) contractor. *Recommendation: (a) if production runs on ICT Group servers.*
5. **Priority and phase.** BA proposes **must / Phase 1**, because production must exist before any external NAV-005 release. PO to confirm.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | — | production hosting ADR (architect, TBD) | C1 trial report per spike §5 (QA, TBD) | D25, D26, D10 |
| AC2 | — | — | production hosting ADR (architect, TBD) | trial evaluation (QA/architect, TBD) | D26 |
| AC3 | — | — | — | mtr path check (QA, TBD) | |
| AC4 | — | all | `infra/` (backend, TBD) | data-flow review (QA/architect, TBD) | D9 |
| AC5 | — | `getHealth`, `postRoute`, `search`, `reverse`, `getBasemapPmtiles` | `infra/` (backend, TBD) | NAV-008 verification plan re-run (QA, TBD) | |
| AC6 | — | `servers` in openapi.yaml | client config (mobile, TBD) | smoke suite against production (QA, TBD) | |
| AC7 | — | — | ADR (architect, TBD) | document review (QA, TBD) | |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-30 | PO decision D25/D26 (chat, relayed by the orchestrator) | Created as a draft: in-country phone latency trial, production host in Mongolia (ICT Group servers likely), residency, parity with NAV-008 checks, operations terms. Priority to be confirmed by the PO | D25 moved the latency trial and the production host out of NAV-008 |
