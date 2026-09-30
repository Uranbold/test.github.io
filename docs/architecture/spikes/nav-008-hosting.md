# Spike: NAV-008 backend hosting (staging first, production path)

- **Story:** [NAV-008](../../requirements/stories/NAV-008-backend-hosting-staging.md), AC 1–4, criteria C1–C10
- **Owner:** architect
- **Date:** 2026-09-30 (one timeboxed desk pass)
- **Status:** recommendation for the PO. The PO decides (AC 5). Draft decision: [ADR-0005](../adr/0005-backend-hosting-staging.md) (status `proposed`)
- **Evidence labels used below:**
  - **M-3P** means measured by a third party (RIPE Atlas) from a fixed network in Ulaanbaatar. This is **indirect**: it is not a Mongolian mobile network and not TCP/443.
  - **Q** means a public price list or public price API, read on the date shown.
  - **D** means provider documentation or a web page, read on the date shown.
  - **E** means an architect estimate. It is not a measurement.
  - **U** means unknown. The "how and by when" column says how it gets found out.

Nothing in this spike was measured from a Mongolian mobile network. **Every candidate is "C1 unmeasured"** in the sense of AC 2 until the protocol in §5 has been run in UB.

---

## 1. Question and short answer

**Question.** Where should the NAV-001 stack (Valhalla, Photon, PMTiles, nginx gateway, one Docker Compose) run so that phones on Mongolian mobile networks reach it over HTTPS with little delay? This covers staging now and a path to production.

**PO rule already decided (2026-09-30):** prefer hosting in Mongolia or on the PO's own servers **if** the median RTT from Mongolian mobile networks is < 50 ms. Otherwise use an international region for staging. Budget USD 50–150/month.

**Short answer.**
1. **No international region can realistically meet C1.** From a fixed network in UB the best regional targets are Hong Kong at 52–56 ms and Vultr Tokyo at 61 ms. The mobile radio link adds more on top (§3.1). Seoul (107–123 ms) and Singapore (79–95 ms) are worse than Hong Kong and Tokyo, even though they look closer on a map.
2. **Hosting in Mongolia can meet C1, but only if the host's upstream network peers locally with the tester's mobile operator.** RIPE Atlas shows about 1 ms between two UB networks that interconnect locally. It also shows **58–104 ms between UB networks whose traffic crosses from MobiCom (AS55805) to Gemnet (AS45204)**. That is as slow as Hong Kong. So "hosted in Mongolia" does not automatically mean "fast from every Mongolian operator" (§3.2).
3. **Recommendation:** run a **cheap two-provider latency trial in Mongolia first**:
   - **Cloud.mn** (iTools, upstream MobiCom): hourly billing and a published price list
   - **Datacom** (datacom.mn): monthly
   
   Then host staging on whichever passes C1 on ≥ 2 operators: **4 vCPU / 16 GB RAM / 100 GB SSD in Ulaanbaatar, about USD 99–114/month**.
   
   **Fallback**, if no provider in Mongolia passes on ≥ 2 operators within 10 working days: **Vultr Tokyo, 6 vCPU / 16 GB / 320 GB SSD, USD 96/month with backups**. This needs a recorded C1 exception (AC 5) and the legal review before tester traffic goes abroad.
   
   **Production:** the same class of hosting in Mongolia, on two VMs, preferably with both upstream sides (MobiCom and Gemnet) covered (§7).

---

## 2. Method (what was done in the timebox)

| Step | Source | Date |
|---|---|---|
| RTT from Ulaanbaatar to about 25 regional targets | RIPE Atlas anchoring-mesh ping results, 7 days (2026-09-23 → 2026-09-30, about 2,500 results per target, 3 ICMP packets each). Source: the **Gemnet UB anchor** (probe 6723, AS45204). Script queried `atlas.ripe.net/api/v2/measurements/<id>/results/?probe_ids=6723` | 2026-09-30 |
| RTT between networks inside Mongolia | RIPE Atlas "Anchoring Probes" ping and traceroute to the Gemnet UB anchor (msm 23859528) from the Mongolian probes that took part: Kewiko (AS56293), NBC (AS134356) and a **Cloud.mn VPS** (AS63962, probe 1017178) | 2026-09-30 |
| Upstreams and IXP membership of Mongolian networks | RIPEstat `asn-neighbours` and `prefix-overview`. PeeringDB `ix` / `netixlan` for country MN | 2026-09-30 |
| Prices | Cloud.mn public calculator API (`api.cloud.mn/api/v1/client/public/calculator`), datacom.mn VPS page, Vultr public `/v2/plans`, Akamai/Linode public `/v4/linode/types`, AWS public EC2 price JSON (ap-east-1, ap-northeast-1, ap-northeast-2), DigitalOcean pricing page | 2026-09-30 |
| Mobile last-mile latency in Mongolia | Opensignal figure quoted by a secondary source (Jan 2024). SpeedOf.Me Mongolia H1 2026 | 2026-09-30 |
| Law | DLA Piper "Data protection laws of the world: Mongolia" (last modified 2026-03-20). Lehman Law blog (2023-11-10). Pandectes (2025-07-24) | 2026-09-30 |
| Exchange rate | Wise mid-market, about 3,599 MNT/USD on 2026-09-14. **This spike uses 3,600 MNT = 1 USD** | 2026-09-30 |

