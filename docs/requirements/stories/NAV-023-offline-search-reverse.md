---
id: NAV-023
title: Offline search and reverse geocoding (server-built SQLite FTS5 + R*Tree search DB, Android on-device engine on the ADR-0012 query plan), online first with the 3.0 s fallback
phase: 1
priority: must          # P1 / standard, feature, PO-confirmed 2026-10-04 (D191); BA MoSCoW mapping P1 → must
size: L                 # search DB builder spec and normalisation vectors (backend), Android engine, fallback, quality gate, held-out report, offline indicator
needs_design: true      # "offline" indicator on search results and, if confirmed, on the coordinate card (D201)
needs_backend: true     # search DB builder in the NAV-020 pipeline (schema, normalisation, test vectors)
needs_mobile: true      # Android on-device search and reverse
status: ready           # 2026-10-04. Delivery slot 4, after NAV-022 (D197). Non-blocking open questions only
---

# NAV-023: Offline search and reverse geocoding

## Story
As an **intercity / countryside driver**, I want **to find a soum centre, a petrol station or a street by typing in Cyrillic or Latin letters, and to see what is at a point on the map, even when my phone has no internet**, so that **I can set a destination and know where I am anywhere in Mongolia without signal**.

Secondary personas:
- **Taxi / delivery driver:** a typed address or place still gives results in a dead zone, from the same search screen.
- **Tourist (English UI):** Latin spellings such as "Sukhbaatar", "Zaisan" or "Erdenet" find the same places offline as online.
- **Pedestrian / UB commuter:** online search is unchanged when the network is fine (online first, D163).

## Context
- **Request and decisions.** Offline is a launch requirement for the real Android app ([D156–D159](../decisions.md)): search and reverse geocoding must work without internet ([D157](../decisions.md)). Spike §9 item 2; [ADR-0017](../../architecture/adr/0017-offline-mongolia-pack-android.md) §3 (search on the device) and §5 (fallback); triage 2026-10-04 row "§9 item 2": feature, P1 / standard ([D191](../decisions.md)).
- **PO decisions that apply:**
  - [D163](../decisions.md) online first with the on-device fallback; [D199](../decisions.md) fallback **≤ 3.0 s** after the online request was sent, or **at once** without a validated network
  - [D168](../decisions.md) **launch gate: ≥ 90 % of the applicable NAV-003 tier A + B golden rows offline**; category queries such as «Галт тэрэгний буудал» (B13) may fail; the story fixes which rows count (AC 17)
  - [D198](../decisions.md) a **held-out query set of ≥ 30 queries including countryside soum and bag names** is **measured and reported first**; the PO sets its pass mark afterwards; **not a launch gate yet**
  - [D201](../decisions.md) results from the pack show a small **"offline" indicator** (glossary OF24 / OF25; UX designs it)
  - [D197](../decisions.md) delivery slot 4, after NAV-022 (spike R11: map and routing may ship before search polish)
- **What exists today.** Online search and reverse on Android: NAV-011 section A (ADR-0012 query plan: coordinate parser, ADR-0006 abbreviation expansion, Latin and vowel variants, merge) and section B (reverse on the coordinate card, «Ойролцоох газар»). Without network NAV-011 shows «Интернэт холболт алга» with 0 requests. The spike's untuned SQLite prototype passed **29 of 30** applicable tier A + B rows on name and distance (B13 failed); type-label conditions were not evaluated.
- **Boundary with NAV-020 (triage 2026-10-04).** **This story owns** the search DB schema, the normalisation rules (section A) and the shared test vectors; NAV-020 runs the pinned builder in the pack-publish step and publishes `search.sqlite`. A schema change increments `search_schema` (manifest `format.search_schema`), and the app only installs schemas it knows (NAV-022 AC 16).
- **Not changed by this story:** NAV-003 (web), NAV-011 AC. The NAV-011 offline states are aligned by change 7b after this story ([D194](../decisions.md)). **Without an installed search file, NAV-011 behaviour and tests stay exactly as they are** (AC 26).
- **Coordination (binding):** `mobile/android` is shared with other running stories: read first, small targeted edits, never revert. Builder work runs in the NAV-020 test setup (separate Compose project and port); never write to or restart the shared dev stack at `http://127.0.0.1:8080`.

