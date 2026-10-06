# ADR-0017: Offline Mongolia pack for Android. A versioned three-file pack (PMTiles monthly; the server's Valhalla graph and an SQLite FTS5 search DB weekly) cut from verified NAV-006 slots; online first with on-device fallback; on-device Valhalla (`valhalla-mobile`) in a separate process behind `RouteRequester`; offline search on `sqlite-bundled`

- **Status:** accepted (PO 2026-10-04). The PO answered "All as recommended" to the eight questions that followed the spike [offline-android](../spikes/offline-android.md) (§10 there, plus the per-file cadence question). The answers are listed under Context and applied in Decision. **Amendment A1** (2026-10-04, at the end) adds the measured engine pins and the NAV-020 publication rules. Amendments A2 (NAV-021 review), A3 (2026-10-05, NAV-022 review: plain WorkManager download jobs, reboot resume at next app start, static-host client rules) and A4 (2026-10-05, NAV-023 review: builder v2 stays at `search_schema` 1, one reference ranking, vector file location, bundled SQLite version) follow it.
- **Date:** 2026-10-04 (proposed and accepted the same day)
- **Stories:** NAV-020 (pack build and publication), NAV-021 (on-device routing), NAV-022 (download, update, map from the pack), NAV-023 (offline search), written 2026-10-04 after triage. Task breakdowns: `docs/architecture/tasks/NAV-02*.md`. Answers backlog owed decision 3 (PO, 2026-10-04: offline at launch = yes). Touches NAV-005, NAV-006, NAV-011, NAV-012. Amends ADR-0001 Consequences (dated note there: offline is a launch requirement).
- **Contract:** `openapi.yaml` **0.6.0** adds `getOfflinePackManifest` and `getOfflinePackFile` (§6). Nothing else in the contract changes.

## Context
The PO decided on 2026-10-04 (triage row "Offline use") that the **real** Android app must work without internet for map display, guidance (already works), **new route and reroute**, and **search and reverse geocoding**. The coverage is all of Mongolia, it is needed **at launch**, the data is **downloaded in the app** (not bundled), the server keeps the daily NAV-006 rebuild, and Android comes first (iOS later).

**PO answers after the spike (2026-10-04, "All as recommended").** These are product decisions. This ADR applies them and does not reopen them.

| # | Question | PO answer |
|---|---|---|
| A1 | Source order with internet **and** an installed pack | Map from the pack. Search, route preview and **reroute online first**, with on-device fallback when there is no connection, **within about 3 s** |
| A2 | Download over mobile data | Wi-Fi by default. Mobile data only after a per-download confirmation that shows the size |
| A3 | Weekly update | Automatic in the background on Wi-Fi with battery not low. If the installed routing and search data are **older than 14 days** and there is no Wi-Fi, offer a small mobile-data update of **routing + search only** (about 33 MB gzip) |
| A4 | Cadence per file | Map tiles **monthly**; routing graph and search index **weekly**. This amends the earlier PO answer 6 ("offline data refreshed weekly") for the map file only |
| A5 | Hosting and updates | Full-file updates at launch; delta updates measured later (spike §9 item 9) |
| A6 | Offline search quality bar | At least **90 %** of the applicable NAV-003 tier A + B golden rows. Category queries such as «Галт тэрэгний буудал» may fail offline |
| A7 | First-launch prompt | Offer «Download Mongolia map» once on first launch when on Wi-Fi, and always in Settings (the Mongolian wording comes from the glossary; the BA adds the term) |
| A8 | Map detail | z0–14, the same as online (about 118 MB on the phone) |

**Reviewer and skeptic concerns** carried into this design (not PO decisions):
- The parity gate must test the **shipped** `valhalla-mobile` library, not the upstream Docker image (§4).
- The on-device engine runs in a **separate process**, so a native crash cannot kill guidance (§2).
- The schedule is realistically **6–9 calendar weeks** (Consequences).
- Offline search needs a **held-out query set**, including countryside soum and bag names, so the 90 % bar does not overfit the golden set (§3).
- Two inconsistencies in the spike are fixed: the effort is **45–67** person-days (the §8 table sum; the "45–65" in §1 and R11 was a typo), and the spike had **seven** product questions, not "four". The PO answered eight, because the per-file cadence question (A4) was added.

Constraints:
- Use upstream components unchanged (ADR-0001), with permissive licences, and Docker Compose on the server.
- The app already owns its route transport: `RouteRequester` → classify → keyed text rewrite → Ferrostar `createOsrmResponseParser(6u)` (ADR-0009 §2, §3.1). `FerrostarCore` is not used.
- MapLibre Native 13.6.1 reads `pmtiles://file://` but not `pmtiles://asset://` (ADR-0016 F1, F2).
- NAV-006 builds immutable, verified slots and switches them with a pointer rename (ADR-0014).
- Phones without Google Play services are supported (D62). Android `minSdk` is 26.

The spike measured on copies of the dev-stack artefacts (spike §2):
- PMTiles z0–14: 117.5 MB (86.4 MB gzip)
- Valhalla tar: 63.7 MB (25.9 MB gzip)
- prototype search DB: 20.4 MB (7.0 MB gzip)
- total about 202 MB on the phone, about 119 MB gzip for a full download; routing + search alone about 33 MB gzip
- `valhalla-mobile` 0.6.3 (Valhalla 3.6.3) on the server's 3.9.0 graph: **12/12 routes identical** to the server. On a graph rebuilt with 3.6.3: 7/12 identical. (Measured with the upstream 3.6.3 Docker image as a stand-in for the library; §4 makes the shipped library the gate.)
- cold route 97–144 ms and 58–76 MiB peak RSS on one x86 core
- the FTS5 prototype passes 29/30 applicable NAV-003 tier A/B rows

## Decision