Not done: no VM was created, no provider was contacted, nothing was measured from a phone, and the PO's own servers were not assessed (no details received).

---

## 3. Evidence

### 3.1 RTT from Ulaanbaatar to regional targets (M-3P, indirect)
Source: RIPE Atlas anchor **mn-uln-as45204** (Gemnet LLC, UB, probe 6723), ICMP, 2026-09-23 → 2026-09-30. The median and p95 are taken over the per-result average RTT.

| Region | Target anchor (operator, ASN) | RIPE msm | Median ms | p95 ms |
|---|---|---|---|---|
| **Hong Kong** | DigiCert, AS12008 | 29716323 | **52.5** | 53.1 |
| Hong Kong | PCCW / Console Connect, AS3491 | 87083312 | 54.4 | 55.0 |
| Hong Kong | Misaka, AS57695 | 32033799 | 55.7 | 56.4 |
| Hong Kong | LSHIY, AS32167 | 63880540 | 110.9 | 112.6 |
| **Tokyo** | **Vultr, AS20473** | 18399117 | **61.0** | 61.7 |
| Tokyo | G-Core, AS199524 | 23085656 | 82.4 | 143.9 |
| Tokyo | Console Connect, AS3491 | 39433115 | 103.5 | 170.2 |
| Tokyo | IIJ, AS2497 | 88535118 | 150.7 | 152.0 |
| Osaka | Tomocha Net, AS45679 | 109366776 | 117.5 | 155.8 |
| Taipei | Console Connect, AS3491 | 26069764 | 73.0 | 76.4 |
| **Singapore** | DigitalOcean, AS14061 | 29567478 | 78.7 | 86.7 |
| Singapore | OVHcloud, AS16276 | 23871530 | 79.0 | 106.5 |
| Singapore | Vultr, AS20473 | 19688015 | 91.1 | 94.8 |
| Singapore | Console Connect, AS3491 | 30715243 | 94.9 | 99.4 |
| **Seoul** | Console Connect, AS3491 | 49059411 | 106.7 | 152.5 |
| Seoul | G-Core, AS199524 | 23149914 | 109.1 | 155.3 |
| Seoul | Vultr, AS20473 | 25311578 | 123.3 | 170.9 |
| Russia | Moscow, Yandex AS13238 | 48197866 | 72.9 | 139.4 |
| Russia | Khabarovsk / Novosibirsk / Chita | 29117517 / 20920346 / 23059816 | 146.7 / 225.9 / 254.1 | 180 / 260 / 292 |
| Germany (reference) | Frankfurt, Vultr AS20473 / DigitalOcean AS14061 | 21985153 / 29556743 | 106.9 / 167.5 | 109.0 / 170.9 |

Reading:
- Nothing abroad is < 50 ms, even from a data-centre-grade fixed network. The best regions are Hong Kong (via some carriers) and Tokyo (Vultr specifically). The spread between providers in the same city (Tokyo 61–151 ms) shows that **the provider's routing matters more than the city**.
- **Mobile adds latency on top.** An Opensignal figure quoted in a January 2024 article gives Mongolian mobile latency as 23 ms, against 4 ms for fixed lines (secondary source, low confidence). SpeedOf.Me reports a 92 ms median for Mongolia in H1 2026, measured to Tokyo (65 %) and Singapore (33 %) servers. That comes from fewer than 1,000 browser tests, mixed mobile and desktop. **E:** expected mobile medians from UB are therefore about **75–80 ms to Hong Kong, 80–90 ms to Vultr Tokyo and ≥ 100 ms to Singapore or Seoul**. Every option B candidate therefore **is expected to miss C1**. This has to be confirmed by §5.
- ICMP can be deprioritised by routers. TCP/443 connect times are usually equal or slightly higher. That is why §5 measures TCP/443.

### 3.2 Inside Mongolia: domestic peering decides the latency (M-3P, indirect)
RTT **to** the Gemnet UB anchor from other Mongolian probes (msm 23859528, 7 days):

| Source probe (network, ASN) | Median ms | p95 ms | Path (RIPE traceroute, 2026-09-29/30) |
|---|---|---|---|
| Kewiko, AS56293 | **1.2** | 1.6 | local |
| NBC Co., AS134356 | 58.3 | 59.7 | via MobiCom AS55805. The RTT jumps by about 55 ms at MobiCom hop 27.123.215.206, then continues to Gemnet 180.149.92.x |
| **Cloud.mn VPS, AS63962** (iTools) | **103.8** | 106.5 | via MobiCom AS55805. The RTT jumps by about 103 ms at the same MobiCom hop |