### Terms used in this story
| Term | Meaning here |
|---|---|
| **Search file** | The installed `search.sqlite` (NAV-022) |
| **Online answer** | A `search` or `reverse` response from the gateway (Photon behind it) handled by NAV-011 rules |
| **On-device answer** | The result of the on-device engine on the search file |
| **Golden set** | NAV-003 "Golden query set" tiers A and B (`tests/e2e/nav003/fixtures/golden-set.json`), with its bias points P1–P6, X1 and top-N conditions |
| **Same slot** | The NAV-006 slot the search file was cut from; online comparisons use Photon on that slot (the NAV-020 test gateway) |
| **Fold** | Lower case, then «ү» → «у», «ө» → «о», «ё» → «е» |
| **Skeleton** | The Latin key of section A (AC 2) |

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **New rows (glossary section 2.7, `needs native review`):** OF24 «Офлайн» (indicator) and OF25 «Офлайн газрын зургаас» (its accessible name). Everything else is reused from NAV-003 / NAV-011: «Газар, хаяг хайх», «Хайлтын илэрц», «{count} илэрц олдлоо», «Илэрц олдсонгүй», «Хайлт түр ажиллахгүй байна», «Түр хүлээгээд дахин оролдоно уу», «Сонгосон цэг», «Ойролцоох газар», the type labels of glossary section 4.1, «Интернэт холболт алга», «Дахин оролдох», «Алдаа гарлаа».

## Acceptance criteria

### A. Search DB builder: schema and normalisation (backend; shared test vectors)
1. **Given** the Photon dump of a slot, **When** the builder runs, **Then** `search.sqlite` (schema version `search_schema`, starting at 1) contains:
   - a `place` table with, per object: the display name by the label rule (`name:mn` → `name` → `name:en`), `name:en` when present, OSM type and ID, `osm_key`, `osm_value` and Photon `type` (enough to apply the NAV-003 / NAV-011 type-label rules unchanged), the context fields (street, suburb, city, county, state), centroid, extent when present, and Photon `importance`
   - an FTS5 index over: the folded names (every name variant), the skeleton of every name variant, joined-word keys (each pair of adjacent words of a name also indexed without the space, for example «Энх тайваны» → «энхтайваны»), and the context
   - a trigram table, a vocabulary for edit-distance expansion, and an R*Tree over centroids for reverse geocoding
   - **no** Traditional Mongolian script (U+1800–U+18AF) in any indexed or displayed column (NAV-003 AC 18)
2. **Given** a name or a query, **When** its skeleton is computed, **Then** both the builder and the Android engine apply exactly these steps:
   1. fold (Terms) and remove Latin diacritics («ü» → «u», «ö» → «o»)
   2. map Cyrillic letters: а a, б b, в v, г g, д d, е e, ж j, з z, и i, й i, к k, л l, м m, н n, о o, п p, р r, с s, т t, у u, ф f, х h, ц c, ч ch, ш sh, щ sh, ы i, э e, ю yu, я ya; «ь» and «ъ» are dropped (ү, ө, ё are already folded)
   3. on the Latin result: kh → h, ts → c, zh → j, then y → i
   4. collapse every run of the same letter to one letter, and drop characters other than a–z, 0–9 and spaces
3. **Given** the shared test-vector file (QA-owned, in `tests/`; path in the QA test plan), **When** it is inspected, **Then** both the builder's tests and the Android engine's tests read the **same** file, and it contains at least these groups, each of which must produce one identical skeleton:
   - «Сүхбаатар», «Сухбаатар», "Sukhbaatar", "Suhbaatar", "Sükhbaatar", "SUKHBAATAR" → `suhbatar`
   - «Зайсан», "Zaisan", "Zaysan" → `zaisan`
   - «Баянзүрх», "Bayanzurkh", "Bayanzurh" → `baianzurh`
   - «Чингэлтэй», "Chingeltei" → `chingeltei`
   - «Их дэлгүүр», "Ikh delguur" → `ih delgur`
   - «Эрдэнэт», "Erdenet" → `erdenet`
   - «Гандан», "Gandan" → `gandan`
   
   A change to the AC 2 rules or to these vectors is a change to this story (through the BA), and increments `search_schema` when the stored keys change.
4. **Given** the Mongolia dump of a slot, **When** the builder runs on staging-like resources, **Then** it finishes in **≤ 5 minutes**, `search.sqlite` is **≤ 30 MB** raw and **≤ 10 MB** gzip (*BA proposal*; prototype 20.4 MB / 7.0 MB), it passes `PRAGMA quick_check`, and the NAV-020 AC 12 self-test query returns ≥ 1 row.
5. **Given** the builder, **When** it runs twice on the same dump, **Then** both outputs have the same row counts per table and give identical results for the golden set (AC 17 harness), so a rebuild never changes search behaviour by itself.

