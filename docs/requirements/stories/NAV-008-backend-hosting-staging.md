---
id: NAV-008
title: Choose and set up hosting for the backend (dev/staging first, production later)
phase: 0          # staging is needed at the end of Phase 0 (NAV-002 sharing) and for all Phase 1 device testing
priority: must    # PO-confirmed 2026-09-30 (D3)
size: L           # spike (about S) + staging setup with TLS, monitoring, backups (about M); can be split if the orchestrator prefers
needs_design: false
needs_backend: true
needs_mobile: false
status: ready     # since 2026-09-30. The spike (AC 1–4) can start. AC 6+ wait for NAV-001 acceptance and the AC 5 choice. Outside testers on a host abroad wait for AC 24
---

# NAV-008: Choose and set up hosting for the backend (dev/staging first, production later)

## Story
As a **taxi / delivery driver** (and every other persona who will test the app on a real phone in Mongolia), I want **the map, search and routing services to be reachable over HTTPS from my phone on a Mongolian mobile network with little delay**, so that **I can try the web demo and the Android navigation app on real streets in Ulaanbaatar, and my feedback reflects the real product rather than a laptop demo**.

> **Spike-type enabler.** No end user touches hosting directly. The first half is a timeboxed architect spike that produces a written recommendation. The PO then decides, and the second half sets up **one staging environment** on the chosen hosting. Production hosting is a later follow-up story, written once the PO has decided (see Out of scope).
>
> | Capability delivered here | Story it unblocks |
> |---|---|
> | Public HTTPS staging URL for the NAV-001 gateway | NAV-002 web demo shared with people outside the team (all personas) |
> | Low-latency access from Mongolian mobile networks | NAV-005 active navigation on real phones (commuter, taxi/delivery, pedestrian); off-route reroute needs a fast `postRoute` |
> | Stable backend for device sessions | NAV-007 AC 9–12 device, TTS and listening tests |
> | Host with capacity and outbound access for the Mongolia data build | NAV-006 daily rebuild pipeline with blue/green switch |

## Context
- **Why now (PO request, 2026-09-29).** The NAV-001 stack (Valhalla, Photon, PMTiles, nginx gateway, Docker Compose) runs only in a temporary cloud dev container. Its gateway binds to `127.0.0.1:8080` (`backend/README.md`). Phones and outside testers cannot reach it. Android (API 28+) and iOS (ATS) block plain `http://` by default, so a phone build also needs **HTTPS with a publicly trusted certificate**.
- **Research.** `docs/osm-navigation-research.md` §11 item 6 ("Hosting: own servers in Mongolia or a cloud provider?"), §10 item 5 (operations: rebuild pipelines, blue/green deploys and monitoring needed from MVP), §5.1 (tiles as a static file, possibly with a CDN later), §5.3 (sizing).
- **Backlog.** This story turns owed decision 5 ("Hosting") into a story. The PO also mentioned possibly having **their own map data and Valhalla endpoints**. That is still unconfirmed and is an input to the spike (Open question 1).
- **PO decisions of 2026-09-30** (`docs/requirements/decisions.md`, answer "all recommended"):
  - D3: priority **must / Phase 0**.
  - D4: the architect spike measures first. **Prefer a Mongolia-hosted server or the PO's own servers if their median RTT is < 50 ms (C1). Otherwise use an international cloud region near Mongolia for staging.** The BA treats the recommendation's readiness condition (ready within 10 working days) as part of the rule until the PO says otherwise.
  - D5: staging budget **USD 50–150 per month**, so the ceiling is **USD 150 per month**.
  - D6: the staging host is a **subdomain of the PO's existing company domain**.
  - D7: a **named person on the PO side** holds the provider account, billing, DNS and SSH access and receives alerts. The backend engineer delivers infrastructure as code plus a runbook.
  - D8: **public but unlisted URL with rate limits**. Authentication is decided before any public release.
  - D9: the PO's **legal counsel reviews the personal-data law before any outside tester's traffic goes to a host abroad** (new AC 24).
  - D10: whether the PO has **own servers, map data or Valhalla endpoints** is still **unanswered**. The spike proceeds without it (AC 1).
- **Stack is fixed** by ADR-0001 and ADR-0002. This story chooses **where** the stack runs, not **what** runs. The gateway paths in `docs/architecture/api/openapi.yaml` stay the same.