Topology facts (RIPEstat and PeeringDB, 2026-09-30):
- **Cloud.mn (AS63962) has one visible upstream: MobiCom (AS55805).**
- The datacom.mn **website** is on Magicnet (AS45237), whose only visible upstream is **Gemnet (AS45204)**. This is weak evidence for Datacom's VPS network, which may differ.
- Mongolian IXPs listed in PeeringDB:
  - **MIX MNDC** (National Data Center) members: 17882 Univision, 38805 STX Citinet, 56293 Kewiko, 24496, **55805 MobiCom**, 139089, 10219 Skymedia, 134356 NBC
  - **MISPA-IXP** members: 55805, 17882, 10219, 56293, 134356 and others
  - **Gemnet (AS45204) is not listed at either IXP.**
- Univision (AS17882) has both MobiCom and Gemnet as upstreams. G-Mobile (AS24559) and STX Citinet go through AS139089.

**Implication.** An in-country host behind MobiCom will probably be fast for MobiCom subscribers and for operators that peer with MobiCom at MIX or MISPA. It may be as slow as Hong Kong for operators whose traffic has to cross from MobiCom to Gemnet, and the reverse holds for a host behind Gemnet. **Which ASN each mobile operator's data traffic uses was not confirmed.** Some mapping guesses:
- MobiCom mobile probably uses AS55805/AS38218
- Unitel may use Univision AS17882, which belongs to the same group
- G-Mobile is AS24559 in PeeringDB

The §5 protocol therefore records the tester's egress ASN. A C1 trial on ≥ 2 operators is not a formality. It is the decisive test.

### 3.3 Law (D, for legal counsel. This is not legal advice)
- The Law on Personal Data Protection (adopted 2021-12-17, in force 2022-05-01) lists **location data** as personal data. DLA Piper summarises it as: "Transfer of Personal Data is prohibited unless otherwise approved under the relevant laws or permitted by the Data Owner". Lehman Law puts it as: forbidden "to transfer personal data outside of Mongolia without the consent of the data subject".
- DLA Piper (last modified 2026-03-20) also cites the **Information Security Requirement** of the Ministry of Digital Development, Innovation and Communications (2023-09-11): the information processing server must be "located in the territory of Mongolia". It links this to controllers that process **sensitive** personal data. Pandectes' list of sensitive categories does **not** include location.
- For us: route and search requests carry coordinates and place names. On an option B host they are **processed abroad** (in memory), even though staging logs keep no IPs or coordinates (AC 14). Whether in-memory processing abroad counts as "transfer", and whether tester consent is enough, is for the PO's counsel. This is already decided as a gate before tester traffic goes abroad. **Option A avoids the question.**

---

## 4. Options and scoring (C1–C10)

### 4.1 Candidates
| ID | Candidate | Why shortlisted |
|---|---|---|
| **A1** | **Cloud.mn** (iTools JSC, "national cloud"), Ulaanbaatar | Public price calculator, hourly billing, self-service console, object storage, snapshot and backup items. Claims Tier II data centre, ISO 9001/27001 and 99.98 % uptime (D). A Cloud.mn VPS is already a RIPE Atlas probe, so its path is known (§3.2) |
| **A2** | **Datacom** (datacom.mn), Ulaanbaatar | Public VPS price list with a 16 GB plan. Likely on the Gemnet side (weak evidence), which complements A1 |
| A3 | Others in UB: National Data Center (datacenter.gov.mn, runs MIX), MobiCom Mogul DC (Tier III claim), Unitel DC, servers.mn, G-Mobile Cloud | Colocation or VMs on quote only, or plans too small in public price lists. **U:** ask for quotes only if A1 and A2 both fail C1 |
| **B1** | **Vultr Tokyo (nrt)** | Lowest measured RTT of any self-service cloud provider (61 ms fixed). Public API prices, backups for +20 % |
| B2 | Akamai/Linode Tokyo (ap-northeast / jp-tyo-3) | Comparable price. RTT not measured. Its speed-test host is included in §5 |
| B3 | Hong Kong: AWS ap-east-1 / Alibaba Cloud cn-hongkong / Tencent HK | Hong Kong is the lowest-RTT city (52–56 ms fixed via some carriers). AWS is **over budget** (below). Alibaba and Tencent HK: **U**, price not verified in the timebox |
| C | **PO's own servers** | **No details received from the PO by 2026-09-30.** Conditional. Score it once the PO answers Open question 1 |
| excluded | Mainland China regions (ICP filing needed, cross-border rules both ways). Russian Siberia and the Far East (146–254 ms measured, plus payment and sanctions problems). Singapore and Seoul (79–123 ms measured, no advantage over Tokyo or Hong Kong) | |

