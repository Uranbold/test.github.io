---
name: NAV-010 Evaluate Hamuga APIs as data sources
assignee: architect
project: osm-navigation-mongolia
---

Run the `spike` skill once the board provides the Hamuga API docs or demos (Swagger export). It covers tiles, search compared with Photon on at least 50 UB queries, traffic convertible to Valhalla live traffic, transit and GTFS, licensing against OSM's ODbL (keep Hamuga data as separate layers), and latency. Keep any demo API key in a local `.env` only.

## Deliverables
- `docs/architecture/spikes/nav-010-hamuga.md` with a recommendation and follow-up items
