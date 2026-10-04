# ADR-0001: Build on open-source components instead of forking a full app

- **Status:** accepted (Consequences amended 2026-10-04 by ADR-0017: offline is a launch requirement)
- **Date:** 2026-09-29
- **Stories:** all

## Context
We want Google-Maps-style navigation on OpenStreetMap for Mongolia: online search, server routing, live traffic later, voice guidance in Mongolian, Android + iOS + web, and our own branding. The options researched (`docs/osm-navigation-research.md`):
1. Write everything from scratch
2. Fork a complete app (CoMaps / Organic Maps / OsmAnd)
3. Build our own app on mature open-source components

## Decision
Option 3, with this stack:

| Layer | Choice | License |
|---|---|---|
| Data | OpenStreetMap, Geofabrik Mongolia extract, daily rebuild | ODbL |
| Tiles | Planetiler → Protomaps PMTiles (static hosting) | Apache / BSD, data ODbL |
| Rendering | MapLibre Native (Android/iOS), MapLibre GL JS (web) | BSD |
| Routing | Valhalla (`mn-MN` narrative, OSRM-compatible output, live/predicted traffic support) | MIT |
| Navigation | Ferrostar (Rust core; Compose, SwiftUI, Web) | BSD |
| Search | Photon (autocomplete) + Nominatim (reverse/structured) | Apache / GPL (server-side only) |
| Transit (later) | MOTIS + GTFS | MIT |

Starter references: the Headway docker stack (backend layout), and the Ferrostar demo apps, Vialix and NavMaster (mobile).

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| From scratch | Full control | Years of work; reinvents routing and rendering |
| Fork CoMaps / Organic Maps | Working offline app immediately; Apache 2.0 | Offline-only design, own map format, no server routing, live traffic is hard, huge C++ core, painful upstream merges, no web |
| Fork OsmAnd | Most features | GPLv3 forces our app to be open source; complex UI |

## Consequences
- We write the app UI, the gateway and the traffic pipeline. The core engines are dependencies we upgrade.
- Ferrostar is pre-1.0, so we pin versions and budget for API changes.
- Offline navigation is a later phase (on-device Valhalla + PMTiles). Revisit if offline becomes a launch requirement. **Amended 2026-10-04:** offline is now a **launch requirement** (PO, backlog owed decision 3 = yes): map, new route and reroute, search and reverse geocoding work without internet on Android from a downloaded Mongolia pack, iOS later. The design is [ADR-0017](0017-offline-mongolia-pack-android.md) (accepted): upstream `valhalla-mobile` (MIT, not forked) and `sqlite-bundled` (Apache-2.0), so the stack stays permissive.
- The whole stack except Nominatim (GPL, server-side) is permissive, so a closed-source commercial product is possible.