### 4.2 Staging price comparison (Q unless marked, read 2026-09-30, 1 USD = 3,600 MNT)
| Candidate | Configuration | Monthly | Notes |
|---|---|---|---|
| **A1 Cloud.mn** "General 2" | 4 vCPU, 16 GB, 100 GB SSD, 1 IPv4 | **357,500 MNT ≈ USD 99** postpaid (vCPU 13,200 × 4 + RAM 13,200 × 16 + SSD 880 × 100 + IP 5,500). Prepaid: 339,625 MNT ≈ USD 94 | NVMe instead of SSD: +44,000 MNT → 401,500 ≈ USD 112. Provider backup of 100 GB: +33,000 MNT. Snapshots cost 220 MNT/GB. The unit prices are multiples of 1.1, so they **probably include 10 % VAT** (E, confirm). Egress terms: **U** |
| A1 Cloud.mn "Cloud 5" | 8 vCPU, 16 GB, 100 GB SSD | 410,300 MNT ≈ USD 114 | For a faster rebuild (not needed, §6) |
| **A2 Datacom** V3-SSD | 6 cores, 16 GB, 320 GB "SSD/HDD", 1 IP | **374,000 MNT ≈ USD 104** | VAT status, disk type, SLA, backups, bandwidth and IPv6 are not stated (D). VPS is **non-refundable**. Instalment payment offered |
| A2 Datacom V2-SSD | 4 cores, 8 GB, 160 GB | 198,000 MNT ≈ USD 55 | **Too small**: 5.5 GB build peak plus < 1 GB services is 81 % of 8 GB, above the AC 16 limit of 70 % |
| **B1 Vultr Tokyo** `vc2-6c-16gb` | 6 vCPU, 16 GB, 320 GB SSD, 5 TB transfer, IPv4 | **USD 80** + automatic backups 20 % (USD 16) = **USD 96 ≈ 345,600 MNT** | International card |
| B1 Vultr Tokyo `vhf-4c-16gb` | 4 vCPU high-frequency, 16 GB, 384 GB NVMe | USD 96 (+ USD 19.20 backups) | |
| B2 Linode 16GB, Tokyo | 6 vCPU, 16 GB, 320 GB, 8 TB transfer | USD 96 + backups USD 20 = USD 116 | |
| B3 AWS ap-east-1 `m6i.xlarge` | 4 vCPU, 16 GB | USD 0.264/h ≈ **USD 193** compute only, plus EBS, public IPv4 (USD 0.005/h) and egress | **Over the USD 150 ceiling.** Tokyo `m6i.xlarge` is USD 0.248/h, Seoul USD 0.236/h |
| B3 Alibaba / Tencent HK | 4 vCPU, 16 GB | **U** | Get a written quote only if the PO wants Hong Kong as the fallback instead of Tokyo |
| Ops VM (§6, optional) | 1 vCPU, 1 GB, e.g. Vultr `vc2-1c-1gb` or Linode Nanode | USD 5 | External uptime checks every 60 s, plus the off-host config backup target |

The domain is a subdomain of the PO company domain (USD 0). The TLS certificate comes from Let's Encrypt (USD 0). **E:** egress for staging, with 50 testers × 20 sessions × about 15 MB of tile ranges, is about 15 GB/month. That is negligible against 5 TB on B1 and needs checking against A1 and A2 terms.

### 4.3 Scoring matrix
Legend: ✔ meets, ~ partly or with conditions, ✘ misses, **U** unknown (how and when in the last column). All dated 2026-09-30.

