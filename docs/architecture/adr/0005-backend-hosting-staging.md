# ADR-0005: Backend hosting: staging on a Hostinger VPS in Singapore (C1 exception accepted); production in Mongolia, chosen by an in-country phone trial, with ICT Group's own servers as the leading candidate

- **Status:** **accepted for staging** (PO decision 2026-09-30, NAV-008 AC 5). The production path (Decision §4) stays **proposed**. The production hosting story decides it.
- **Date:** 2026-09-30 (proposed and accepted the same day)
- **Stories:** NAV-008 (AC 1–5 here; AC 6–24 through the staging deployment). Unblocks NAV-002 sharing, NAV-005/NAV-007 phone tests and NAV-006
- **Evidence:** [spike nav-008-hosting](../spikes/nav-008-hosting.md). All figures, sources and evidence labels (M-3P measured by a third party / Q quoted / D documentation / E estimate / U unknown) are there
- **Deployment design:** [deployment-staging.md](../deployment-staging.md) (host baseline, TLS, firewall, rate limits, logs, monitoring, backups, rebuild)
- **Contract:** `openapi.yaml` **0.4.0** adds the staging `servers` entry and `429 RateLimited` (additive, no path change)

## Context
- The NAV-001 stack (ADR-0001, ADR-0002, ADR-0003) runs only in a dev container. Phones need a public HTTPS host. This ADR chooses **where** the stack runs, not **what** runs.
- **PO decisions of 2026-09-30 before the spike** (`docs/requirements/decisions.md` D3–D9): prefer Mongolia or the PO's own servers if the median TCP RTT from Mongolian mobile networks is < 50 ms, otherwise an international region for staging (D4); budget USD 50–150/month (D5); a subdomain of the company domain (D6); a named PO-side operator, with backend delivering infrastructure-as-code plus a runbook (D7); an unlisted URL with rate limits (D8); a legal review before outside testers' traffic goes abroad (D9).
- **The spike (same day) proposed** a two-provider latency trial in Ulaanbaatar (Cloud.mn, Datacom) before staging, with Vultr Tokyo as the fallback. See "History" below.
- **PO decision of 2026-09-30 after the spike (this ADR's acceptance):**
  - Staging runs on a **Hostinger VPS, plan KVM 4, in Singapore**: 4 vCPU AMD EPYC, 16 GB RAM, 200 GB NVMe, 16 TB bandwidth, 1 Gbps, full root, KVM virtualisation, weekly backups and snapshots, Wanguard DDoS filtering.
  - The PO accepts a **C1 latency exception for staging**: about 100 ms from Mongolian phones instead of the 50 ms target.
  - Staging is **tester-only**. It holds no real users' personal data.
  - **Production goes on servers in Mongolia later, most likely ICT Group's own servers.** Details are pending from the PO (D10 is now partly answered).
- **Sizing baseline:** ≥ 4 vCPU, ≥ 16 GB RAM, ≥ 100 GB SSD. NAV-001 measured a cold build peak of 5.5 GB plus < 1 GB of services, which is 41 % of 16 GB (AC 16 limit 70 %).
- **Latency evidence for Singapore (M-3P, fixed network in UB, ICMP, 7 days to 2026-09-30):** DigitalOcean 78.7 ms, OVHcloud 79.0 ms, Vultr 91.1 ms, Console Connect 94.9 ms (medians; spike §3.1). **Hostinger Singapore itself was not measured.** The spike estimated ≥ 100 ms median from Mongolian mobile networks to Singapore (E). For comparison, Vultr Tokyo was 61 ms fixed and Hong Kong 52–56 ms.
- **Law (not legal advice; for the PO's counsel):**
  - The **Law on Personal Data Protection** (in force 2022-05-01) treats location data as personal data and restricts transfer abroad without the data subject's consent or another legal basis.
  - The **Information Security Requirement of 2023-09-11** (Ministry of Digital Development, Innovation and Communications) requires the processing server to be located in Mongolia for controllers that process **sensitive** personal data. Whether location data falls under it is for counsel (the secondary source in the spike does not list location as sensitive).
  - Route and search requests carry coordinates. On a host in Singapore they are processed abroad (in memory), even though no log keeps them.

## Decision

### 1. Staging host (accepted)
| Item | Value |
|---|---|
| Provider, plan, location | **Hostinger VPS KVM 4, Singapore data centre** |
| Size | 4 vCPU (AMD EPYC), 16 GB RAM, 200 GB NVMe, 16 TB/month transfer, 1 Gbps, 1 public IPv4 (plus IPv6 if assigned) |
| Meets AC 4 | Yes: ≥ 4 vCPU, ≥ 16 GB, ≥ 100 GB SSD. 200 GB leaves ≥ 50 GB free after a build with room for a second data copy (AC 16, NAV-006) |
| OS | Ubuntu 24.04 LTS, unattended security upgrades, Docker Engine and the Compose plugin from Docker's apt repository |
| Stack | `backend/compose.yaml` (NAV-001) unchanged, plus a staging overlay under `infra/staging/` that adds **Caddy** as the TLS terminator in front of the nginx gateway |
| Hostname | `staging.<domain>`, a subdomain of the PO's company domain (D6). **Placeholder** until the PO provides the domain. In `openapi.yaml`: `https://staging.example-placeholder` |
| Data | Geofabrik `mongolia-latest` (reachable from Singapore; blocked only in the dev container), rebuilt daily by a scheduled job with a restart (interim until NAV-006 defines blue/green) |
| Provider-level protection | Hostinger Wanguard DDoS filtering (volumetric), plus the provider's weekly backups and snapshots (on-provider, so they do **not** satisfy AC 19 on their own) |
| Off-host items | A small ops VM at a **different provider** for the external uptime check (AC 17) and as the encrypted backup target for configuration (AC 19). See deployment-staging.md §9–§10 |
| Cost | Recorded from the first full month's invoice (AC 22). The KVM 4 plan plus an ops VM is expected to stay well below the USD 150 ceiling (E) |

The full deployment architecture is in [deployment-staging.md](../deployment-staging.md). Its main choices:
- **TLS: Caddy** (Apache-2.0) with automatic Let's Encrypt, chosen over certbot (reasons in deployment-staging.md §5).
- **Host firewall:** only 22 (keys only, no passwords, no root login), 80 and 443, enforced at the Hostinger panel firewall **and** with UFW. Docker publishes only Caddy's 80/443. The gateway stays bound to `127.0.0.1`.
- **Rate limits at the host edge**, enforced by the nginx gateway (per client IP taken from Caddy's `X-Forwarded-For`), because only the gateway can return the contract's `GatewayError` JSON with correct CORS headers (ADR-0002 §3). Hostinger Wanguard covers volumetric attacks in front of the host.
- **Logs:** no client IPs, query strings, bodies or coordinates anywhere on the request path (Caddy access log off, Caddy runtime log restricted to certificate events, gateway format unchanged, Valhalla redaction unchanged).
- **CORS:** an explicit allowlist of the web demo origin(s) plus `http://localhost:5173`. Never `*` on staging.

### 2. C1 exception for staging (accepted, AC 5)
- **Accepted by the PO on 2026-09-30:** staging may miss the C1 target (median TCP connect RTT < 50 ms from Mongolian mobile networks). The accepted value is **about 100 ms**.
- **Measured value at acceptance:** none from Mongolian mobile networks (spike: "C1 unmeasured"). The basis is the M-3P fixed-network figures for other Singapore providers (78.7–94.9 ms median) and the E ≥ 100 ms mobile estimate.
- **AC 8 threshold:** AC 8 uses the accepted exception value instead of 50 ms. **Proposed exact threshold: median ≤ 100 ms per operator** on ≥ 2 operators, p95 recorded. The PO confirms the exact number (open question in the handoff). The spike's own estimate sits at this threshold, so a miss on some operators is possible. A miss is reported to the PO as a finding. It is not silently accepted.
- **Unchanged:** AC 9 (route p95 ≤ 500 ms **including** the network, one kept-alive HTTPS connection). With about 100 ms RTT and one request per round trip on a warm connection, this is expected to hold (E). Server-side NFR-L1 (route p95 ≤ 500 ms) is unaffected.
- **Also recorded when measured (AC 8):** the table at the end of this ADR, per operator.

### 3. Legal caveat for staging (AC 24)
- Staging is **outside Mongolia**. AC 24 therefore **applies**: before the staging URL is given to anyone outside the project team (outside testers, the NAV-007 review panel, partner drivers), a dated record of the counsel's review must exist (D9). The PO's statement "tester-only, no real users' personal data" narrows the risk but does **not** replace that record: a tester's own route requests carry that tester's coordinates, which are personal data of the tester.
- Architect recommendation for the counsel's scope: (i) in-memory processing of testers' coordinates in Singapore under the Law on Personal Data Protection; (ii) whether a tester consent text is needed and what it says; (iii) whether the 2023 Information Security Requirement applies to location data; (iv) the ops VM abroad holding only configuration and `/health` probes.
- Technical safeguards on staging (deployment-staging.md §8): no client IPs, coordinates, query strings or bodies in any log; no user accounts; no stored traces; staging is never used for real users; no real users' data is ever imported.
- These questions and the 2023 rule are the main reason production goes to Mongolia (§4).

### 4. Production selection path (proposed; decided in the production hosting story)
- **Leading candidate: ICT Group's own servers (option C)**, pending details from the PO. Needed:
  - data-centre location (city, facility) and power/UPS/generator arrangements (winter load in UB)
  - **upstream network(s)**: ASN(s), whether on the MobiCom (AS55805) side, the Gemnet (AS45204) side, or both, and membership of MIX MNDC or MISPA-IXP (spike §3.2: domestic peering decides latency)
  - spare capacity for two VMs of the staging size (4 vCPU / 16 GB / ≥ 100 GB SSD each), virtualisation type, and whether Docker can run
  - public IPv4 (and IPv6) with inbound 80/443, and who administers the hosts (role, not name)
  - backup and snapshot facilities, and whether a second site exists
  - own map data or Valhalla endpoints, if any (D10 remainder)
- **Other candidates kept from the spike:** A1 Cloud.mn (MobiCom side), A2 Datacom (probably Gemnet side), A3 National Data Center / MIX and other UB providers on quote.
- **Selection method: the in-country phone trial** (spike §5), **with the skeptic review's amendments**:
  1. **Duration ≥ 3 days.** Measurements run on at least three separate days, each with the morning, midday and evening-peak windows, ≥ 20 TCP/443 samples per operator per candidate per run, on ≥ 2 operators (all four preferred), with the egress ASN recorded. One good day is not a pass.
  2. **Full download timing (C9).** On each trial VM, time the **complete** 931 MB OSM water-polygons download (`WATER_POLYGONS_URL`) and the complete Geofabrik `mongolia-latest` download, not only a 1 KB range request. Record the throughput. A cold build from an empty cache must still fit AC 15's 30 minutes, so international transit from the host is tested, not assumed.
  3. **Datacom egress and disk checks.** Get Datacom's egress terms in writing (monthly cap, overage price, domestic versus international traffic) and the disk type (SSD or HDD). Run a short `fio` random-read test on the trial VM. Apply the same disk check to every candidate, ICT Group's servers included.
  4. **Hong Kong quotes.** Get written quotes from Alibaba Cloud Hong Kong and Tencent Cloud Hong Kong for 4 vCPU / 16 GB, as the benchmark for an international fallback or cold DR copy (only if counsel allows it). Hong Kong is not a production primary.
- **Pass rule (unchanged):** median < 50 ms on each of ≥ 2 operators, on every trial day. ICT Group's servers join the trial as target C at no cost.
- **Production shape (unchanged):** hosting in Mongolia, **two VMs** (blue/green or warm standby, NAV-006) that cover **both upstream sides** (MobiCom and Gemnet), either through two sites or providers or through one multi-homed or IXP-connected site. E: about USD 200–260/month on rented VMs; less on own servers. An international region only as a cold DR copy, if counsel allows it.
- **Migration:** the staging infrastructure-as-code is provider-agnostic (§5). Moving to production is a re-run with a production inventory, not a rewrite. What happens to the Singapore staging host afterwards (keep as staging, or retire) is a later PO decision.

### 5. Infrastructure-as-code stays provider-agnostic
The code under `infra/staging/` is an idempotent bootstrap script (or Ansible playbook), a Compose overlay for Caddy, systemd units for rebuild, backup and checks, and the existing `backend/compose.yaml`. The only provider-specific steps are "create an Ubuntu 24.04 VM with this SSH key", "set the panel firewall to 22/80/443" and DNS. Terraform stays optional.

### 6. Excluded (unchanged)
Mainland China regions (ICP filing, cross-border rules). Russian Siberia and the Far East (146–254 ms measured, payment and sanctions problems).

## Alternatives considered
| Option | Pros | Cons | Outcome |
|---|---|---|---|
| **Hostinger KVM 4, Singapore** | Ready the same day. 16 GB / 200 GB NVMe meets the sizing with room for blue/green. 16 TB transfer. Weekly backups, snapshots and DDoS filtering included. Low monthly price (Q, PO-provided) | Misses C1 (E ≥ 100 ms mobile). Data processed abroad (AC 24 gate). Mongolian testers lose service when international links degrade (C10). Singapore was 18–34 ms slower than Vultr Tokyo in fixed measurements | **Chosen for staging by the PO**, with the C1 exception |
| A1 Cloud.mn / A2 Datacom in UB, after a phone trial (spike proposal) | In Mongolia (C3, C10). Could meet C1 on the operators that peer with the host's upstream | C1 unmeasured. Needs a trial of ≤ 5 working days before staging. Domestic peering can make either one slow for some operators | Not used for staging. **Kept as production candidates** in §4 |
| **C ICT Group's own servers** | In Mongolia. No new cash cost. Under the PO's control | Details not yet received (location, upstreams, capacity, administration) | **Leading production candidate**, pending details |
| B1 Vultr Tokyo (spike fallback) | 61 ms fixed, the fastest self-service region abroad. USD 96 with backups | Still expected to miss C1 on mobile (E 80–90 ms). Same legal gate as Singapore | Not chosen by the PO |
| B3 Hong Kong (AWS / Alibaba / Tencent) | Lowest RTT city abroad (52–56 ms fixed via some carriers) | AWS over budget. Alibaba and Tencent prices not verified | Written quotes requested for the production path (DR benchmark only) |

TLS terminator alternatives (Caddy versus certbot) are compared in deployment-staging.md §5.

## Consequences
- **Easier:**
  - Phone testing (NAV-002 sharing, NAV-005, NAV-007 device tests) is unblocked without waiting for a trial or procurement in Mongolia.
  - The same infrastructure-as-code later runs on production hosts in Mongolia.
  - 200 GB NVMe and 16 GB RAM leave room for NAV-006 blue/green on the same host.
- **Harder:**
  - Mongolian testers see about twice the target RTT. Latency results from staging (AC 8, AC 9) do **not** predict production latency, so they must not be used to judge the production choice.
  - Testers lose service when international links from Mongolia degrade (C10). Record any such incident (NAV-008 edge cases).
  - The AC 24 legal record is needed before any outside tester gets the URL.
  - Monitoring and off-host backup need an ops VM at another provider.
- **Follow-up work:**
  - backend: `infra/staging/` IaC and runbook (NAV-008 AC 6–23), gateway rate limit and `429` per `openapi.yaml` 0.4.0
  - QA: staging verification plan and runs (AC 6–24)
  - BA: record this decision in the NAV-008 Change log and `decisions.md` (AC 5), link backlog owed decision 5 to this ADR, and write the production hosting story with §4 as its selection method
  - PO: company domain and DNS access, ACME contact mailbox, alert channel for the operator, ICT Group server details, counsel review before outside testers

## Staging measurements (to fill in at AC 8 and AC 22)
| Operator | Network type | Place (district) | Date | Egress ASN | n | Median ms | p95 ms | Timeouts | Pass (≤ accepted threshold) |
|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — |

| Month | Hostinger invoice (USD) | Ops VM (USD) | Total (USD) | ≤ USD 150 (AC 22) |
|---|---|---|---|---|
| — | — | — | — | — |

## History
- **2026-09-30, proposed:** staging on a VM in Ulaanbaatar at whichever of Cloud.mn or Datacom passed C1 on ≥ 2 operators in a phone trial, with Vultr Tokyo as the fallback after 10 working days, and production in Mongolia on two VMs covering both upstreams.
- **2026-09-30, accepted (this version):** the PO chose Hostinger KVM 4 in Singapore for staging with a C1 exception, skipping the trial for staging. The trial, with the skeptic review's amendments, moved to the production selection path, and ICT Group's own servers became the leading production candidate.