### Sizing baseline (inputs to the spike)
| Source | Figure |
|---|---|
| Research §5.3, Mongolia only | Valhalla build 4 vCPU / 8 GB, serving 2 vCPU / 2–4 GB; Photon/Nominatim 2 vCPU / 4 GB and a few GB of disk; PMTiles < 1 GB. "One mid-size VM" |
| NAV-001 measurements, Mongolia extract (geo2day, 70 MB PBF), `backend/README.md` ("Measured on the reference machine"), ADR-0002 Amendment 1 | Peak build memory **5.5 GB** on the cold first run (empty `data/`; tiles-build alone 5.0 GB; `docker stats` sum sampled every 3 s). The earlier figure of 4.24 GB was the source-switch rebuild with cached auxiliary files, so it understates a fresh host. Running services < 1 GB. PMTiles **243 MB**. Routing graph 64 MB. Photon index 27 MB. `data/` **2.9 GB**, of which about 2.5 GB is cached auxiliary downloads. Source switch rebuild **336 s** on 4 CPU. p95 on the host: route 12 ms, search 70 ms, reverse 44 ms, tile range 1.3 ms |
| BA sizing target for staging | **4–8 vCPU, 16 GB RAM, ~100 GB SSD**, one public IPv4. The headroom covers a second data copy during NAV-006 blue/green and a later own Nominatim import (ADR-0003 production direction). Check against AC 16: the 5.5 GB cold build peak plus < 1 GB of running services is about 6.5 GB, roughly 40 % of 16 GB, which is inside the 70 % limit |

