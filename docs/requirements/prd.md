# Product requirements (PRD), OSM Navigation (working name)

- **Owner:** business-analyst. **Status:** draft, 2026-10-04 ([D202](decisions.md): drafted as a separate BA task now that offline is in the launch scope). The PO has not reviewed it yet.
- **Rule:** this document summarises decisions; it makes none. The binding sources are [decisions.md](decisions.md) (PO decisions `D<n>`), [backlog.md](backlog.md) (priorities and status) and the story files (acceptance criteria). Where this PRD and a story differ, the story and the decision log win. Open points are under "Open questions".

## 1. Vision
A Google-Maps-style navigation app for **Mongolia**, built on **OpenStreetMap** and open-source components, **Mongolian first** (English second). It lets people search for places in Cyrillic or Latin spelling, get a route by car, on foot or by bike, and follow **turn-by-turn voice guidance in Mongolian**, in Ulaanbaatar and across the countryside, **with or without an internet connection**. The data is refreshed daily from OSM, so local mappers' fixes show up the next day.

## 2. Users (personas)
| Persona | What they need most | Where the product answers it |
|---|---|---|
| **UB commuter by car** | Reliable routes and arrival times through UB traffic; Mongolian voice; no distraction while driving | NAV-005 guidance, NAV-011 preview and search, NAV-012 background and lock screen; traffic later (Phase 3) |
| **Taxi / delivery driver** | Fast search for addresses and places, quick reroutes, guidance that survives calls, Bluetooth and battery savers | NAV-003 / NAV-011 search, NAV-005 reroute, NAV-012, offline fallback (NAV-021, NAV-023) |
| **Pedestrian** | Walking routes and search in the city | «Явган» mode in NAV-004 / NAV-005 / NAV-011 |
| **Tourist (English UI)** | English UI, Latin-spelling search ("Sukhbaatar"), Cyrillic map labels | Language switch (D59: Mongolian on first launch), NAV-003 tier B, D11 |
| **Intercity / countryside driver** | Routes on unpaved roads, guidance and search with weak or no signal | «Шороон замаас зайлсхийх», all of Mongolia (D131), **offline Mongolia map** (D156–D170) |

## 3. Launch scope (first external Android release)
Platform order: **Android first, then iOS** ([D24](decisions.md)). Coverage: **all of Mongolia** ([D131](decisions.md), [D158](decisions.md)).

| Capability | Stories | Notes |
|---|---|---|
| Backend: tiles, routing (Valhalla, `mn-MN`), search (Photon) behind one gateway | NAV-001 | Contract `docs/architecture/api/openapi.yaml` |
| Daily OSM rebuild with blue/green switch and rollback | NAV-006 | 03:30 Asia/Ulaanbaatar (D132) |
| Hosting: staging, then production in Mongolia | NAV-008, NAV-009 | Staging Hostinger VPS (D25); production most likely ICT Group servers (NAV-009, priority to confirm) |
| Android search (Cyrillic/Latin), route preview, alternatives, «Дугуй», reverse geocoding, typing lock | NAV-011 (done), NAV-018 | NAV-003 golden set as the quality bar |
| Android turn-by-turn guidance with Mongolian voice, reroute, GPS-loss and network-loss handling | NAV-005 | Voice fallback: chime plus notice until NAV-016 (D23, D76) |
| Background, lock screen, restore, calls, Bluetooth, sunrise/sunset theme | NAV-012 | |
| **Offline Mongolia map: map, new route and reroute, search and reverse without internet** | **NAV-020** (server pack), **NAV-021** (on-device routing), **NAV-022** (download, update, map from the pack), **NAV-023** (offline search) | Required at launch ([D159](decisions.md)); downloaded in the app ([D160](decisions.md)); online first with on-device fallback ≤ 3.0 s ([D163](decisions.md), [D199](decisions.md)); Wi-Fi default with mobile-data confirmation ([D164](decisions.md)); map monthly, routing and search weekly ([D166](decisions.md)); "offline" indicator on routes and results ([D201](decisions.md)); changes 7a–7c to NAV-005 / NAV-011 / NAV-012 after these stories ([D194](decisions.md)) |
| Native-speaker review of Mongolian voice and UI text | NAV-007 | Must finish before any external release ([D17](decisions.md)) |
| Mongolian voice clip pack (expected fallback) | NAV-016 | Confirmed only after NAV-007 AC 9 (D76) |
| In-app licences screen (ODbL and library notices) | change on NAV-005 | Before the first external Android release ([D195](decisions.md)) |

**Demos and planning tools (not the launch product):** the web demo (NAV-002 map, NAV-003 search, NAV-004 route preview; the public site is map-only, D44 / D47), the web demo mode for the PO's iPhone (NAV-017) and the Android demo build for the PO's phone (NAV-019, in progress).