| # | A1 Cloud.mn | A2 Datacom | B1 Vultr Tokyo | B3 Hong Kong (AWS / Alibaba) | C PO servers | How and when unknowns get resolved |
|---|---|---|---|---|---|---|
| **C1 latency** | **C1 unmeasured.** E: about 25–35 ms for MobiCom and operators that peer with MobiCom, about 120 ms for traffic that has to reach Gemnet. M-3P: the Cloud.mn VPS reaches Gemnet in 104 ms | **C1 unmeasured.** E: likely fast on the Gemnet side and possibly slow from MobiCom (mirror of A1) | **C1 unmeasured.** M-3P 61 ms fixed. E: 80–90 ms mobile → **expected ✘** | **C1 unmeasured.** M-3P 52–56 ms fixed (other carriers). E: 75–80 ms mobile → **expected ✘** | U | §5 protocol on ≥ 2 operators. Trial VMs by working day 3 after the PO go-ahead, measurements by day 5 |
| **C2 cost** | ✔ about USD 99–114 staging (Q). Production E: 2 VMs about USD 200–230 | ✔ about USD 104 (Q). VAT **U** | ✔ USD 96 (Q). Production E: 2 VMs about USD 192 + CDN | AWS ✘ > USD 193 (Q). Alibaba **U** | E: no new cash cost | Datacom VAT and egress: written quote. Alibaba HK: quote only if needed |
| **C3 residency / legal** | ✔ data in Mongolia. MNT invoice. Mongolian contract law | ✔ same as A1 | ✘ processed in Japan: legal review gate. International card. Contract under the provider's home jurisdiction (US for Vultr, D) | ✘ processed in Hong Kong: legal review gate. AWS contract under US law; Alibaba under Singapore/HK law (D) | ✔ if in Mongolia | Legal counsel before any B traffic (PO decision) |
| **C4 operations** | ~ Self-service console, hourly billing, resize. Tier II DC, ISO 27001 (D). SLA 99.98 % claimed (D). UPS/generator **U**. API/Terraform **U**. Support in Mongolian | ~ Monthly, non-refundable. SLA **U**. Snapshots, API and power **U**. Support in Mongolian | ✔ Mature API, snapshots and console. SLA published. English support 24/7 | ✔ (AWS) | U | Ask A1/A2: SLA, power redundancy (winter), maintenance windows, API. Answer due by trial day 5 |
| **C5 sizing and scale** | ✔ General 2/Cloud 5 meet the target. Resize by flavour. Object storage exists (PMTiles/CDN later) | ✔ V3 meets the target (confirm SSD). Resize: **U** | ✔ Resize and multiple VMs. Object storage and CDN available | ✔ | U | A2: confirm SSD and resize in the quote |
| **C6 backups** | ~ Provider backup and snapshots in the same DC (330 / 220 MNT per GB). Off-site copy needs a second location (ops VM or object storage elsewhere) | **U** | ✔ Automatic backups (+20 %). Snapshots. Off-host copy still needed for AC 19 | ✔ | U | Runbook design in the staging story |
| **C7 TLS / domain** | ✔ public IPv4 included. ACME HTTP-01 on 80 is possible if inbound 80/443 are open (**U**, check on trial). IPv6 **U** | same as A1. IPv6 **U** | ✔ IPv4 and IPv6 (D, not re-verified) | ✔ | U | Trial: `curl` from outside to ports 80 and 443. Check the console for IPv6 |
| **C8 monitoring** | ~ No provider monitoring known. Self-host node-exporter/Prometheus (Apache-2.0) and an external checker (ops VM with Uptime Kuma, MIT) | same as A1 | ~ Provider graphs and alerts exist. External checker still needed | ✔ (CloudWatch) | U | Staging story (AC 17–18) |
| **C9 outbound downloads** | **U.** International transit via MobiCom. Nothing suggests blocking. Geofabrik was blocked only by the dev-container proxy | **U** | ✔ expected. Unrestricted egress from Tokyo (E) | ✔ expected | U | Trial: run the §5.6 download check on each trial VM |
| **C10 international-link resilience** | ✔ Mongolian users keep service if international links degrade. Only the daily rebuild stops, and the old data keeps serving (NAV-001 atomic rules) | ✔ same as A1 | ✘ Mongolian users lose service when international links degrade. Survives a UB DC failure | ✘ same as B1 | ✔ if in Mongolia | — |

---

## 5. C1 measurement protocol (for the PO or a tester in UB)

This protocol satisfies AC 2 and is reused for AC 8. QA should turn it into a script under `tests/` (follow-up item). The commands below are the reference.

### 5.1 Who, where, when
- **People and SIMs:** one tester with SIMs from **≥ 2** operators out of Unitel, MobiCom, Skytel and G-Mobile. Use all four if possible, because §3.2 shows results can differ by operator. Use prepaid data SIMs on the **default APN**.
- **Place:** central UB (for example Sükhbaatar district), outdoors or near a window, ≥ 3 signal bars. Optional extra run in a ger district (for example Chingeltei) for information.
- **Time windows:** at least **one** run of 20 samples per operator per target (AC 2 minimum). **Recommended:** three runs on the same day (08:00–10:00, 12:00–14:00 and 19:00–22:00 Asia/Ulaanbaatar, the evening peak), which gives 60 samples.
- **Device setup (either works):**
  - (a) A **laptop tethered by USB** to the phone. On the phone, Wi-Fi is **off**. On the laptop, Wi-Fi and Ethernet are **off**. macOS or Linux with the built-in `curl`, or Windows 10+ (`curl.exe` is built in; run the script in Git Bash or WSL).
  - (b) **Android + Termux** (F-Droid), `pkg install curl`, Wi-Fi off.
  - An iPhone can only be measured with (a).

### 5.2 Targets (TCP port 443)
| Key | Host | What it stands for |
|---|---|---|
| A1-trial | the public IP of a **Cloud.mn trial VM** ("Cloud 1", hourly), running any listener on 443, e.g. `sudo python3 -m http.server 443` | A1 |
| A1-proxy | `cloud.mn` (103.50.204.94, AS63962) | A1, before the trial exists. Indicative only |
| A2-trial | the public IP of a **Datacom trial VM** (smallest plan, or ask Datacom for a trial) | A2 |
| MN-ref | `mn-uln-as45204.anchors.atlas.ripe.net` (Gemnet, 180.149.98.146; self-signed certificate, so use `-k`) | a Gemnet-side host in UB |
| B1 | `hnd-jp-ping.vultr.com` (Vultr Tokyo) and `jp-tyo-as20473.anchors.atlas.ripe.net` (104.238.161.230, `-k`) | Vultr Tokyo |
| B2 | `speedtest.tokyo2.linode.com` | Akamai/Linode Tokyo |
| B3 | `ec2.ap-east-1.amazonaws.com` (AWS Hong Kong regional endpoint), `hk-kco-as3491.anchors.atlas.ripe.net` (PCCW HK, `-k`) | Hong Kong |
| extra | `sgp-ping.vultr.com`, `sel-kor-ping.vultr.com` | Singapore and Seoul, for the record |
| C | the public IP or host name of the PO's server, if one exists | C |

