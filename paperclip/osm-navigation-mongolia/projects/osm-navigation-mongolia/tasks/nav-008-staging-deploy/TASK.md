---
name: NAV-008 Deploy staging on the Hostinger VPS
assignee: backend-engineer
project: osm-navigation-mongolia
---

When the board provides a **Hostinger VPS KVM 4 in Singapore** (not web hosting) and the domain, hand the operator `infra/staging/RUNBOOK.md`. The operator runs bootstrap, DNS, Caddy HTTPS, deploy, rebuild, monitoring and backups. Then QA runs the outside-in checks (`docs/qa/test-plans/NAV-008.md`). Keep the host IP and domain out of the repository, in a local untracked `.env` or inventory only.

## Deliverables
- Staging reachable at `https://staging.<domain>`, with the NAV-001 smoke suite passing against it
