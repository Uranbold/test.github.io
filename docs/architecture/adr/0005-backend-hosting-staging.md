# ADR-0005: Backend hosting: in-country VM in Ulaanbaatar for staging, gated by a mobile latency trial, with Vultr Tokyo as the fallback

- **Status:** proposed (awaiting the C1 trial results and the PO decision, NAV-008 AC 5)
- **Date:** 2026-09-30
- **Stories:** NAV-008 (AC 1–4). Unblocks NAV-002 sharing, NAV-005/NAV-007 phone tests, NAV-006
- **Evidence:** [spike nav-008-hosting](../spikes/nav-008-hosting.md). All figures, sources and evidence labels (measured by a third party / quoted / documentation / estimate / unknown) are there

## Context
- The NAV-001 stack (ADR-0001, ADR-0002, ADR-0003) runs only in a dev container. Phones need a public HTTPS host. This ADR chooses **where** the stack runs, not **what** runs. `openapi.yaml` paths do not change.
- **Decisions the PO has already made (2026-09-30):**
  - hosting in Mongolia or on the PO's own servers is preferred **if** the median TCP RTT from Mongolian mobile networks is < 50 ms, otherwise an international region for staging
  - budget USD 50–150/month
  - a subdomain of the company domain
  - a named PO-side operator, with backend delivering infrastructure-as-code plus a runbook
  - an unlisted URL with rate limits
  - a legal review before tester traffic goes abroad
- **Sizing:** one VM with ≥ 4 vCPU, ≥ 16 GB RAM and ≥ 100 GB SSD. NAV-001 measured a cold build peak of 5.5 GB plus < 1 GB of services, which is 41 % of 16 GB. 8 GB would be 81 % and fails the 70 % limit in AC 16.
- **Third-party measurements (RIPE Atlas, 7 days to 2026-09-30, fixed network in UB; indirect, not mobile, ICMP):**
  - **Abroad:** Hong Kong 52–56 ms (some carriers), Vultr Tokyo 61 ms, Singapore 79–95 ms, Seoul 107–123 ms. Nothing abroad is < 50 ms before the mobile last mile is added.
  - **Inside Mongolia:** 1.2 ms between locally interconnected networks, but **58–104 ms between networks whose traffic crosses from MobiCom (AS55805) to Gemnet (AS45204)**. A Cloud.mn VPS, whose only visible upstream is MobiCom, reaches the Gemnet UB anchor in 104 ms. Gemnet is not listed at either Mongolian IXP (MIX MNDC, MISPA-IXP) in PeeringDB.