### B. Android on-device engine
6. **Given** an installed search file, **When** an on-device search runs, **Then** it uses the **ADR-0012 query plan** unchanged (coordinate parser first, ADR-0006 abbreviation expansion such as «БЗД» → «Баянзүрх дүүрэг», Latin and vowel variants), matches the folded and skeleton columns with a prefix match on the last token, expands tokens by edit distance (1 for 5–8 letters, 2 for ≥ 9 letters) when there are 0 hits, then falls back to trigrams, and ranks by exact / prefix name match, importance, distance to the bias point and text relevance. The SQLite library is `androidx.sqlite:sqlite-bundled` (FTS5, R*Tree), in the main process.
7. **Given** an on-device answer, **When** it reaches the UI, **Then** it uses the same result model as online answers, so the results list, the type labels (NAV-011 / NAV-003 rules on `osm_key` / `osm_value` / `type`), the place card, «Маршрут гаргах» and the number of options shown are the same as for an online answer.
8. **Given** typed coordinates (NAV-011 AC 7 / 7a), **When** they are entered with or without a search file, **Then** behaviour is unchanged («Сонгосон цэг», **0** `search` requests and **0** search-file queries).
9. **Given** the mid-range and low-end benchmark phones of NAV-021, **When** the golden set queries run on the device, **Then** from query dispatch to the results list ready, p95 is **≤ 150 ms** on the mid-range and **≤ 400 ms** on the low-end phone, and an on-device reverse takes **≤ 100 ms** p95 on both (*BA proposal*). Typing keeps the NAV-011 debounce; results of an older query never replace a newer one.
10. **Given** a new search file is installed by NAV-022, **When** the next query runs, **Then** it uses the new file; a query already running finishes on the old one (NAV-022 AC 19).

### C. Fallback rule (D163, D199), the same as NAV-021
11. **Given** an installed search file and **no** validated network, **When** the user searches or opens a coordinate card, **Then** the on-device engine answers at once and **0** `search` / `reverse` requests are sent; «Интернэт холболт алга» is **not** shown.
12. **Given** an installed search file and a validated network, **When** a `search` or `reverse` request is sent, **Then** it is cancelled and answered on the device in the same attempt when, before response headers arrive, the connection fails, the gateway answers 502 / 503 / 504 or 429, **or no headers arrive within 3.0 s** of sending. Measured as NAV-021 AC 9: **3.0 s** in JVM tests with a fake clock; **≤ 3.2 s** on a benchmark phone.
13. **Given** headers arrived within 3.0 s, **When** the answer is handled, **Then** NAV-011 rules apply (body timeout 8 s for search). An authoritative online answer, **including a 200 with 0 results** («Илэрц олдсонгүй») and a 400, is shown as today with **0** on-device queries.
14. **Given** a fallback caused by a timeout or a connection failure, **When** further searches or reverses come within **60 s**, **Then** they go straight to the on-device engine with **0** online requests, until 60 s have passed or a **newly** validated network is reported. While typing on a dead connection, each settled query therefore answers within the AC 9 time, not after 3 s.
15. **Given** the gateway answered 429 with `Retry-After: N`, **When** queries come during the next N seconds, **Then** **0** online requests are sent, the on-device engine answers them, and «Түр хүлээгээд дахин оролдоно уу» is **not** shown while on-device answers are available.
16. **Given** an on-device answer, **When** the bias is applied, **Then** it uses the same bias point as an online request would (D30 / D174 rule: the shown position rounded to 3 decimals, only when my location is on and the camera follows; otherwise the NAV-011 rule).

### D. Quality: the launch gate (D168) and the held-out report (D198)
17. **Given** the golden set and a search file from the same slot, **When** QA runs the offline gate with the Android engine code (a JVM harness running the same Kotlin engine on the published file, plus a device spot check of at least 10 rows), **Then**:
    - **applicable rows** are A1–A14, A16 and B1–B15 (30 rows); A15 (coordinates) is excluded because it never reaches the search file (AC 8)
    - **type-label conditions are evaluated** (A9–A13, B9–B11, B14, B15), as are names, distances and top-N
    - a row that **also fails online** on the same slot is a data gap: it is recorded with both top-5 lists and **not counted**
    - the gate **passes** when `passed × 10 ≥ counted × 9` (integer arithmetic: with 30 counted rows, 27 must pass)
    - QA reports the counted rows, the passed rows and every failing row with its offline and online top 5. B13 («Галт тэрэгний буудал») may fail
