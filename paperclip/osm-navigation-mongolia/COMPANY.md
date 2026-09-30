---
name: OSM Navigation Mongolia
description: A Google-Maps-style navigation product for Mongolia built on OpenStreetMap and open-source components (Valhalla, Photon, PMTiles, MapLibre, Ferrostar), delivered by a contract-first agent team with triage lanes
slug: osm-navigation-mongolia
schema: agentcompanies/v1
version: 1.0.0
license: MIT
authors:
  - name: ICT Group product team
goals:
  - Ship turn-by-turn navigation for Ulaanbaatar and Mongolia, Mongolian first
  - Keep every change traceable story -> contract -> code -> test through the agent team
  - Route every incoming item through triage into the right lane (hotfix, bug, change, feature, spike)
  - Keep product decisions with the human board; agents propose, the board decides
tags:
  - navigation
  - openstreetmap
  - mongolia
  - valhalla
  - maplibre
---

OSM Navigation Mongolia builds a Google-Maps-style navigation product for Mongolia on OpenStreetMap data. The code, stories, contract and tests live in one Git repository (see README "Workspace"). Every agent works inside that repository and follows its `CLAUDE.md`.

## How work flows

1. **New item** (idea, change, bug, question, tech debt) → **CEO** hands it to the **Triage Lead**.
2. Triage proposes type, severity, priority and a lane; the **board (you)** confirms priority.
3. The CEO runs the lane by creating issues for the owners:
   - **Feature:** Business Analyst → (UX Designer ∥ Architect) → (Backend ∥ Mobile) → (QA ∥ Architect review) → fix loop
   - **Bug:** QA reproduces with a failing test → owner fixes → QA verifies + regression
   - **Change request:** BA + Architect impact analysis → board approves → story → design/contract → code → tests
   - **Hotfix (S1 only):** reproduce → minimal fix → smoke → 48 h follow-up
   - **Spike:** Architect or BA researches, a skeptic challenges, follow-ups go back to triage
4. Every agent ends its task with a **handoff block**; the CEO routes open questions to the board.

## Stack (ADR-0001)

OpenStreetMap (Geofabrik Mongolia) · Planetiler → PMTiles · Valhalla (mn-MN) · Photon + Nominatim · MapLibre (web/native) · Ferrostar (navigation SDK) · Docker Compose.