All the hosts above resolved and answered on 443 from the dev container on 2026-09-30. The anchors answered with self-signed certificates, which is why they need `-k`. The metric is the TCP handshake, so a certificate error does not affect it.

### 5.3 Record once per operator session
Record the date, local time, operator, network type shown on the phone (4G/5G), signal bars, place (district only, no home address), device model, and the **egress ASN**. To get the ASN, run `curl -s https://stat.ripe.net/data/whats-my-ip/data.json`, then look up the IP with `curl -s "https://stat.ripe.net/data/prefix-overview/data.json?resource=<ip>"`. **Store only the ASN, not the IP.**

### 5.4 Run (reference script)
```bash
# c1.sh  usage: OPERATOR=Unitel NET=4G ./c1.sh targets.txt > c1-Unitel-$(date +%F-%H%M).csv
# targets.txt: one "key host" pair per line, e.g. "B1 hnd-jp-ping.vultr.com"
echo "date,time,operator,net,key,host,sample,connect_ms,status"
while read -r key host; do
  curl -k -s --noproxy '*' -o /dev/null --max-time 5 "https://$host/" >/dev/null 2>&1   # warm-up (DNS cache), discarded
  for i in $(seq 1 20); do
    out=$(curl -k -s --noproxy '*' -o /dev/null --max-time 5 -w '%{time_namelookup} %{time_connect}' "https://$host/")
    set -- $out
    if [ "${2:-0}" = "0.000000" ] || [ -z "${2:-}" ]; then st=timeout; ms=; else
      st=ok; ms=$(awk -v n="$1" -v c="$2" 'BEGIN{printf "%.1f",(c-n)*1000}'); fi
    echo "$(date +%F),$(date +%T),$OPERATOR,$NET,$key,$host,$i,$ms,$st"
    sleep 1
  done
done < "$1"
```
- The metric is **`time_connect − time_namelookup`**, the TCP handshake only, without DNS or TLS. Every sample is a **new** TCP connection (a new `curl` process).
- **No proxy or VPN.** `--noproxy '*'` bypasses any `HTTPS_PROXY` setting, but a VPN or a system proxy on the laptop must be switched off. Behind a proxy, `time_connect` measures the hop to the proxy (the dry run of this script in the dev container gave 0.1 ms for Tokyo for exactly this reason).
- **Validity:** a run with > 2 timeouts out of 20 is repeated. Timeouts are reported, not dropped silently.
- **Optional path evidence:** `mtr --tcp --port 443 --report -c 20 <host>` (Linux/macOS) shows whether traffic leaves Mongolia.

### 5.5 Evaluate and report
- For each (operator, target): n, **median**, **p95** (nearest rank), and the number of timeouts.
- **Pass (C1):** median < 50 ms on **each of ≥ 2 operators** for the candidate. Report the p95 too.
- Send the CSV files and the §5.3 metadata to the orchestrator. The architect copies the medians and p95 into ADR-0005 (AC 2). QA keeps the raw files under `docs/qa/`.

### 5.6 Download check on each trial VM (C9, same day)
On each trial VM run `curl -sS -o /dev/null -w '%{http_code} %{time_total}\n' -r 0-1023 <url>` for every URL in the `backend/README.md` downloads table. That table covers Geofabrik `mongolia-latest`, osmdata.openstreetmap.de, naciscdn.org, r2-public.protomaps.com, qrank.toolforge.org, the GraphHopper Photon dump, GitHub/codeload/ghcr.io, mcr.microsoft.com, Maven Central and repo.osgeo.org. Also run `docker pull` for the pinned images. Record each HTTP status. Any failure is a C9 finding for that candidate.

### 5.7 Trial cost and timing (E)
- Cloud.mn "Cloud 1" (1 vCPU, 1 GB, 15 GB, IP) costs about 45,100 MNT/month billed hourly, so **about 3,000 MNT for 2 days**.
- The smallest Datacom VPS is 132,000 MNT (≈ USD 37) for a month and **non-refundable**, unless Datacom offers a trial.
- Latency does not depend on VM size, so the smallest VMs are enough.
- Total trial budget: **≤ USD 50 one-off**. It can be done within 5 working days of the PO go-ahead.

---

