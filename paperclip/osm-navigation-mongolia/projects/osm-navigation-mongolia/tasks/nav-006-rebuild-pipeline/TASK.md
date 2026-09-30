---
name: NAV-006 Daily OSM rebuild pipeline with blue/green switch
assignee: architect
project: osm-navigation-mongolia
---

Design the scheduled Geofabrik Mongolia rebuild, covering tiles, the Valhalla graph and the Photon index, with a health check and an atomic blue/green switch. Build on the NAV-008 staging timer (`infra/staging/bin/nav-rebuild.sh`). This depends on the staging host proving it can run the Mongolia rebuild (NAV-008 AC 15).

## Deliverables
- ADR and task breakdown for backend and QA