- **Law:** the Law on Personal Data Protection (in force 2022-05-01) treats location data as personal data and restricts transfer abroad without consent or a legal basis. A 2023 Information Security Requirement (MDDIC) requires processing servers in Mongolia for sensitive personal data. Route and search requests contain coordinates. Counsel must say whether processing them abroad counts as a transfer.
- **Option C (PO's own servers): no details received from the PO by 2026-09-30.**

## Decision (proposed)
1. **Staging runs on one VM in Ulaanbaatar (option A)**, at the provider that **passes C1 on ≥ 2 Mongolian mobile operators** in the trial defined in the spike (§5: TCP/443 connect, ≥ 20 samples per operator, median and p95, egress ASN recorded).
   - **Candidates to trial in parallel:**
     - **Cloud.mn** (iTools, MobiCom upstream): "General 2" with 4 vCPU / 16 GB / 100 GB SSD, 357,500 MNT ≈ USD 99/month (public price API, 2026-09-30)
     - **Datacom**: "V3-SSD" with 6 cores / 16 GB / 320 GB, 374,000 MNT ≈ USD 104/month (price page, 2026-09-30)
     
     They probably sit on different upstreams, so trying both raises the chance of passing on ≥ 2 operators.
   - If both pass, pick the one that passes on more operators. On a tie, pick **Cloud.mn**: hourly billing, self-service resize, and published backup, snapshot and object-storage prices.
   - The PO's own servers join the same trial if they exist in UB.
2. **Fallback (option B):** if no candidate in Mongolia passes on ≥ 2 operators within **10 working days** of the PO go-ahead, staging runs on **Vultr Tokyo (nrt) `vc2-6c-16gb`**: 6 vCPU / 16 GB / 320 GB SSD, 5 TB transfer, USD 80 + 20 % backups = USD 96/month (public API, 2026-09-30).
   - This needs the measured C1 values recorded as an **exception under AC 5**, and legal counsel's clearance **before** tester traffic goes to it.
   - Hong Kong is an alternative only after a written in-budget quote. AWS ap-east-1 (`m6i.xlarge` USD 0.264/h ≈ USD 193/month compute only) is over budget.
3. **Staging specification:** 4 vCPU (8 if that is the provider's 16 GB plan), 16 GB RAM, 100 GB SSD/NVMe, 1 public IPv4 (plus IPv6 if offered), Ubuntu 24.04 LTS with unattended security upgrades, Docker Engine and the Compose plugin.
   - Optionally, a 1 vCPU / 1 GB **ops VM at a different provider** (≈ USD 5) for external uptime checks (AC 17) and as the encrypted off-host backup target for configuration (AC 19). It holds no personal data.
   - **Estimated total: USD 100–120/month.**
4. **Infrastructure-as-code is provider-agnostic.** It consists of cloud-init user-data, an idempotent script or Ansible playbook, and the existing `backend/compose.yaml`. The only provider-specific step is "create a VM with this user-data, open 80/443/SSH". Moving between A1, A2 and B1 (or to production) is then a re-run, not a rewrite. Terraform is optional.
5. **Production path (to confirm in a later story):** hosting in Mongolia, **two VMs** (blue/green or warm standby) that **cover both upstream sides** (MobiCom and Gemnet), either through two providers or through one multi-homed or IXP-connected provider. E: about USD 200–260/month. An international region only as a cold DR copy, if counsel allows it.
6. **Excluded:** mainland China regions (ICP filing, cross-border rules) and Russian Siberia and the Far East (146–254 ms measured, payment and sanctions problems).

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **A1 Cloud.mn, UB (proposed primary)** | In Mongolia (C3, C10). Hourly billing. Public prices for VM, SSD/NVMe, IP, backup, snapshot and object storage. Tier II DC, ISO 27001 (claimed). About USD 99 | **C1 unmeasured.** Single MobiCom upstream, and a 104 ms path to Gemnet-side networks was measured. SLA, power redundancy, API and egress terms unknown |
| **A2 Datacom, UB (proposed second trial)** | In Mongolia. 16 GB plan at about USD 104. Probably on the Gemnet side, which complements A1 | **C1 unmeasured.** Non-refundable monthly billing. VAT, disk type, SLA, backups and IPv6 not published |
| A3 other UB providers (NDC/MIX, MobiCom Mogul DC, Unitel DC) | NDC runs the MIX IXP, which may give the best domestic reach. Tier III claims | Quote-only. Kept for production or if A1 and A2 fail |
| **B1 Vultr Tokyo (proposed fallback)** | Fastest self-service cloud abroad (61 ms fixed). Mature API, backups and snapshots. USD 96 with backups. Ready the same day | Expected to **miss C1** on mobile (E 80–90 ms). Data processed abroad (legal gate). Mongolian users lose service if international links degrade |
| B2 Akamai/Linode Tokyo | USD 116 with backups. 8 TB transfer | RTT not measured. No advantage over B1 |
| B3 Hong Kong (AWS / Alibaba / Tencent) | Lowest-RTT city abroad (52–56 ms fixed via some carriers) | AWS over budget. Alibaba/Tencent prices not verified. Same legal and C10 issues as B1 |
| Singapore / Seoul | Many providers | 79–123 ms measured. No advantage |
| C PO's own servers | No new cash cost. Possibly in Mongolia | No details received by 2026-09-30. Admin, public IP and capacity unknown |

## Consequences
- **Easier:** one data-residency story. Mongolian users keep service if international links degrade. Costs are in MNT inside the budget. The same IaC works on any provider.
- **Harder:**
  - A short trial (≤ USD 50, ≤ 5 working days) comes before staging.
  - Local provider SLA, power, backups and egress terms must be asked in writing.
  - Monitoring and off-site backup must be self-built (ops VM), because local providers do not offer them.
  - Some operators may still see high RTT because of domestic peering. The production story must address this.
- **Follow-up work:**
  - backend NAV-008 staging IaC (AC 6–23)
  - QA C1 trial script, measurements and the staging verification plan
  - architect contract update: `429` on `postRoute`/`search` and the staging `servers` entry in `openapi.yaml` (AC 13, AC 23)
  - BA production hosting story
- **To finish this ADR (AC 2, AC 5):** add a table here with each candidate's measured C1 per operator (median, p95, n, network type, place, date, ASN). Then set the status to `accepted`, or record the PO's alternative, and record the first month's actual cost (AC 22).