## 4. Out of scope at launch
- **iOS** (NAV-015, after Android, D24), including iOS offline (D162).
- **Real-time traffic** and traffic-aware arrival times (Phase 3; needs a data partner, backlog owed decision 4).
- Lane guidance (NAV-013) and speed limits (NAV-014), Phase 2.
- Public transport directions, Android Auto / CarPlay, satellite imagery, street-level imagery, POI reviews and photos.
- Multi-stop routes, saved places, recent searches, toll avoidance (no toll option, D52 / D61).
- Delta updates for the offline pack (full files at launch, D167; spike parked, D196); per-region packs.
- Indoor (floor-level) positioning (D171).
- Our own Nominatim import (draft row, D128).

## 5. Constraints
- **Data and licence:** OpenStreetMap under ODbL 1.0. «© OpenStreetMap contributors» is visible on every map screen (CLAUDE.md rule 8). The offline pack is a distributed derivative database: offered under ODbL, no DRM, the method public (ADR-0017 §7).
- **Stack:** open-source, permissive components (ADR-0001): OSM → Planetiler / PMTiles, MapLibre, Valhalla, Photon, Ferrostar; Docker Compose on the server. On the phone: `valhalla-mobile` and `sqlite-bundled` for offline (ADR-0017).
- **Language:** Mongolian first, English second. The glossary is binding: one approved Mongolian term per concept; new terms start as `needs native review` until the NAV-007 panel signs them off.
- **Android platform:** `minSdk` 26; phones **without Google Play services** are supported (D62).
- **Privacy:** no analytics or third-party hosts (NAV-005 AC 65); coordinates only in the requests that need them, search bias rounded to 3 decimals (D30); legal review before outside testers' traffic goes abroad (D9).
- **Release gates:** NAV-007 before any external release (D17); D9 before outside testers use the staging host abroad; the licences screen before the first external Android release (D195).
- **Data quality (business risks):** OSM address coverage, `name:mn` / `name:en`, `maxspeed`, `turn:lanes`, countryside soum and bag names; Mongolian TTS availability on phones. Tracked in the backlog risk list.
- **Repository:** public; no secrets, hostnames or IPs (D35).

## 6. Success measures (quality bars already decided)
| Area | Bar | Source |
|---|---|---|
| Search (online) | Every NAV-003 tier A row passes; ≥ 80 % of tier B rows | NAV-003 AC 14, D33 |
| Search (offline) | ≥ 90 % of the applicable tier A + B rows; held-out countryside set reported, pass mark set by the PO later | D168, D198, NAV-023 AC 17–19 |
| Offline fallback | On-device answer starts ≤ 3.0 s after the online request, or at once without a validated network | D199 |
| Reroute | New route on screen ≤ 3 s after off-route for ≥ 9 of 10 G2 replays (online) | NAV-005 AC 45; offline NAV-021 AC 15 |
| Data freshness | Server data ≤ 48 h old; offline routing and search weekly, offline map monthly | NAV-006 AC 3, D166 |
| Service | 0 failed requests during a data switch | NAV-006 AC 15 |
| Voice and text | Native-speaker panel sign-off before external release | NAV-007, D17 |

Business measures for the launch (active users, retention, trips per user) are not defined yet (Open question 1).

## 7. Open questions (PO)
1. **Launch success metrics.** Which business measures define a successful launch (for example weekly active users, trips completed, offline pack installs), and their targets? *Recommendation:* agree 3–4 measures before the first external release; they need a privacy-safe counting method (no analytics hosts today, NAV-005 AC 65), which is a separate item through triage.
2. **NAV-009 priority** (production hosting in Mongolia; backlog: TBD, BA proposal must). *Recommendation:* must, before any external release, because the PRD launch scope assumes it.
3. **Launch date and pilot group** (internal testers, a taxi or delivery partner, public). *Recommendation:* a pilot with the NAV-007 driver partner first (D18), once NAV-007 and D9 allow it.

## 8. Links
- Research: [`docs/osm-navigation-research.md`](../osm-navigation-research.md)
- Stack: [`docs/architecture/adr/0001-tech-stack.md`](../architecture/adr/0001-tech-stack.md); offline: [ADR-0017](../architecture/adr/0017-offline-mongolia-pack-android.md) and the [offline spike](../architecture/spikes/offline-android.md)
- API contract: [`docs/architecture/api/openapi.yaml`](../architecture/api/openapi.yaml)
- Backlog: [backlog.md](backlog.md); decisions: [decisions.md](decisions.md); glossary: [glossary.md](glossary.md)
- Team and intake: [`docs/team/agent-architecture.md`](../team/agent-architecture.md), [`docs/team/intake-and-triage-flow.md`](../team/intake-and-triage-flow.md)

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | [D202](decisions.md) (PO "all as recommended", group C item 5) | First draft: vision, personas, launch scope with the offline stories NAV-020–NAV-023, out of scope, constraints, decided quality bars, three open questions, links | Offline joined the launch scope (D159); the PRD did not exist yet |