## 6. Recommended staging specification (AC 3, AC 4)
| Item | Value | Reason |
|---|---|---|
| vCPU | **4** (8 if the chosen provider's 16 GB plan has 8) | NAV-001 cold build 462 s and source-switch rebuild 336 s on 4 CPU, well inside AC 15's 30 min |
| RAM | **16 GB** | Cold build peak 5.5 GB + services < 1 GB ≈ 6.5 GB = 41 % of 16 GB (AC 16 limit 70 %). Leaves room for NAV-006 building beside the serving copy. 8 GB would be 81 % and fails |
| Disk | **100 GB SSD** (NVMe if available at the same price tier) | `data/` 2.9 GB today. A second blue/green copy plus a later own Nominatim import (tens of GB) must still leave ≥ 50 GB free (AC 16) |
| Network | 1 public IPv4, inbound 80/443 plus SSH. IPv6 if offered | AC 6, 7 and 12 |
| Location | **Ulaanbaatar**, at the provider that passes C1 (A1 or A2). Fallback: Vultr Tokyo (nrt) | §3 |
| OS | Ubuntu 24.04 LTS with unattended security upgrades, Docker Engine and the Compose plugin | AC 12. Same toolchain as the dev container |
| Ops VM (optional, recommended) | 1 vCPU / 1 GB at a **different provider**, running Uptime Kuma (MIT) and acting as the encrypted backup target for configuration | Meets AC 17 ("outside the host's network") and AC 19 ("another provider, region or site") with one USD 5 item. It only probes `/health` and stores config (no personal data), so its location is not a residency issue (E, confirm with counsel) |
| **Estimated staging total** | **A1: about USD 105–120/month**, including provider backup and the ops VM. **A2: about USD 110** plus unknown extras. **B1: about USD 101** | Inside the USD 50–150 ceiling |

**Provisioning approach (guidance for the backend story, see ADR-0005):** keep infrastructure-as-code **provider-agnostic**: `cloud-init` user-data, an idempotent shell or Ansible playbook, and the existing `backend/compose.yaml`. The provider console step is only "create a VM with this user-data". Then switching A1 → A2 → B1 is a re-run, not a rewrite. Terraform stays optional, because provider APIs for A1 and A2 are unknown.

---

## 7. Production path (proposal, to confirm in a production story after staging)
- **Same class as staging (in Mongolia)**, because of C1, C3 and C10. Use **two VMs** for blue/green or warm standby, NAV-006.
- **Cover both upstream sides** (MobiCom and Gemnet). Two options:
  - one VM at a MobiCom-side provider (A1) and one at a Gemnet-side provider (A2), with DNS failover or client-side fallback
  - a provider that is multi-homed or at the MIX/MISPA IXPs; the National Data Center runs MIX, so ask for a quote
  
  The staging C1 data per operator decides which.
- **E:** about USD 200–260/month (2 × 16 GB VMs, backups, ops VM). A CDN or object storage for PMTiles only if measurements show the need (C5, R9).
- An international region only as a **cold DR copy**, and only if legal counsel allows it.
- On-call, SLA and auth are outside this spike (NAV-008 Out of scope).

---

## 8. PO decisions still needed (AC 3)
Already decided on 2026-09-30: priority must/Phase 0, the < 50 ms decision rule, the USD 50–150 budget, a subdomain of the company domain, a named PO-side operator, an unlisted URL with rate limits, and legal review before tester traffic goes abroad.

| # | Decision | Options | Architect recommendation |
|---|---|---|---|
| OQ1-a | Go ahead with the **latency trial** (§5) at A1 and A2 | (a) both, ≤ USD 50 one-off; (b) A1 only (cheapest, hourly); (c) skip the trial and pick B1 now | **(a)**. A1 and A2 probably sit on different upstreams (§3.2), so testing both doubles the chance of passing on ≥ 2 operators |
| OQ1-b | Who runs §5 in UB, with which operator SIMs | a named tester with ≥ 2 SIMs (4 preferred) | Nominate by trial day 3 |
| OQ1-c | Fallback if no candidate in Mongolia passes on ≥ 2 operators within 10 working days | (a) B1 Vultr Tokyo (Q, USD 96); (b) get an Alibaba/Tencent HK quote first (possibly about 5–10 ms better, price U); (c) keep testing in Mongolia (A3 quotes, NDC/MIX) and delay phone testing | **(a)** with the C1 exception recorded (AC 5) and the legal gate. Run (c) in parallel for production |
| OQ1-d | Option C: do the PO's own servers exist? Location, spare CPU/RAM/disk, public IP and 443, administrator, upstream ISP. Also: own map data or Valhalla endpoints? | answer or "none" | If they exist in UB, add them to the §5 trial as target C at no cost |
| OQ2 | Who signs and pays the provider in MNT (A1/A2) or by international card (B1) | company account under the named operator | Company account. Needed before trial day 1 |
| OQ5 | Staging data coverage | (a) Geofabrik `mongolia-latest`; (b) BBBike UB | **(a)**. AC 10 and AC 15 need it |
| OQ8-scope | Legal review scope | counsel reviews (i) coordinates processed abroad in memory (B only); (ii) the ops VM abroad holding config and `/health` probes only | Needed **only if** the B fallback is used. Ask about (ii) in any case (cheap) |

---

## 9. Risks
| Risk | Effect | Mitigation |
|---|---|---|
| Domestic peering (MobiCom and Gemnet) makes an in-country host slow for some operators | A1 or A2 fails C1 on some operators | Trial both sides. For production, cover both upstreams (§7) |
| Indirect data misleads: ICMP from a fixed network ≠ TCP from mobile | Wrong ranking | Only §5 counts for C1 (AC 2). This spike ranks and does not decide |
| Local provider details (SLA, power, backups, egress) are unknown | Surprises in operation | Written questions to A1/A2 during the trial (C4 column) |
| Procurement: Datacom is non-refundable and MNT invoicing may need a contract | Delay | A1 is hourly and self-service. B1 is ready the same day as the fallback |
| Winter power in UB | Staging outages | Ask A1/A2 about UPS/generator (C4). Staging tolerates outages (R4) |
| Cloud.mn has a single upstream (MobiCom) | An upstream outage takes staging down | Accepted for staging. Production covers both upstreams |

---

## 10. Follow-up items for triage
1. **Backend (feature, NAV-008 AC 6–23):** provider-agnostic infra-as-code (cloud-init + script/Ansible + compose), TLS with ACME, firewall and SSH hardening, a gateway rate limit (429 JSON), privacy-safe logs on any TLS terminator, external uptime checks and host metrics, encrypted off-host config backup, restore runbook, and new `.env.example` keys.
2. **QA (spike/measurement):** turn §5 into `tests/latency/c1.sh` plus an evaluator, run the C1 trial on ≥ 2 operators against the A1, A2, B and C targets, and run the §5.6 download check on the trial VMs.
3. **QA (feature):** verification plan for NAV-008 AC 6–22 on staging (TLS check, port scan, CORS, rate-limit load test, log inspection, rebuild, stats, alert drill, restore drill).
4. **Architect (change):** add the `429 RateLimited` response to `postRoute`/`search` and the staging entry under `servers` in `openapi.yaml` (AC 13, AC 23), once the rate-limit values and hostname are known.
5. **Architect (ADR):** move ADR-0005 to `accepted`, or record the PO's alternative, once the trial results and the PO decision are in (AC 5).
6. **BA (feature, later):** production hosting story (two VMs, both upstreams, DR, SLA, on-call), after staging data exists.

---

## Sources
- RIPE Atlas probes in MN: https://atlas.ripe.net/api/v2/probes/?country_code=MN&status=1 (2026-09-30)
- RIPE Atlas measurement results (msm IDs in §3.1 and §3.2): `https://atlas.ripe.net/api/v2/measurements/<msm>/results/?probe_ids=6723` (2026-09-30)
- RIPEstat prefix overview and ASN neighbours: https://stat.ripe.net/data/asn-neighbours/data.json?resource=AS63962 (2026-09-30)
- PeeringDB IXPs in Mongolia: https://www.peeringdb.com/api/ix?country=MN, members `netixlan?ix_id=607` and `2407` (2026-09-30)
- Cloud.mn: https://cloud.mn/, price API https://api.cloud.mn/api/v1/client/public/calculator (2026-09-30)
- Datacom VPS: https://datacom.mn/hosting/vps (2026-09-30)
- Vultr plans and regions: https://api.vultr.com/v2/plans, https://api.vultr.com/v2/regions. Backups +20 %: https://docs.vultr.com/support/platform/billing/how-much-does-it-cost-to-enable-automatic-backups (2026-09-30)
- Akamai/Linode types and regions: https://api.linode.com/v4/linode/types, https://api.linode.com/v4/regions (2026-09-30)
- AWS EC2 on-demand prices (public pricing JSON behind aws.amazon.com/ec2/pricing): `https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20(Hong%20Kong)/Linux/index.json` (2026-09-30)
- DigitalOcean droplet pricing: https://www.digitalocean.com/pricing/droplets (2026-09-30)
- DLA Piper, Data protection laws of the world, Mongolia (last modified 2026-03-20): https://www.dlapiperdataprotection.com/index.html?t=law&c=MN
- Lehman Law, Personal data protection in Mongolia (2023-11-10): https://lehmanlaw.mn/blog/personal-data-protection-in-mongolia/
- Pandectes, Mongolia's data privacy law (2025-07-24): https://pandectes.io/blog/mongolias-data-privacy-law-key-features-and-implications-explained/
- SpeedOf.Me, Mongolia H1 2026: https://speedof.me/internet-speed/mongolia
- Mobile vs fixed latency (Opensignal via secondary source, Jan 2024): https://gigago.com/mobile-internet-mongolia/
- Wise USD/MNT history: https://wise.com/us/currency-converter/usd-to-mnt-rate/history