### Decision criteria (the spike scores every option on each one)
| # | Criterion | Target or what must be reported |
|---|---|---|
| C1 | **Latency from Ulaanbaatar** | Median TCP connect RTT to port 443 **< 50 ms** from phones in UB on **≥ 2** Mongolian mobile operators (for example Unitel, Mobicom, Skytel, G-Mobile), **≥ 20** samples per operator, p95 also reported. Method in AC 2 |
| C2 | **Monthly cost** | Staging and projected production, in USD and MNT: compute, disk, backups, bandwidth or egress, public IP, domain, certificate. Each figure cites a quote or price list with its date |
| C3 | **Data residency and legal** | Where the data and logs physically sit. Whether route and search coordinates from Mongolian users leave Mongolia. The Mongolian Law on Personal Data Protection (adopted 17 Dec 2021, in force 1 May 2022) restricts transfer of personal data abroad without consent or a legal basis, so this is flagged for the PO's legal counsel (Open question 8). Also: the provider's contract jurisdiction, mainland-China ICP filing if a China region is considered, and the payment method (MNT invoice or international card) |
| C4 | **Operational effort** | Who patches the OS and Docker, how hardware failure is handled, the provider SLA, power redundancy (UPS or generator; UB winter peak load), snapshot and API support, support language and hours, and estimated operator hours per month |
| C5 | **Sizing fit and scale path** | Meets the sizing target above on SSD/NVMe. Can resize vertically. Can add a second VM for blue/green or HA. Option for a CDN or object storage in front of the PMTiles file |
| C6 | **Backups** | Off-host snapshot or backup service, retention, restore time |
| C7 | **TLS and domain** | Public IPv4 with inbound 443 and 80, DNS control, automatic certificate issuance and renewal (for example ACME/Let's Encrypt) works from the host. IPv6 (AAAA) availability recorded |
| C8 | **Monitoring** | External uptime checks, host metrics (CPU, RAM, disk), alert delivery to the operator |
| C9 | **Outbound access for the data build** | The host can reach every download in the `backend/README.md` table: Geofabrik (blocked from the dev container), osmdata.openstreetmap.de, naciscdn.org, r2-public.protomaps.com, qrank.toolforge.org, GraphHopper Photon dump, GitHub / codeload / ghcr.io, mcr.microsoft.com, Maven Central, repo.osgeo.org |
| C10 | **International-link resilience** | What happens to Mongolian users if Mongolia's international links (via Russia and China) degrade. An in-country host keeps working for Mongolian users, and a foreign host does not. A foreign host keeps working if the local data centre fails |

### Options to compare
| Option | Notes for the spike |
|---|---|
| **A. VPS or cloud server hosted in Mongolia** | Name **≥ 2** Mongolian providers if they exist. Expected strengths: C1, C3, C10. To check: C4, C6, C9, procurement lead time |
| **B. International cloud region close to Mongolia** | For example Hong Kong, Seoul, Tokyo, Singapore, or a mainland China region (ICP filing) or a Russian Far East / Siberia region (payment and sanctions issues). Latency must be **measured**, not assumed. Expected strengths: C4, C6, C8, fast self-service setup |
| **C. PO's own existing servers** | Needs details from the PO (Open question 1): location, spare capacity, public IP and 443, who administers them. If the PO also has **own map data or Valhalla endpoints**, the spike records what they are and whether they could replace or complement our Valhalla. Any change to ADR-0001 is the architect's call |

## Acceptance criteria

### A. Spike: written recommendation (architect)
1. **Given** NAV-001 is accepted and this story is started, **When** the architect delivers the spike within **5 working days**, **Then** a proposed ADR under `docs/architecture/adr/` (status `proposed`) exists that:
   - compares **≥ 3** options: at least one from each of A and B, and option C, or the note "no details received from the PO by <date>"
   - gives **every option a value for every criterion C1–C10**, or "unknown" together with how and by when it will be found out
   - states for each figure whether it was measured, quoted or taken from documentation, with the date
2. **Given** a candidate can be tested (a trial VM, a provider test IP or a looking-glass host), **When** C1 is measured, **Then** the ADR records, for each of **≥ 2** Mongolian mobile operators, **≥ 20** TCP connect samples to port 443 from a phone in UB (Wi-Fi off) or a laptop tethered to that phone. Record the median, p95, operator, network type (4G/5G), place and date. A candidate that could not be measured is marked "C1 unmeasured" and **cannot** be recommended as meeting C1.
3. **Given** the comparison, **When** the recommendation section is read, **Then** it names:
   - **one** option for staging, chosen by applying the PO's rule D4 to the measured C1 values, and a proposed path to production (same or different option), each with reasons, risks and estimated monthly cost
   - that the staging option's estimated monthly cost is **≤ USD 150** (D5). If no option that meets AC 4 fits the budget, the ADR says so and lists what would fit (smaller specification or higher budget) for the PO to decide
   - a concrete VM specification (vCPU, RAM, disk type and size, region or data centre)
   - the PO decisions and information still needed, each with the architect's recommendation: the D4 choice for the PO to confirm (AC 5), D10 (own servers, map data, Valhalla), and Open question 5 if the PO has not confirmed it yet
4. **Given** the sizing baseline, **When** the recommended staging specification is checked, **Then** it has **≥ 4 vCPU, ≥ 16 GB RAM and ≥ 100 GB SSD**, or the ADR explains in writing why a smaller size is enough, based on the NAV-001 measurements.

### B. PO decision recorded
5. **Given** the recommendation, and the PO decisions of 2026-09-30 already recorded (D3 priority, D4 location rule, D5 budget, D6 domain, D7 operator, D8 access, D9 legal review; `docs/requirements/decisions.md`), **When** the PO confirms the staging option that the D4 rule selects (or picks an alternative), **Then**:
   - the confirmed provider and location, and the staging data coverage (Open question 5), are recorded with their date in this story's Change log and as a new row in `docs/requirements/decisions.md`
   - the architect sets the ADR to `accepted` or records the PO's chosen alternative
   - backlog owed decision 5 links to the ADR (it already links to `decisions.md` D3–D10)

   If the PO accepts an option that misses C1 (median ≥ 50 ms), the accepted exception and the measured value are recorded, and AC 8 uses the accepted value instead of 50 ms. Under D4 this applies to the international fallback region when it also misses 50 ms.

### C. Staging environment reachable from Mongolian phones
6. **Given** staging is set up on the chosen hosting, with `<staging-host>` a subdomain of the PO's existing company domain (D6; the exact label, for example `nav-staging`, is set during setup and recorded under AC 23), **When** a phone in Ulaanbaatar on a Mongolian mobile network (Wi-Fi off) opens `https://<staging-host>/health` in the default browser, **Then** it gets **200** with `{"status":"ok"}` and **no certificate warning**. Tested on **≥ 1 Android** phone (Chrome) and, if available, **≥ 1 iPhone** (Safari), on **≥ 2** operators in total.
7. **Given** the staging host, **When** its TLS setup is checked from outside (for example with `openssl s_client` or `testssl.sh`), **Then**:
   - the certificate is publicly trusted, matches the hostname and is valid for **≥ 30 days** on the acceptance date
   - only TLS 1.2 and 1.3 are offered
   - automatic renewal is configured, and a renewal dry run succeeds
   - `http://<staging-host>/` answers **301 or 308** to the `https://` URL, or port 80 serves only the ACME challenge
8. **Given** a phone in UB on each of **≥ 2** Mongolian operators, **When** **20** TCP connect times to `<staging-host>:443` are measured (as in AC 2), **Then** the **median is < 50 ms** on each operator (or below the exception value accepted under AC 5). The p95 is recorded.
9. **Given** a laptop tethered to a phone on a Mongolian mobile network, **When** **20** sequential `postRoute` requests P1→P2 (`costing=auto`, `format=osrm`, `language=mn-MN`, `units=kilometers`, voice and banner instructions on) are sent over one kept-alive HTTPS connection, **Then** the **p95 is ≤ 500 ms**, including the network.
10. **Given** staging is healthy, **When** `BASE_URL=https://<staging-host> tests/smoke/run.sh` runs from a machine **outside** the staging host's network, **Then** it exits **0**, and every NAV-001 smoke check passes, including the checks that failed on the BBBike UB box (NAV-001 AC 10, 13, 14). This requires Mongolia-wide or full-UB data (Open question 5). The same command, run from a laptop tethered to a Mongolian mobile network, also exits **0**.
11. **Given** the staging CORS allowlist is set to the NAV-002 web demo origin(s) and `http://localhost:5173` (BA proposal: lets developers run the demo locally against staging, and matches the smoke suite's test origin), **When** preflights are sent, **Then** allowed origins get `Access-Control-Allow-Origin`, and `Origin: http://evil.example` gets **none**. The allowlist does **not** contain `*`.
12. **Given** the staging host, **When** it is port-scanned from the internet (for example `nmap -Pn -p 1-65535`), **Then** only **443**, **80** (redirect or ACME) and the SSH port are open. Valhalla (8002), Photon (2322) and the gateway's internal 8080 are **not** reachable. SSH accepts **keys only** (password login is refused), and automatic OS security updates are enabled.
13. **Given** the staging gateway, **When** one client IP sends a sustained **100 requests/s** for **10 s** to `/v1/route` or `/v1/search`, **Then** at least one response is **429** with a JSON `{code, message}` body, and the stack stays healthy. A normal session (**≤ 10 requests/s** to the API paths, any rate of tile range requests) is **never** limited. Limits are BA-proposed. They are set loosely because mobile operators put many phones behind one carrier-grade NAT address (see Edge cases).
14. **Given** a day of normal staging use, **When** the access logs of the gateway **and** of any TLS terminator or proxy in front of it are read, **Then** they contain **no** client IP addresses, query strings, request bodies or coordinates. The NAV-001 privacy rules (`backend/README.md`, "Privacy and logs") also hold on staging.

### D. Daily data rebuild can run on the host
15. **Given** staging is configured with `OSM_PBF_URL=https://download.geofabrik.de/asia/mongolia-latest.osm.pbf` and an **empty** auxiliary cache, **When** `make rebuild-data` runs on the staging host, **Then**:
    - it finishes with no manual steps and **no pre-seeded files** (every download in C9 is reachable from the host)
    - it takes **≤ 30 minutes**
    - `data/build-info.json` records the Geofabrik source and a replication timestamp **≤ 48 hours** older than the run
    - the smoke suite (AC 10) exits **0** afterwards
    - the downtime of the served API during the rebuild is measured and recorded. Staging may be down during a rebuild, and zero-downtime switching belongs to NAV-006
16. **Given** the rebuild in AC 15, **When** memory and disk are measured (`make stats`, sampled every **≤ 3 s**), **Then**:
    - peak build memory **plus** the steady-state memory of the running services is **≤ 70 %** of the VM's RAM, so that NAV-006 can build while the old version still serves
    - free disk after the build is **≥ 50 GB**, enough for a second data copy during blue/green and a later own Nominatim import

### E. Operations: monitoring, backups, runbook, cost
17. **Given** staging is live, **When** an external uptime check requests `https://<staging-host>/health` every **≤ 60 s** from outside the host's network, **Then** stopping the gateway triggers an alert to the channel of the named PO-side operator (D7) within **≤ 5 minutes**, and restarting it sends a recovery notice within **≤ 5 minutes**.
18. **Given** host metrics are collected, **When** they are checked, **Then** CPU, RAM and disk usage are kept for **≥ 7 days**, and alerts fire when disk use is **≥ 85 %** and when the TLS certificate expires in **≤ 14 days**.
19. **Given** the backup setup, **When** it is reviewed, **Then**:
    - everything on the host that is **not** in git and **not** rebuildable is backed up **daily** to a location **off the host** (another provider, region or site): `.env`, TLS/ACME account data, proxy and firewall config, scheduler units
    - retention is **≥ 7 daily** and **≥ 4 weekly** copies
    - secrets are encrypted in the backup
    - `data/` is **not** backed up (it can be rebuilt from OSM, AC 15), and the runbook says so
20. **Given** a new, empty VM of the same specification, **When** the operator restores staging from the git repository plus the latest backup, following the runbook, **Then** the smoke suite (AC 10) exits **0** within **≤ 2 hours** from the start, and the measured time is recorded.
21. **Given** the backend engineer's deliverables, **When** `infra/` and the runbook are inspected, **Then** they contain scripts or code for provisioning, deploy and update, certificate renewal, rebuild, restore and monitoring setup, plus the access roles (role names only, no personal data). **No secrets** are in the repository, and every new configuration key (for example public hostname, CORS origins, ACME contact, alert target) is in `.env.example` with a one-line comment.
22. **Given** the first full month on staging, **When** the provider's invoice or usage page is checked, **Then** the actual monthly cost is **≤ USD 150** (the ceiling of the PO-approved band USD 50–150, D5), or ≤ a higher ceiling the PO approved under AC 3, and the amount in USD (and MNT if invoiced in MNT) is recorded in the ADR.
23. **Given** staging is accepted, **When** `docs/architecture/api/openapi.yaml` and `backend/README.md` are read, **Then** both list the staging base URL (`servers` in the OpenAPI file), so NAV-002, NAV-005 and QA can point their builds at it through configuration and **not** hard-coded strings.

### F. Legal gate for a host abroad (added 2026-09-30, PO decision D9)
24. **Given** the confirmed staging host (AC 5) is physically **outside Mongolia**, **When** the staging URL is first given to anyone outside the project team (outside testers, the NAV-007 reviewer panel, partner drivers), **Then** a record exists, dated **on or before** that first invitation, stating that the PO's legal counsel reviewed the Mongolian Law on Personal Data Protection for this setup. The record gives the date, the reviewer's role (no name), the outcome (go / go with conditions / no go) and any conditions, such as a tester consent text. It is kept in this story's Change log and as a row in `docs/requirements/decisions.md`. Until the outcome is go or go with conditions (and the conditions are met), only the project team uses the staging URL. If the confirmed host is in Mongolia, this AC is recorded as "not applicable (host in Mongolia)".

## Edge cases
- **No network or weak signal (countryside persona):** RTT outside UB is higher and is **not** a pass criterion. If a tester is in an aimag centre, record one C1-style measurement there for information.
- **Carrier-grade NAT:** many phones on one operator share a public IP. Rate limits (AC 13) must not block a group of testers. If testers report 429s during normal use, the limit is raised, and the change is logged here.
- **IPv6-only or NAT64 mobile networks:** record whether the host has an AAAA record and whether AC 6 passes on each operator's default APN.
- **DNS caching by operators:** after a DNS change, re-run AC 6 only after the record's TTL has passed. Keep the TTL **≤ 300 s** during setup.
- **Certificate renewal fails:** caught by the ≤ 14-day expiry alert (AC 18) well before phones start refusing the connection.
- **Disk full during a rebuild:** caught by the ≥ 85 % alert (AC 18). The NAV-001 atomic-write rules mean the old data keeps serving and no half-written artefact is served.
- **A download is blocked from the host** (Geofabrik was blocked from the dev container): AC 15 fails, and the spike should already have caught it under C9. Pre-seeding by hand is a documented workaround, **not** a pass.
- **Provider or data-centre outage:** staging is a single VM with no high availability. That is accepted for staging. The production follow-up covers HA.
- **International link degradation:** for a host outside Mongolia, Mongolian testers lose the service even though local internet works (C10). Record any such incident during staging.
- **Winter:** UB winter peak electricity load can cause power interruptions. The provider's power redundancy is scored under C4. Rebuild times are scheduled in the night window (Asia/Ulaanbaatar, UTC+8) by NAV-006.
- **Off-route and reroute (NAV-005):** reroute requests go through the same `postRoute`. AC 9 bounds their network delay on staging.
- **No search result or Cyrillic/Latin search** ("Sukhbaatar" vs "Сүхбаатар"): behaviour is unchanged from NAV-001, and the smoke suite runs the same checks against staging (AC 10).
- **GPS loss:** not applicable to hosting. It is a client concern (NAV-005).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Download sources blocked or slow from the chosen host** (Geofabrik blocked from the dev container) | Daily rebuild cannot run, so NAV-006 is blocked | C9 in the spike, AC 15 on the real host. Architect, backend |
| R2 | **Personal-data law** (Law on Personal Data Protection, in force 1 May 2022): route and search coordinates may count as personal data. Transfer abroad needs consent or a legal basis | An option B host may need user consent flows or may be unsuitable for production | Legal review before production and before outside testers' traffic goes abroad (D9, AC 24). Staging logs keep no IPs or coordinates (AC 14). PO |
| R3 | **PO's own servers, map data or Valhalla endpoints are unknown** (D10, still unanswered on 2026-09-30) | Option C cannot be scored. A late answer may reopen the decision | Open question 1 asks for the details. The spike records "no details received from the PO by <date>" (AC 1). PO |
| R4 | **Single VM is a single point of failure** | Staging downtime during outages or rebuilds | Accepted for staging. HA in the production follow-up |
| R5 | **Agents cannot hold credentials or operate servers directly** | Provisioning, DNS and billing steps need a human | Backend delivers everything as code plus a runbook (AC 21). A named PO-side person runs privileged steps (D7). The person is named to the orchestrator, not in the repo |
| R6 | **Procurement lead time** (contracts and MNT invoices for local providers, international card for foreign clouds) | Staging is delayed, and phone testing slips | Raised in the spike (C2, C3). Option B as a timeboxed staging fallback (Open question 1) |
| R7 | **Data quality becomes visible to outside testers.** OSM address coverage (`addr:*`, khoroo, ger plots), sparse `maxspeed` (unrealistic ETAs), missing `turn:lanes`, and missing `name:mn` in the tiles (NAV-001 R6, R8–R10) | Testers judge the product on data gaps, not on hosting | Tell testers what is known to be weak. Collect feedback per story. BA and PO |
| R8 | **Photon index date differs from the tiles and routing data** (GraphHopper dump, ADR-0003) | Search can disagree with the map on staging | Recorded in build-info. Own Nominatim in NAV-006 |
| R9 | **PMTiles for Mongolia was 243 MB at z15**, above the NAV-001 AC 12 limit of 200 MB (which was defined for the UB extract). PO decision D1 (2026-09-30): build at z14 and re-measure, with a 400 MB fallback limit | None for serving: clients fetch byte ranges, not the whole file. Affects egress cost | Egress in C2. CDN option in C5. The spike uses the z14 size once the backend has re-measured it |
| R10 | **Public, unauthenticated API** (NAV-001 has no auth) | Scraping or abuse can exhaust the VM | Rate limits (AC 13), unlisted URL (D8). Auth is decided before any public release (D8) |

## Out of scope
- **Production environment** (HA, second VM, load balancer, CDN, production SLA, on-call). The BA writes a follow-up story after the PO decision in AC 5.
- The **scheduled** daily pipeline and blue/green switch (NAV-006). This story only proves the host can run the rebuild (AC 15–16).
- Own Nominatim import (ADR-0003 production direction, NAV-006).
- Authentication, API keys, user accounts.
- Client builds pointing at staging (done in NAV-002 and NAV-005 through configuration).
- The legal opinion itself (PO's legal counsel, Open question 8).
- Traffic data, analytics, crash reporting.

## Open questions
Decisions for the PO. On 2026-09-30 the PO answered "all recommended" (`docs/requirements/decisions.md`): Q1 → D4 (rule applied after the spike, confirmed in AC 5), Q2 → D5, Q3 → D6, Q4 → D7, Q6 → D8, Q7 → D3, Q8 → D9. **Still open:** the information request in Q1 (D10, unanswered) and Q5, which was not in the relayed list.

1. **Provider and location.** Options:
   - (a) a VPS or cloud server hosted in Mongolia
   - (b) an international cloud region close to Mongolia (for example Hong Kong, Seoul, Tokyo, Singapore)
   - (c) the PO's own existing servers
   - (d) hybrid: (b) for staging now, then (a) or (c) for production

   *Recommendation: decide after the spike's latency measurements. BA leaning: (a) or (c) for staging if one can be ready within **10 working days** and meets C1, because in-country hosting is strongest on latency, data residency and international-link resilience. Otherwise (d), so phone testing is not blocked.*

   **Information needed from the PO for (c):** where the servers are, spare CPU, RAM and disk, whether a public IP with inbound 443 is possible, and who administers them. Also: do you have **your own map data or Valhalla endpoints**? If so, what data, which Valhalla version, and should they replace or sit next to our stack?

   **Decided 2026-09-30 (D4):** the spike measures first. Prefer (a) or (c) if the median RTT is < 50 ms (and, per the recommendation, it can be ready within 10 working days). Otherwise use an international cloud region near Mongolia for staging, which is (b) now and possibly (d) later. The PO confirms the resulting choice in AC 5. **The information request above is still unanswered (D10).**
2. **Budget range (monthly).** Options:
   - (a) ≤ USD 50
   - (b) USD 50–150
   - (c) USD 150–400
   - (d) no new cash cost (own servers only)

   *Recommendation: (b) for staging, including off-host backups and monitoring. Revisit for production after the spike's quotes. These bands are BA estimates, not quotes.* **Decided 2026-09-30 (D5): (b), ceiling USD 150 per month** (AC 3, AC 22).
3. **Domain name.** Options:
   - (a) register a product domain now (`.mn` or `.com`) and use `staging.<domain>`
   - (b) a subdomain of an existing PO or company domain, for example `nav-staging.<company-domain>`
   - (c) the provider-assigned hostname, for staging only

   *Recommendation: (b). It is fast, needs no brand decision (the product name is still a working name) and gives a trusted certificate. Register the product domain before any external beta.* **Decided 2026-09-30 (D6): (b).** The PO or the operator provides the company domain and DNS access at setup.
4. **Who operates staging.** Options:
   - (a) a named person on the PO side holds the provider account, billing, DNS and SSH access and receives alerts. The backend engineer delivers everything as code plus a runbook (AC 21), and that person runs the privileged steps
   - (b) the PO's own IT team operates it fully
   - (c) a contractor or managed-hosting service

   *Recommendation: (a) for staging. Revisit for production, where 24/7 on-call becomes a question.* **Decided 2026-09-30 (D7): (a).** The person is named to the orchestrator before AC 6 and is not recorded in the repo (AC 21).
5. **Staging data coverage.** Options:
   - (a) Geofabrik `mongolia-latest`
   - (b) the BBBike Ulaanbaatar extract (dev default)

   *Recommendation: (a). The BBBike box (about 13 × 4 km) misses Zaisan (P3) and the Chingeltei ger district (P6) and fails 3 smoke checks. The Mongolia extract passes all of them, allows countryside testing and fits the sizing. This does not decide launch coverage (backlog owed decision 2).*

   **Not in the relayed decision list of 2026-09-30.** "All recommended" would mean (a). AC 10 and AC 15 already assume (a), and the dev default has been the full Mongolia extract since 2026-09-29, so the BA treats (a) as the working assumption. The PO confirms it at the latest in AC 5.
6. **Who may reach staging.** Options:
   - (a) public but unlisted URL, protected by rate limits (AC 13)
   - (b) (a) plus a shared API key or basic auth (needs an OpenAPI change and client support)
   - (c) VPN or IP allowlist (hard with mobile carrier-grade NAT)

   *Recommendation: (a) for Phase 0–1 testers. Decide on auth before any public release. A key embedded in a public web demo does not protect much anyway.* **Decided 2026-09-30 (D8): (a).** Auth is decided before any public release.
7. **Priority and phase.** BA proposes **must, Phase 0** (spike and staging finish at the end of Phase 0), because it blocks NAV-002 sharing, NAV-005 on phones and NAV-007 device tests. Options:
   - (a) must / Phase 0
   - (b) must / Phase 1, with NAV-002 shown only on team laptops until then
   - (c) should / Phase 1

   *Recommendation: (a).* **Decided 2026-09-30 (D3): (a).**
8. **Legal review of data residency.** Options:
   - (a) the PO's legal counsel reviews the personal-data implications before production and before outside testers' traffic goes to a host abroad
   - (b) review only before production
   - (c) no review

   *Recommendation: (a) if option B or (d) is chosen, otherwise (b).* **Decided 2026-09-30 (D9): (a).** New AC 24 is the gate.

Process note for the orchestrator (not a PO decision): the desk part of the spike (AC 1, 3, 4, and C1 measurements against provider test IPs) does not use NAV-001 code. It could start before NAV-001 is formally accepted, to save calendar time. AC 6 onwards needs NAV-001 accepted.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | — | hosting ADR (architect, TBD) | ADR review | PO request 2026-09-29 |
| AC2 | — | — | hosting ADR (architect, TBD) | C1 latency measurements (architect, recorded in ADR) | |
| AC3 | — | — | hosting ADR (architect, TBD) | ADR review | D4 rule, D5 budget (2026-09-30) |
| AC4 | — | — | hosting ADR (architect, TBD) | ADR review against sizing baseline | |
| AC5 | — | — | this story's Change log; `docs/requirements/decisions.md` (D3–D10 plus the confirmation row); `backlog.md` decision 5 | — | D3–D10 recorded 2026-09-30 |
| AC6 | — | `getHealth` | `infra/` (backend, TBD) | phone check on ≥ 2 operators (QA report, TBD) | D6 subdomain of the company domain |
| AC7 | — | all | `infra/` TLS config (backend, TBD) | TLS check (QA, TBD) | |
| AC8 | — | — | — | RTT measurement (QA report, TBD) | |
| AC9 | — | `postRoute` | — | tethered latency test (QA, TBD) | |
| AC10 | — | `getHealth`, `getBasemapPmtiles`, `postRoute`, `search`, `reverse`, preflights | `backend/` (NAV-001) | `tests/smoke/run.sh` with `BASE_URL=https://<staging-host>` | |
| AC11 | — | `preflightRoute`, `preflightSearch`, `preflightTiles` | `.env` CORS key (backend) | CORS check (`make cors-check`, QA) | |
| AC12 | — | — | `infra/` firewall and SSH config (backend, TBD) | external port scan (QA, TBD) | |
| AC13 | — | `postRoute`, `search` (429 response to be added to openapi.yaml, architect) | gateway rate limit (backend, TBD) | load test (QA, TBD) | D8 unlisted URL plus rate limits |
| AC14 | — | all | gateway and proxy log config (backend, TBD) | log inspection (QA, TBD) | |
| AC15 | — | — | `make rebuild-data` on staging | rebuild run record + smoke (QA, TBD) | |
| AC16 | — | — | — | `make stats` record (QA, TBD) | |
| AC17 | — | `getHealth` | uptime monitor config (backend, TBD) | alert drill (QA, TBD) | D7 PO-side operator receives alerts |
| AC18 | — | — | metrics and alert config (backend, TBD) | alert config review (QA, TBD) | |
| AC19 | — | — | backup scripts in `infra/` (backend, TBD) | backup review (QA, TBD) | |
| AC20 | — | all | runbook (backend, TBD) | timed restore drill (QA, TBD) | |
| AC21 | — | — | `infra/`, runbook, `backend/.env.example` | repo inspection + secret scan (QA, TBD) | |
| AC22 | — | — | — | invoice check (PO/architect, recorded in ADR) | D5 ceiling USD 150 per month |
| AC23 | — | `servers` in openapi.yaml | `docs/architecture/api/openapi.yaml`, `backend/README.md` | doc check (QA) | |
| AC24 | — | — | this story's Change log; `docs/requirements/decisions.md` (legal review row) | record check: date on or before the first outside invitation (QA, TBD) | D9 2026-09-30 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 | PO request: add hosting to the backlog | Created as a spike-type enabler: architect spike with criteria C1–C10 and options A/B/C, PO decision, staging on HTTPS reachable from Mongolian mobile networks, NAV-001 smoke suite against staging, rebuild capacity for NAV-006, monitoring, backups and runbook. Production hosting left to a follow-up story. | The backend runs only in a temporary dev container. NAV-002 (sharing), NAV-005 (real phones) and NAV-007 (device tests) need a hosted backend. Turns backlog owed decision 5 into a story. |
| 2026-09-29 | Sizing baseline correction | Peak build memory in the sizing baseline changed from 4.24 GB to **5.5 GB** (Mongolia cold first run, tiles-build 5.0 GB). The 4.24 GB figure is kept as context: it was the source-switch rebuild with cached auxiliary files. Added the AC 16 check (about 6.5 GB ≈ 40 % of 16 GB). The 16 GB RAM recommendation is unchanged. No AC changed | A new staging host starts from an empty `data/`, so the cold-run peak is the right sizing input. Sources: `backend/README.md` "Measured on the reference machine", ADR-0002 Amendment 1 |
| 2026-09-30 | PO decisions D3–D10 (`docs/requirements/decisions.md`, PO answer "all recommended", relayed by the orchestrator) | **Front matter:** priority must / Phase 0 is PO-confirmed (D3), and status moves from `draft` to `ready`. **Context:** new bullet listing D3–D10. **AC 3:** the staging option is chosen by the D4 rule and must fit ≤ USD 150 per month (D5), or the ADR lists what would fit; the list of decisions still needed shrinks to the D4 confirmation, D10 and Q5. **AC 5:** rewritten. The PO's 2026-09-30 decisions are taken as recorded, and AC 5 now covers only the PO's confirmation of the D4 result and Q5, recorded here and in `decisions.md`. **AC 6:** the host is a subdomain of the PO's company domain (D6). **AC 17:** alerts go to the named PO-side operator (D7). **AC 22:** the ceiling is USD 150 per month (D5). **New AC 24** (section F): legal gate before outside testers use a host abroad (D9). It is numbered after AC 23 so existing numbers stay stable. **Risks:** R2, R3 (D10 unanswered), R5, R9 (NAV-001 D1, z14) and R10 updated. **Open questions:** Q1–Q4 and Q6–Q8 marked decided. The Q1 information request stays open (D10), and Q5 is flagged as not in the relayed list, with (a) as the working assumption. **Traceability** AC3, AC5, AC6, AC13, AC17 and AC22 updated, and AC24 added | The PO accepted every BA recommendation. Status moves to `ready` because no open question blocks the spike: D10 is handled by AC 1 ("no details received by <date>"), and Q5 already matches AC 10 and AC 15. NAV-001 acceptance stays a dependency for AC 6 onwards |