### 1. One pack per country, three files, versioned per file
- The pack `mn` contains three files, each with its **own version**:

  | Kind | File (installed) | Source in the slot | Cadence (A4) |
  |---|---|---|---|
  | `tiles` | `basemap.pmtiles` | the slot's tiles archive, byte-identical, z0–14 (A8) | **monthly** |
  | `routing` | `routing.tar` | the slot's `valhalla_tiles.tar`, byte-identical | **weekly** |
  | `search` | `search.sqlite` | built from the slot's Photon dump, schema in §3 | **weekly** |

- **File version = the NAV-006 slot ID** the file was cut from (UTC `YYYYMMDDTHHMMSSZ`, ADR-0014 §1). Each file lives under its own version directory: `packs/mn/<fileVersion>/<file>.gz`.
- **`routing` and `search` are always cut together** from the same slot, so they share one version. They are the weekly part of the pack.
- **`tiles` is cut monthly.** A published pack may therefore combine a basemap from an older slot (up to about one month) with the routing graph and search index of the newest weekly slot. This is intended. The map's roads can lag the routing graph by up to a month, the same order of difference the online map already has against server routing on a day the tiles step fails (R4).
- **`pack_version`** in the manifest = the slot ID of the publication (the slot of the newest routing and search files). It identifies the manifest; clients decide downloads per file.
- Every file comes from a slot that was **active and verified** when it was cut, so each file is exactly data the public server verified, and offline routes match server routes for that graph.
- **The graph is never rebuilt for the phone.** The spike showed that a separate build changes routes (spike §2.3).
- One country pack, not per-region packs. Cross-region routing needs every graph anyway, and the PO chose all of Mongolia.