18. **Given** the held-out set, **When** it is created, **Then** QA writes it **after** the builder and the ranking are frozen for the measurement, the builder and Kotlin ranking are **not tuned on it** before the first report, and it has **≥ 30** queries, including: **≥ 10** soum centre names, **≥ 5** bag names, **≥ 5** aimag centres outside Ulaanbaatar; across these, **≥ 8** Latin spellings and **≥ 4** Russian-layout spellings (у for ү, о for ө). Bias: the aimag centre of the place, or none.
19. **Given** the held-out set, **When** it is measured, **Then** a row **passes** when the OSM object that online Photon on the same slot ranks first (same query, same bias) appears in the offline top **5**; rows where online returns 0 results are excluded and listed. The report gives the offline pass rate, the online-versus-offline comparison and every failing row. **It does not gate this story** (D198); the PO sets a pass mark after this first report, and any later gate is a change to this story.
20. **Given** points P1–P6, **When** reverse runs offline and online on the same slot, **Then** for each point the offline result is the same OSM object as online, or a named object within **50 m** of the online result's point; at P1 it is «Сүхбаатарын хөшөө» or another object within 50 m of it (spike measurement). The result shows under «Ойролцоох газар» exactly as an online result.

### E. "Offline" indicator on results (D201)
21. **Given** a results list from the on-device engine, **When** it renders, **Then** the list shows the indicator OF24 «Офлайн» once (as designed by UX) with the accessible name OF25 «Офлайн газрын зургаас», and the screen reader announcement after results render is «{count} илэрц олдлоо» followed by OF25. A list from the gateway shows **no** indicator. «Илэрц олдсонгүй» from the on-device engine also shows the indicator.
22. **Given** a place card opened from an on-device result, **When** it shows, **Then** it carries OF24 as well.
23. **Given** a reverse answer from the on-device engine on the coordinate card, **When** «Ойролцоох газар» shows, **Then** OF24 is shown next to it. *(D208.)*
24. **Given** the indicator, **When** it renders, **Then** it never covers the search field, a result name, «Маршрут гаргах» or the OSM attribution, and meets contrast ≥ **4.5:1** in both themes.