### 2. Routing on the device: `valhalla-mobile`, unmodified, in a separate process, behind `RouteRequester`
- Dependency: `io.github.rallista:valhalla-mobile` (MIT), **pinned** (0.6.3 at the time of writing), used as published. We do not fork it.
- **Process isolation (skeptic mitigation).** The engine runs in a dedicated app process (for example `android:process=":routing"`) inside an `OnDeviceRoutingService`, a **bound service** with a small AIDL interface. Only this process loads `libvalhalla` and the wrapper's Kotlin layer. Ferrostar, the guidance state, MapLibre and TTS stay in the main process.
  - Interface: request JSON in (the exact ADR-0009 §2 body), OSRM response bytes out, plus an error kind. The response goes through a `ParcelFileDescriptor` pipe (or a file in the app's cache directory), **not** inside the binder transaction. A cross-country OSRM body with banners and voice can exceed the ~1 MB binder buffer. `SharedMemory` is not used because it needs API 27 and `minSdk` is 26.
  - **Crash handling.** If the routing process dies (`DeadObjectException`, `binderDied`, or no answer within 10 s), the request is classified `Unavailable`. Guidance continues on the current route as today (NAV-005 sections G/H). The next request rebinds. After **3 deaths within 10 minutes**, on-device routing is disabled until the next app start or the next routing-file install, and the reroute and preview fall back to today's states. A crash counter without coordinates may be reported; no route request, coordinate or OSM ID goes to a log.
  - **Process priority.** The main process binds with `BIND_AUTO_CREATE`, and with `BIND_IMPORTANT` while guidance runs, so the routing process inherits the foreground-service priority of NAV-005/NAV-012 and is not killed before it.
  - **Install self-test** (§5) also runs in the routing process, so a damaged or incompatible tar cannot crash the main process.
  - `Application.onCreate` runs in every process. The app's application class must skip the main-process initialisation (DI graph, MapLibre, notification channels, workers) in `:routing`.
  - Cost: one extra ART runtime, about 15–25 MiB (I), on top of the engine's 60–100 MiB with a 32 MiB tile cache. The process is unbound and may be reclaimed when the app is in the background and not guiding.
- Add an `OnDeviceRouteRequester` in the main process that sends the **exact ADR-0009 §2 body** (the same fields, `language`, `costing_options`, `heading` rule, `alternates`) through the service to `Valhalla.routeRaw(json)` and returns the OSRM bytes. Everything after that is unchanged: the same classification and rewrite, the same parser, and the same `ReroutePolicy` pacing (at most 1 in flight). Valhalla's narrative is still replaced, so no user-visible text depends on the engine version.
- Classification without HTTP: OSRM `code` `Ok` → `Ok`, `NoRoute` → `NoRoute`, `NoSegment` → `OutOfCoverage` (measured), `DistanceExceeded` → `TooFar`. Any other error, exception or process death → `Unavailable` (local engine), which shows the existing reroute and preview error states.
- Config: built from the library's config builder, with `tile_extract` = the installed `routing.tar`, `max_cache_size` = 32 MiB, and the server's `service_limits` for the used costings. No elevation, admin or timezone DB (the same as the server).
- Lifecycle: bound lazily on the first on-device request (or when a pack is installed and the network is lost during guidance, so the first offline reroute does not pay the cold start); kept while the app is in the foreground or guidance runs; unbound on `onTrimMemory(RUNNING_CRITICAL)` when not guiding. A routing-file switch never happens during guidance.

### 3. Search on the device: SQLite FTS5 + R*Tree, read with `androidx.sqlite:sqlite-bundled`
- The framework SQLite has no FTS5. `sqlite-bundled` (Apache-2.0) ships SQLite 3.50 *(corrected by A4 item 4: the pinned 2.5.2 bundles 3.46.0)* with FTS5, R*Tree and the trigram tokenizer (checked in the published binary). It runs in the main process (pure SQLite, no large native engine).
- The DB is built server-side. Its schema version is `format.search_schema` of the `search` file in the manifest. It holds:
  - a `place` table: display name by the label rule, `osm_key`/`osm_value`/`type` for the NAV-003 type labels, context fields, centroid, extent, importance
  - an FTS5 index over Cyrillic-folded names (ү→у, ө→о, ё→е), a Latin skeleton of every name variant (Cyrillic→Latin, kh→h and similar, doubled letters collapsed, adjacent words joined) and context
  - a trigram table
  - a vocabulary for edit-distance expansion
  - an R*Tree for reverse geocoding
  - Traditional script is stripped at build time

  The exact normalisation tables are specified in the search story and shared as test vectors between the builder and the app.
- The Android engine reuses the ADR-0012 query plan (coordinate parser, abbreviation expansion, Latin and vowel variants) and returns `PhotonFeature` objects, so the search UI, cards and type labels are unchanged.
- **Quality gate (A6).** Offline search passes at least **90 %** of the applicable NAV-003 tier A + B golden rows, with type-label conditions evaluated (the prototype checked names only). Category queries such as «Галт тэрэгний буудал» (B13) may fail.
- **Held-out set (reviewer concern).** QA keeps a second query set that the search-DB builder and the Kotlin ranking are **not tuned on**: at least 30 queries, including countryside **soum and bag** names (Cyrillic, Latin and Russian-layout spellings) and aimag centres outside Ulaanbaatar. Its expected results come from online Photon on the same slot. The story reports both pass rates. The held-out rate gating launch, and its threshold, is a PO decision (open question); until then it is reported, not gating.
- Reverse geocoding: the nearest named object within 50 m of the Photon result for P1–P6.

### 4. Compatibility and parity (the shipped library is the gate)
- Each file's `format` in the manifest records what the app needs to check it: `graph_builder` (for example `valhalla 3.9.0`) for `routing`, `search_schema` for `search`, the PMTiles spec version and zooms for `tiles`; the manifest records `pack_schema`. The app has a compiled-in **allow-list** of graph builder versions that the compatibility gate below verified with the shipped engine. A routing file outside the list is not offered, and the installed one stays.
- **Gate 1, compatibility (CI, mobile + QA).** It runs whenever the pinned `valhalla-mobile` version, the server's Valhalla version, or the routing golden set changes. It runs the **shipped `valhalla-mobile` artefact**, not the upstream Docker image:
  - preferred: an instrumented test on an **x86_64 Android emulator** that loads the same AAR version the app ships (if the AAR has an x86_64 ABI) and calls `routeRaw` through the same `OnDeviceRoutingService`;
  - otherwise: a host (Linux x86-64) build of the **same Rallista fork commit** that the pinned AAR was built from, with the same build options, called with the same request bodies.

  It routes the golden route set (the spike's 12 routes plus the NAV-005 GPX fixture routes) on a `routing.tar` from the server's builder and compares with the server engine on the same tar: distance, duration, manoeuvre sequence and geometry, as in spike §2.3. A pass adds that `graph_builder` to the allow-list for the next app release. A difference blocks the version change.
- **Gate 2, per-pack publication (server, backend).** Before a new `routing` file is published, the pack-publish step routes the same golden set on the candidate `routing.tar` with a **host build of the same fork commit** (a pinned container image built from that commit, unmodified) and compares with the active server engine. A difference blocks publication; the previous manifest stays.
- The upstream Valhalla Docker image of the same version (used in the spike) is **evidence only**, never the gate.
- A server Valhalla upgrade, or a `valhalla-mobile` upgrade, needs an ADR note that re-runs Gate 1 first.

### 5. Delivery: static files, a manifest, an app-side pack manager
**Server (NAV-006 extension).**
- A `pack-publish` step runs after a successful switch (ADR-0014) and reads only the now-active, verified slot:
  - **weekly:** when the last published `routing`/`search` version is ≥ 7 days old (configurable weekday), it hard-links or copies the slot's tar, builds `search.sqlite`, writes gzip copies, computes SHA-256 of the raw and gzip files, runs Gate 2 and the self-tests, and writes `packs/mn/<slotId>/routing.tar.gz` and `search.sqlite.gz`;
  - **monthly:** when the last published `tiles` version is ≥ 28 days old (configurable), the same run also cuts `basemap.pmtiles.gz` into the same directory. Otherwise the new manifest keeps pointing at the previous tiles file.
- It then writes the new `packs/mn/manifest.json` (temp file + one `rename(2)`).
- **Retention:** every file referenced by one of the last 3 published manifests, so a download that started before a switch can still resume. Disk is about 0.12 GB per weekly routing + search version and 0.2 GB per monthly tiles version (raw and gzip).
- The gateway serves `/packs/` statically:
  - Range 206 and a strong ETag
  - no `Content-Encoding`
  - `immutable` caching on version paths and `no-cache` on the manifest
  - a per-IP limit
  - no auth, no personal data (requests carry no coordinates)
- A plain static host or CDN may serve the same tree.

**App.** A `PackManager` on WorkManager. Installed state is per file kind.
- **Network (A2).** Downloads use `NetworkType.UNMETERED` by default. Mobile data is used only after an explicit **per-download confirmation that shows the download size** (from `download_bytes` of the files to fetch). The confirmation applies to that one download only. The small manifest (a few KB) may be fetched on any validated network.
- Range resume with `If-Range` at immutable URLs.
- Checks the compressed and installed SHA-256, then decompresses with streaming gzip.
- Self-tests before activation: PMTiles header and bounds; a test route on the tar (in the routing process, §2); SQLite `quick_check` and a test query.
- Storage pre-check with `getAllocatableBytes`: first install about 340 MB free; a weekly update about 160 MB (new routing + search next to the old ones, plus the largest compressed file and a margin); a monthly update with tiles about 540 MB.
- Installs into `noBackupFilesDir/packs/<fileVersion>.partial/` → rename → `active.json` (temp + rename), which records the installed version of each kind.
- Consumers switch at safe points: search at once; map at the next style load; routing after guidance.
- **First launch (A7).** When there is no pack and the device is on Wi-Fi, the app offers «Download Mongolia map» **once** (the flag is stored; UX places it). The action is always available in Settings.
- **Automatic update (A3, A4).** A periodic worker (24 h; unmetered, battery not low, storage not low) fetches the manifest with `If-None-Match`. For each kind whose installed `sha256` differs from the manifest, it downloads in the background without asking. In practice this moves routing + search (about 33 MB gzip) weekly and the basemap (about 86 MB gzip) monthly. The user sees the new data date.
- **Mobile-data mini-update (A3).** When the app is in the foreground, the network is validated but not unmetered, and the installed routing and search version is **older than 14 days**, the app offers an update of **routing + search only**, showing its size (about 33 MB gzip). Tiles are never included. Accepting runs the same download with the mobile-data confirmation already given. How often the offer repeats after a decline is a UX/PO detail (open question; technical default once per 7 days).
- **Fallback order (A1).**

  | Capability | Pack installed, validated network | Pack installed, no validated network | No pack |
  |---|---|---|---|
  | Map | **from the pack** | from the pack | online PMTiles (today) |
  | Preview route | gateway first, on-device fallback | on-device, 0 requests | today's states |
  | Reroute during guidance | **gateway first**, on-device fallback, same `ReroutePolicy` pacing | on-device, 0 requests | today's back-off |
  | Search and reverse | gateway first, on-device fallback | on-device, 0 requests | today's states |

  Fallback rule, the same for route, reroute, search and reverse:
  - **No validated network** (`NET_CAPABILITY_VALIDATED` absent): on-device at once, no request.
  - **Online attempt:** the gateway request has a **3 s budget to response headers**. On `Offline` (`IOException` before a response), `Unavailable` (502/503/504), `RateLimited` (429), or no headers within 3 s, the request is cancelled and the on-device engine answers in the same attempt. The user gets a result within about 3 s plus the local compute time. Once headers have arrived, the body read continues under the existing client timeouts (12 s route, 8 s search).
  - **Authoritative answers are not retried locally:** `Ok`, `NoRoute`, `OutOfCoverage`, `TooFar` and 400 from the gateway are shown as today.
  - **Stickiness:** after a fallback caused by a timeout or `Offline`, the on-device source is used directly for 60 s, or until the network callback reports a newly validated network, so typing on a dead connection does not wait 3 s per query. 429 follows the existing `Retry-After` rules for the online source and answers locally in the meantime.
  - A result says nothing about its source on screen unless UX specifies an indicator.
  - ADR-0009 `ReroutePolicy` counts one reroute attempt per online try plus its fallback. The P5 back-off applies only when both sources fail.
- **Graph consistency.** When online, preview and reroute both use the server's daily graph; when offline, both use the pack's weekly graph. A mixed pair (preview online, reroute offline) happens only when connectivity drops mid-trip; the reroute replaces the whole remaining route, so the guidance never joins two graphs in one route.

The map uses the installed basemap (`pmtiles://file://…/packs/<fileVersion>/basemap.pmtiles`); each version has its own path, so MapLibre never reuses a header cached for another archive.

### 6. Contract (applied as openapi 0.6.0)
Two operations, tagged `packs`, served statically by the gateway or a static host:
- `GET /packs/{region}/manifest.json` (`getOfflinePackManifest`): 200 `OfflinePackManifest` with `ETag` and `Cache-Control: no-cache`; 304 on `If-None-Match`; 404 when no pack is published.
- `GET /packs/{region}/{fileVersion}/{file}` (`getOfflinePackFile`): `fileVersion` matches `^[0-9]{8}T[0-9]{6}Z$`, `file` is one of `basemap.pmtiles.gz`, `routing.tar.gz`, `search.sqlite.gz`. 200, 206 with `Content-Range`, 304, 404, 416, as `getBasemapPmtiles`, with `Cache-Control: public, max-age=31536000, immutable`. Clients take the path from the manifest and never build it from `pack_version`.

`OfflinePackManifest` (full schema in `openapi.yaml`):
- required: `pack_schema` (const 1), `region` (`mn`), `pack_version` (slot ID of the publication), `published_at`, `attribution`, `licence`, `files`, `total_bytes`, `total_download_bytes`
- optional: `self_test` (`route`, `search`)
- `files[]` items require `kind` (`tiles` | `routing` | `search`), **`version`** (the file's slot ID), `path` (relative to `/packs/{region}/`, for example `20261004T193412Z/routing.tar.gz`), `encoding` (`gzip`), `download_bytes`, `download_sha256`, `bytes`, `sha256`, **`data_timestamp`** (the OSM data date for `tiles` and `routing`, the Photon dump date for `search`; shown to the user as the data date). Optional `format`: tiles `{pmtiles, minzoom, maxzoom}`, routing `{graph_builder}`, search `{search_schema}`.
- Exactly one file per kind. `routing` and `search` have the same `version`; `tiles.version` ≤ that version.

Compared with the proposed draft, the top-level `osm_data_timestamp` and `photon_data_timestamp` moved into each file as `data_timestamp`, because the files now have different ages.

### 7. Licences
- ODbL: attribution «© OpenStreetMap contributors» on every map screen (rule 8), in the pack screen and in the licences screen.
- Packs are offered under ODbL, with no DRM.
- The method of making them (the public pipeline) and the files themselves are freely available, which meets ODbL §4.4 and §4.6.
- Library notices: `valhalla-mobile` and its Valhalla, protobuf, Boost and lz4 code (MIT, BSD-3, BSL-1.0, BSD-2), and `sqlite-bundled` (Apache-2.0). Nothing GPL ships.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Chosen: one 3-file pack with per-file versions (tiles monthly, graph and search weekly), `valhalla-mobile` on the server's graph in a separate process, SQLite FTS5 search, online first with on-device fallback** | Measured sizes (202 MB / 119 MB gzip first install, about 33 MB weekly), 12/12 route parity, reuses ADR-0009 and ADR-0012 seams, all permissive, a native crash cannot end guidance | Third-party wrapper lags the server version (R1); extra process and AIDL code; map can lag routing by up to a month |
| One version for all three files, all weekly (the proposed draft) | One version to reason about | About 0.5 GB per user per month; the PO chose monthly tiles (A4) |
| Reroute on-device first when a pack is installed (spike §4.2 draft) | Deterministic, no network wait | Preview (daily server graph) and reroute (weekly pack graph) on different graphs while online; the PO chose online first (A1) |
| Engine in the main process | Simpler, no IPC | A native crash in Valhalla ends the app and the running guidance |
| Own JNI build of upstream Valhalla | Same engine version as the server, no third-party wrapper | We own an NDK/vcpkg cross-build for 4 ABIs: +10–15 person-days plus upkeep. Stays the fallback if the wrapper stalls |
| Graph rebuilt for the phone with the mobile engine's version | No reader-compatibility question | Measured route differences against the server (7/12 identical) |
| GraphHopper or OSRM on the device | Mature engines | Different engine and graph: no parity with server routes and NAV-005 fixtures |
| MapLibre `OfflineManager` regions | Built-in API | Per-tile HTTP, no file-level integrity or atomic version, slow for 442k tiles |
| Per-aimag packs | Smaller per user | The PO chose all of Mongolia; cross-region routing needs all graphs; more versions |
| Binary delta updates | Smaller transfers | Not measured yet, more code; the PO chose full files at launch (A5); revisit after two weekly packs exist |
| Bundled in the APK / Play Asset Delivery | No download step | Rejected by the PO (download in the app); data updates would need app releases; PAD excludes phones without Play (D62) |

## Consequences
- ADR-0001's "offline navigation is a later phase" no longer holds. ADR-0001 carries a dated note (2026-10-04) that offline is a launch requirement and points here.
- **Contract 0.6.0** adds the two `packs` operations. Backend extends `contract_check.py` to cover them.
- The server gains a pack-publish step (weekly graph and search, monthly tiles), the Gate 2 parity check with a host build of the mobile engine's fork commit, a `/packs/` static location and about 0.6–0.8 GB of retained files. The NAV-006 verify step gains the pack checks.
- The app gains about 10 MB per ABI (installed), a second process for routing with an AIDL service, a pack manager with per-file state, two new transports (`OnDeviceRouteRequester`, on-device search and reverse), the fallback policy with a 3 s budget, the first-launch offer and pack settings. Today's offline states («Интернэт холболт алга» …) remain for users without a pack.
- **Data volume per user** drops from about 0.5 GB to about **0.23 GB per month** (one 86 MB basemap plus about 4.3 × 33 MB). About 2.3 TB per month at 10,000 active users (I). NAV-009 input.
- When online, map data on the phone can be up to about a month older than the server's daily tiles, while routes and search are current. Users see the map data date in Settings (R4).
- Valhalla upgrades on either side are coupled through Gate 1 (§4).
- Fewer coordinates leave the phone only when the device is offline. With online first (A1), the online paths and their privacy rules (D9) are unchanged.
- iOS can reuse the same packs later with `valhalla-mobile` for Swift (not in scope).
- **Effort and schedule.** The spike's estimate is **45–67 person-days** (spike §8). The PO answers and the skeptic mitigations add about **4–7 person-days**: the separate routing process and AIDL transport (mobile, 2–3), Gate 1 with the shipped library on an emulator or host build (QA + mobile + backend, 1–2), and per-file versioning with the mobile-data mini-update (backend + mobile, 1–2). Total about **49–74 person-days**. Planned as **6–9 calendar weeks** with backend ∥ mobile and UX ∥ architect, not the spike's 5–7. The device benchmark and the process-isolation proof go first in the routing story, because device behaviour is the largest uncertainty. This is on the launch critical path.
- Follow-up work: spike §9 items 1–11, with these changes from the PO answers: item 3 includes the first-launch offer, the per-download mobile-data confirmation and the 14-day mini-update; item 5 includes the separate process and Gate 1; item 2 includes the held-out query set.

## Amendment A1 (2026-10-04): NAV-020 design. Engine pins, publication rules, server layout
Written by the architect while splitting NAV-020 into tasks (`docs/architecture/tasks/NAV-020-offline-pack-build-publication.md`). These are technical clarifications inside the accepted decision. No product decision changes. Contract: openapi **0.6.1** (documentation only, see its change log).

**F1. What `valhalla-mobile` 0.6.3 is actually built from (measured 2026-10-04).** The spike, §2, §4 above, NAV-020 and NAV-021 call the Gate 2 engine source "the Rallista fork commit". The facts:
- Tag `0.6.3` of `github.com/Rallista/valhalla-mobile` is commit `b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8` (2026-08-28). Its `.gitmodules` points `src/valhalla` at **upstream** `github.com/valhalla/valhalla.git`, and the submodule commit is `e2f017b16080f49203de245a211b09efab09cf72`, which is **upstream tag `3.6.3`** (checked with `git ls-remote`). The README section "Valhalla Fork" is out of date for this tag.
- Build inputs of the wrapper at that tag: `src/CMakeLists.txt` (`ENABLE_TOOLS`, `ENABLE_DATA_TOOLS`, `ENABLE_PYTHON_BINDINGS`, `ENABLE_HTTP`, `ENABLE_SERVICES`, `ENABLE_TESTS` all `OFF`; C++20; Release) and `src/vcpkg.json` (vcpkg `builtin-baseline` `f176b58f35a75f9f8f54099cd9df97d2e2793a2e`; static triplets).
- The published AAR (`valhalla-mobile-0.6.3.aar`, SHA-256 `ac6d7023…eb11cde`) ships `libvalhalla-wrapper.so` for **arm64-v8a, armeabi-v7a, x86 and x86_64**. The x86_64 library was built with Android clang 21.0.0 (NDK toolchain string in `.comment`) and contains protobuf 4.25.1. It needs only `liblog`, `libz`, `libm`, `libdl`, `libc`.
- The AAR's `default.json` `service_limits` for `auto`, `bicycle` and `pedestrian` distances equal the server's generated 3.9.0 config. The only differences are key names that changed between versions.

**Decisions that follow from F1:**
1. **Gate 2 engine (NAV-020 AC 35).** "The pinned fork commit" means the pair **wrapper `b47ad5a…` + Valhalla `e2f017b…`**. The image is built unmodified from these two commits with the wrapper's CMake options and vcpkg baseline, for `x64-linux` with static linkage and clang 21. A small driver of ours calls the wrapper's `ValhallaActor::route(json)` (the same C++ entry point that the JNI `route` uses) once per request and writes the raw response. The driver is the only code of ours in the image. The upstream `ghcr.io/valhalla/valhalla:3.6.3` image comes from the same source commit, but its dependencies and build options differ, so it stays **evidence only**.
2. **Closest equivalent, if the wrapper does not build on Linux.** The wrapper's `main.cpp` compiles its JNI and Obj-C++ parts only under `__ANDROID__` / `__APPLE__`. If the host build of the wrapper target still fails, the fallback is upstream Valhalla `e2f017b` alone, built with the same options and vcpkg baseline. The driver then calls `valhalla::tyr::actor_t::route` on a 16 MB-stack thread, which is what `ValhallaActor` does. This counts as meeting NAV-020 AC 35 and needs no story change. The run log records which variant was built.
3. **Gate 1 (NAV-021 AC 33)** takes its preferred path: the x86_64 emulator with the shipped AAR. The host build above is only its fallback.
4. **Residual risk.** Gate 1 and Gate 2 both run x86_64 code. The arm64 library may contract floating-point operations differently (FMA). The NAV-021 device replays (AC 31) on a real arm64 phone are the only check of this. Any difference found there is a NAV-021 defect, and Gate 2 stays strict.
5. **On-device config (shared by Gate 2 and the Android `:routing` service).** Start from the pinned AAR's `default.json` and override only these keys: `mjolnir.tile_extract` = the installed tar; `mjolnir.max_cache_size` = `33554432`; `mjolnir.tile_dir`, `mjolnir.traffic_extract`, `mjolnir.admin`, `mjolnir.timezone`, `mjolnir.landmarks` = `""`. `service_limits` is **not** overridden, because it already equals the server's values (F1). Both sides assert these values in a unit test. A changed override is an ADR note.

**Publication rules (NAV-020):**
6. **Tiles are cut only in a run that also cuts the weekly part.** When `tiles` is due (≥ 28 days) but the weekly part is not (< 7 days), nothing is cut that day. Tiles are cut with the next weekly cut, so they are at most about 35 days old. This keeps the contract invariant `tiles.version ≤ routing.version = search.version = pack_version`. Without the rule, a tiles-only cut from a newer slot would break it. `TILES=1` implies `FORCE=1`.
7. **Version directories are write-once.** `packs/mn/<slotId>/` is assembled as `<slotId>.partial/` and renamed when complete. If it already exists (a second `FORCE=1` on the same active slot), the step ends `not due (already published)`. No file is added to or replaced in an existing version directory.
8. **The server tree holds only `.gz` files.** Raw files are never kept under `packs/`. Gate 2 and the self-tests run on files **decompressed from the candidate `.gz`** into a work directory (`packs/.work/<run-id>/`, same filesystem), so the bytes that phones install are the bytes that were checked. Steady-state disk is about 0.2–0.4 GB (three weekly parts of about 33 MB, plus one or two tiles files of about 86 MB), below the 0.6–0.8 GB estimated in §5.
9. **Deterministic outputs.** The gzip header is written without a file name and with `mtime` 0, at a fixed level (6). The search builder writes rows in a fixed order with a pinned SQLite. The same input therefore gives the same `download_sha256` and `sha256`. Clients compare `sha256` and skip unchanged files, so an unchanged Photon dump costs phones nothing (NAV-020 edge case "Photon dump not updated").
10. **Gate 2 reference engine** = the active lane's Valhalla, reached through the public gateway on loopback (`127.0.0.1:GATEWAY_PORT`), paced at ≤ 5 requests/s. This is "the active server Valhalla" in NAV-020 AC 10, with its real config. It is never a separately started 3.9.0 container.
11. **Manifest `ETag` freshness.** nginx's static `ETag` is `"<mtime>-<size>"`. The writer makes the new manifest's `mtime` differ from the old one: when both fall in the same second, it sets old + 1 s. A same-size manifest written in the same second could otherwise keep its `ETag` and get a wrong 304. A rollback republish (NAV-020 AC 28) writes a fresh copy, so it gets a new `mtime` too.
12. **NAV-006 outcome is untouched by the pack step.** The pack step runs inside the NAV-006 lock after the old lane has stopped and slot cleanup is done. Its result is a separate `pack` field in the run summary, in `state.json` and in `make status`. It never changes the NAV-006 result or exit code, so the rebuild heartbeat is unaffected. Its own failures call the alert hook once (NAV-020 AC 26).
13. **No Gate 2 tolerance.** Strict equality (NAV-020 AC 10). If a floating-point-only difference ever blocks a publication, the architect writes an ADR note first. Any tolerance is a change to NAV-020 through the BA.

## Amendment A2 (2026-10-04): NAV-021 integration review notes
Written by the architect during the light review of the NAV-021 Android change (`mobile/android`, not yet committed). These are technical clarifications inside the accepted decision. No product decision changes, and the contract does not change.

1. **§7 licence list, completed.** A symbol scan of the shipped `libvalhalla-wrapper.so` and the wrapper's `src/vcpkg.json` at `b47ad5a` show more statically linked code than §7 lists. The full list for `valhalla-mobile` 0.6.3 is: Valhalla 3.6.3 and its bundled `date` library (MIT), protobuf 4.25.1 (BSD-3-Clause), **Abseil (Apache-2.0, pulled in by protobuf)**, Boost (BSL-1.0), lz4 (BSD-2-Clause), **RapidJSON, robin-hood-hashing and unordered_dense (MIT)**. The Kotlin side brings Moshi 1.15.1 (Apache-2.0), `kotlin-reflect` / `kotlin-stdlib-jdk7` / `-jdk8` 1.8.21 (Apache-2.0) and `valhalla-models` / `valhalla-models-config` 0.5.2 (MIT). All are permissive and none is GPL. `mobile/android/THIRD_PARTY_NOTICES.md` is the list that ships on the licences screen.
2. **Moshi and `kotlin-reflect` 1.8.21.** The library's default Moshi uses `KotlinJsonAdapterFactory`, which reads Kotlin 2.2 metadata through `kotlin-reflect` 1.8.21 and may fail at runtime. The app passes its own Moshi instance with an explicit adapter for the `{code, message}` error envelope, through the library's public constructor. This counts as using the library as published: it is a supported constructor argument, not a patch. Removing `moshi-kotlin` / `kotlin-reflect` from the dependency graph (about 1 MB) is **not** allowed for now. A library path that still reaches the default factory would then fail with `NoClassDefFoundError` inside `:routing`. That could only be ruled out by an instrumented test that is not yet written. Revisit when R8 is enabled for release builds, because R8 removes the unused code anyway.
3. **10 s engine timeout (NAV-021 AC 23).** Confirmed: on a timeout the main process drops every binding, kills the stuck `:routing` process (same uid, `Process.killProcess`) and counts it as one death towards the 3-in-10-minutes rule in §2. Only unbinding would leave a busy native engine that blocks the service's single worker thread, so the "next request rebinds" requirement would not hold.
4. **Gate 1 host (NAV-021 AC 33).** This is already decided by A1 item 3: the shipped AAR on an **x86_64 emulator**. The AAR has an x86_64 ABI, so the host build is only the fallback. The CI job needs a runner with `/dev/kvm`. Until one exists, the job may run on the A1 item 1 host-build image and must say so in its log. In both cases the `graph_builder` allow-list (`valhalla 3.9.0`) still rests on the spike's 12/12 stand-in result, and AC 7 and AC 33 are **not verified** until the job has run once.

## Amendment A3 (2026-10-05): NAV-022 integration review notes
Written by the architect during the light review of the NAV-022 Android change and the backend static export (`mobile/android`, `backend/`, not yet committed). These are technical clarifications inside the accepted decision. No product decision changes, and the contract does not change (openapi stays **0.6.1**).

1. **Download jobs are plain WorkManager jobs, not a foreground service (replaces task P5 "`setForeground`, type `dataSync`").** WorkManager's foreground mode acquires a wake lock in its `Processor`, so it needs `WAKE_LOCK`. ADR-0013 removes `WAKE_LOCK` from the app (`tools:node="remove"`, NAV-012 AC 48), and that ADR adds it back only for a measured guidance-timer need. A download is not a reason to reopen it. So:
   - User-started downloads and the periodic check run as ordinary (non-expedited) `CoroutineWorker` jobs. The app posts its own progress notification (channel OF1, low importance, OF8, action OF10) while the job runs and clears it at the end. No `FOREGROUND_SERVICE_DATA_SYNC`, no new permission (NAV-022 AC 41).
   - The system may stop a job at the end of its execution window (about 10 minutes on current Android versions) or under OEM battery savers. The job then ends with `retry` or is rescheduled by WorkManager, and the next run resumes with `Range` + `If-Range` (AC 14). A slow first install can therefore take several job windows. This is accepted for the PO phone test and for release. NAV-022 R2 covers the OEM part.
   - Revisit only if the AC 46 device checks show that a first install over Wi-Fi does not finish within one hour of being started with the app in the background. The options then are an ADR-0013 amendment (`WAKE_LOCK` + `dataSync` foreground job) or Android 14+ user-initiated data-transfer jobs. Either is an ADR note, not a mobile-only change.
2. **Resume after a reboot happens at the next app start.** WorkManager reschedules its jobs after a reboot through a `RECEIVE_BOOT_COMPLETED` receiver. NAV-012 AC 25 / ADR-0013 exclude that permission, and it stays excluded. The queued download, the periodic check and the partial files survive the reboot, and WorkManager reschedules them when the app process next starts (any launch). NAV-022 AC 15's last sentence ("resumes it after the reboot") is met in that form. The BA aligns the AC wording with NAV-012 AC 25 (an open question for the PO if the wording is not enough).
3. **Search self-test before NAV-023 (task P4).** Until NAV-023 brings the shared skeleton rules, the app runs `self_test.search.q` against the `names` column of `place_fts` with the simple fold (lower case, «ү»→«у», «ө»→«о», «ё»→«е»), every token quoted and `*` on the last, and needs ≥ 1 row. The builder writes `names` with the same fold (`search_builder.fold`), so this proves the FTS5 index and the file. The server's Gate on the `skel` column is unchanged. When NAV-023 lands, the app switches to the server's `skel` query so both sides test the same expression.
4. **A static host may serve the pack (§5, last line), with the client rules made explicit.** The packs base URL is a build property (`nav.packBaseUrl`, never committed; https only in release). Unset, it is `<gateway>/packs`. A static host must serve exactly the export tree of `make pack-export`: `<base>/mn/manifest.json` and `<base>/mn/<fileVersion>/<file>.gz`, byte-identical with the published files and without `Content-Encoding`. The app:
   - takes file URLs only from the manifest `path` (never from `pack_version`)
   - sends `Accept-Encoding: identity` for pack files, so no HTTP content coding can change the checked bytes
   - uses a strong `ETag` as the `If-Range` validator, else `Last-Modified`, else sends `Range` alone (the URL is immutable and the SHA-256 catches anything else)
   - treats a 200 to a `Range` request, or a 206 that does not start at the requested byte, as a restart from byte 0

   The export holds only the current manifest's files. A phone whose download started before an upload gets a 404 and re-reads the manifest (NAV-022 AC 15). Keeping the previous version directories on the host until phones have updated is an operator recommendation (backend README), not a requirement.
5. **Routing switch and guidance.** The NAV-021 guidance pin (`OnDeviceRouting`, version per session) is what keeps a guidance session on its routing file. `active.json` may therefore change during guidance. The pack job also waits for guidance to end before the routing self-test, so the cold engine never competes with a reroute. The map tile source changes only outside guidance. A file pinned by guidance or used by the map is deleted only after its consumer releases it (AC 19, 36).

## Amendment A4 (2026-10-05): NAV-023 integration review notes
Written by the architect during the light review of the NAV-023 builder follow-up and the Android on-device search (`backend/pack/`, `mobile/android`, not yet committed). These are technical clarifications inside the accepted decision. No product decision changes, and the contract does not change (openapi stays **0.6.1**).

1. **Builder v2 stays at `search_schema` 1 (one-time exception).** Builder v2 changes stored data: context values come from `<key>:mn` first, there is a new `place.locality` column (from `address.neighbourhood`), and `vocab` also holds the joined-word skeleton keys. The rule is that a stored-key change increments `search_schema`. It is waived **once**, because no pack has been published anywhere: the NAV-020 pack step is not running on a server yet, and NAV-008 staging does not exist. The Android engine also reads both v1 and v2 files (it checks for `locality` with `pragma_table_info`), so a dev phone that holds a v1 test file keeps working. From the first published pack onwards, any change to stored keys or columns increments `search_schema`, and the app's `KNOWN_SEARCH_SCHEMAS` and the pack config change in the same commit. `PACK_SEARCH_BUILDER_VERSION` defaults to 2; a deployment `.env` that still says 1 makes the builder refuse with exit 3 (fail closed, as intended).
2. **One ranking: `backend/pack/search_engine.py` is the reference, and the app ports it.** The reference engine (`RANKING_VERSION` 1) and the Kotlin `OfflineSearchEngine` must give the same places in the same order. The gate harness, the held-out report and the reverse comparison may run on either engine. Any app-side search step must also exist in the reference engine before a measurement is reported (R1). The Android Latin «u»→«o» step (used only at 0 hits) is not yet in the reference, so the reference's held-out report understates the app on rows such as "Ulgii". The backend ports that step, or the held-out report is run with the Kotlin engine. A ranking change bumps `RANKING_VERSION` on both sides.
3. **Shared normalisation vectors live at `backend/pack/vectors/search-normalisation.v1.json`.** Both the builder tests and the Android tests read this path. It replaces the "QA's file in `tests/`" location of the NAV-023 task file SB1/SM2. The file is append-only within a `vectors_version`. NAV-023 AC 3 still names a QA-owned file in `tests/`; the BA aligns the wording and QA adopts or reviews this file. The vector content stays a NAV-023 (BA) change.
4. **Bundled SQLite version.** `androidx.sqlite:sqlite-bundled` 2.5.2 (the pinned version) bundles SQLite **3.46.0**, not 3.50 as §3 said. FTS5, the trigram tokenizer and R*Tree are present. The server builder runs SQLite 3.46.1 (`python:3.14-slim`). Same minor line, so the file format and FTS5 syntax match. JVM tests run the Android artefact's `BundledSQLiteDriver` class with the host native library from the `sqlite-bundled-jvm` artefact of the same release (test-only, not packaged). This fulfils NAV-023 task SM1 "JVM unit tests use the same driver's JVM artefact".


## Amendment A5 (2026-10-06): licences screen review (NAV-005 section P)
Written by the architect during the light review of the licences screen, the restore source fix and the APK API-level checker (`mobile/android`, not yet committed). The contract does not change (openapi stays **0.6.1**).

1. **The §7 / A2 symbol-scan rule applies to every shipped native library, not only `libvalhalla-wrapper.so`.** A scan of the debug APK (2026-10-06) shows statically linked third-party code that the licences screen does not list yet:
   - `libmaplibre.so` (MapLibre Native android-v13.6.1): ICU 61 (`ubidi_*_61` symbols), FreeType, HarfBuzz, its own SQLite copy (source id 2024-04-15), protozero, supercluster, plus the other vendored code in upstream's third-party licence list for that tag. Upstream publishes these notices with the release; the generator should bundle that upstream list verbatim as parts of the MapLibre entry, not re-derive it.
   - `libferrostar.so` (Ferrostar 0.57.0): Rust crates and the Rust standard library (for example `geo`, `geographiclib-rs`, `serde`, `serde_json`, `chrono`, `uuid`, `rand`, `regex-automata`, `uniffi_core`, `anyhow`, `android_logger`). The list comes from Ferrostar's `Cargo.lock` at the pinned tag (for example with `cargo about`, run by the mobile engineer offline, output committed under `mobile/android/licenses/`), not from strings.
   - `libjnidispatch.so` (JNA, covered), `libsqliteJni.so` (covered), `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` (AndroidX, Apache-2.0, covered).
   Some of these licences are outside the AC 96 allow-list (ISC, the Unicode/ICU licence, the FreeType Project Licence as the elected option of FreeType's FTL-or-GPL-2.0 dual licence, Zlib). All are permissive and none forces GPL, so §7's "nothing GPL ships" still holds, but extending the allow-list is a BA/PO change to NAV-005 AC 96 and stays an open question until then. The release gate (AC 98, D17/D108) already blocks external release until this is complete.
2. **Process for the future:** a dependency bump that changes a native library's version (MapLibre, Ferrostar, valhalla-mobile, sqlite-bundled, JNA) re-runs the scan and updates the per-library notice list in the same commit. The `checkThirdPartyNotices` drift check catches Maven changes only; the native list is pinned by version in the generator, so a version mismatch between the resolved artifact and the pinned native list should fail the check (mobile engineer's implementation).
3. **`method_url` in `active.json` (NAV-005 Open question 6):** no change now. §7 already treats the public pipeline and the freely available files as meeting ODbL §4.6. If counsel chooses option (b), keeping the manifest's `method_url` in `active.json` is a small NAV-022 change with no contract change (the manifest already carries it); it is shown as plain text, never a link (AC 89).
4. **MapLibre Java licence (mobile open question):** keep Apache-2.0 per the published POM for `android-sdk-geojson` / `android-sdk-turf` 6.0.1 until the legal review; the review compares it with the upstream repository's current MIT `LICENSE`. Both are permissive, so either outcome is a data change in the generator only.