### F. States, regression and failure
25. **Given** an installed search file that fails to open or answer (damaged after install), **When** a search falls back to it, **Then** «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» is shown (NAV-011 state), **0** crashes occur, and the failure is logged without the query text or coordinates.
26. **Given** no installed search file, **When** the existing Android suite and the NAV-011 tests run (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`), **Then** all pass unchanged, and offline search shows «Интернэт холболт алга» with 0 requests as today.
27. **Given** the web demo (NAV-003), **When** this story ships, **Then** nothing changes on the web (the offline engine is Android only).

### G. Privacy and strings
28. **Given** a session of online, fallback and offline searches and reverses, **When** Logcat, files and preferences are scanned, **Then** **0** query texts and **0** coordinates (regex `-?\d{1,3}\.\d{4,}`) are found in logs or storage, and **0** `search` / `reverse` requests are sent while answers come from the device without network.
29. **Given** the Android resources, **When** the NAV-005 AC 61 checks run, **Then** they pass with OF24 and OF25 matching the glossary exactly.

### H. Verification
30. **Given** the deliverables, **When** QA reports, **Then** the report contains: the builder tests on the shared vectors (AC 3), the Android engine tests on the same vectors, the AC 17 gate result, the AC 19 held-out report, the AC 20 reverse comparison, the AC 9 device timings, and JVM tests with a fake clock and mock server for section C. Device checks that could not run are listed as **not verified** with the reason.

## Edge cases
- **Cyrillic / Latin transliteration:** "Sukhbaatar", "Suhbaatar", "Sükhbaatar", «Сухбаатар» meet in one skeleton (AC 2–3); tier B rows B1–B12 measure it (AC 17).
- **Russian-layout typing** (у for ү, о for ө): folding (Terms); held-out rows (AC 18).
- **Typos:** edit-distance expansion (AC 6); B4 «Сүхбатар» measures it.
- **Category queries** («Галт тэрэгний буудал», «ШТС»): may fail offline (D168); a small synonym table may be added but is not gated. «ШТС» as a name abbreviation is matched only if it is in an object's name.
- **Abbreviations** («БЗД», «худ»): ADR-0006 expansion (AC 6); A9–A11.
- **No result found:** «Илэрц олдсонгүй» with the indicator (AC 21).
- **No network / dead zone / airplane mode:** AC 11.
- **Slow network:** AC 12, AC 14.
- **Rate limit (429):** AC 15.
- **GPS loss:** the bias falls back as NAV-011 (AC 16); search still works.
- **Countryside names** (soum, bag): low OSM name coverage is a data risk (R2); the held-out report shows it (AC 19).
- **Search data older than online:** up to about 7 days (weekly, D166), and older when the Photon dump was not updated (NAV-006 AC 8); the indicator and the NAV-022 data dates show it.
- **Ger-district addresses** (khoroo, plots): as poor as online (NAV-003 R4); B14, B15 measure khoroo search.
- **Winter:** not applicable.
- **Off-route:** not applicable (NAV-021).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Offline ranking drifts from Photon** (our own Kotlin ranking) | Different or worse result order offline | D168 gate (AC 17), held-out report (AC 19), online first (D163). Mobile, QA |
| R2 | **OSM name coverage** (`name`, `name:mn`, `name:en`) and countryside soum and bag names barely covered by the golden set | Countryside searches fail offline and online | Held-out set (D198); mapping tasks through triage lane `osm-data` (human mappers only). BA, PO |
| R3 | **Category queries fail** (no category index beyond a small synonym table) | «Галт тэрэгний буудал»-type searches return nothing offline | PO-accepted (D168); online first |
| R4 | **Search dump age:** the GraphHopper Photon dump is weekly, so offline search can be up to about two weeks behind the map in the worst case | New places missing offline | Data dates (NAV-022 AC 39); own Nominatim draft row (D128) |
| R5 | **Normalisation drift** between builder and app | Queries miss their own index | One shared vector file (AC 3), `search_schema` versioning |
| R6 | **Low-end phone latency** for fuzzy expansion | Slow typing feedback offline | AC 9 thresholds; precomputed vocabulary index |

## Out of scope
- The pack download and update (NAV-022), publishing the file (NAV-020), routing (NAV-021).
- Recent searches, saved places, a category browser.
- Offline search on the web demo or iOS (D162).
- Our own Nominatim import (draft row, D128).
- A held-out launch gate (D198: set later by the PO, then a change to this story).
- The NAV-011 text changes for offline states (change 7b, after this story, D194).

## Open questions
Non-blocking; each has a working assumption in the AC.
1. **Closed 2026-10-04 by [D208](../decisions.md): option (a).** OF24 is shown next to «Ойролцоох газар» when the reverse answer came from the device (AC 23).
2. **Counting rule for the D168 gate** (AC 17: a row that also fails online on the same slot is a data gap and is not counted, as NAV-003 does for tier B controls). Options: (a) as written; (b) count every applicable row. *Recommendation: (a).* The gate measures the offline engine, not OSM gaps that online search shares. D168 left the definition of "applicable" to the story.
3. **Starting values marked *BA proposal*** (AC 4 build time and size, AC 9 latency). *Recommendation:* accept as acceptance values; change through the BA after the first device measurement if needed.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–AC5 | — | — (file published by NAV-020; manifest `format.search_schema`) | search DB builder (backend, TBD) | builder tests on shared vectors (QA fixture in `tests/`) | ADR-0017 §3; NAV-020 AC 6 |
| AC6–AC10 | NAV-011 screen spec (unchanged list and card) | — | on-device search engine (mobile, TBD) | JVM tests; device timing | ADR-0012; NAV-021 benchmark phones |
| AC11–AC16 | — | `search`, `reverse` | fallback policy (mobile) | JVM tests with fake clock and mock server | D163, D199; D30, D174 |
| AC17–AC20 | — | `search`, `reverse` (online comparison on the same slot) | — | offline gate harness, held-out report, reverse comparison (QA) | D168, D198; Open question 2 |
| AC21–AC24 | UX: offline indicator spec (TBD) | — | indicator on list, card (mobile) | Robolectric / TalkBack checks | D201, D208; glossary OF24, OF25 |
| AC25–AC27 | NAV-011 states | `search`, `reverse` | error handling | regression suite | change 7b (D194) |
| AC28–AC29 | — | `search`, `reverse` | logging, resources | log scan, glossary check | NAV-005 AC 61 |
| AC30 | — | — | — | QA report | |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | Offline spike follow-up §9 item 2 (triage log 2026-10-04; PO "all as recommended", [D191](../decisions.md), [D197](../decisions.md), [D198](../decisions.md), [D199](../decisions.md), [D201](../decisions.md)) | Story written: 30 AC in sections A–H (builder schema and normalisation with shared vectors, Android engine, fallback rule, D168 gate and D198 held-out report, offline indicator, states and regression, privacy and strings, verification). Status `ready` | Launch scope (D157, D159); last of the four offline items (D197) |
| 2026-10-04 | Open question 1 decided by the orchestrator on the PO's standing instruction ([D208](../decisions.md)) | Closed with option (a): OF24 also on reverse results on the coordinate card (AC 23 now cites D208; no AC meaning changed). Open questions 2 and 3 stay open. AC 9 uses the NAV-021 benchmark phones, so its low-end figure is **not verified** until the PO supplies a low-end phone (D204) | Recommended option, per the PO's standing instruction |
