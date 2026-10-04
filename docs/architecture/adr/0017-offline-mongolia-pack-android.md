# ADR-0017: Offline Mongolia pack for Android. One weekly, versioned three-file pack (PMTiles, the server's Valhalla graph, an SQLite FTS5 search DB) cut from a verified NAV-006 slot; on-device Valhalla through `valhalla-mobile` behind `RouteRequester`; offline search on `sqlite-bundled`

- **Status:** proposed (2026-10-04). Accepted only after the PO decides on the spike [offline-android](../spikes/offline-android.md) and its open questions §10.
- **Date:** 2026-10-04
- **Stories:** none yet. The follow-up stories from the spike §9 go through triage. Answers backlog owed decision 3 (PO, 2026-10-04: offline at launch = yes). Touches NAV-005, NAV-006, NAV-011, NAV-012; amends ADR-0001 Consequences ("offline navigation is a later phase").
- **Contract:** `openapi.yaml` **unchanged by this proposal** (stays 0.5.5). The two paths in §6 are added as 0.6.0 only when this ADR is accepted.

## Context
The PO decided on 2026-10-04 (triage row "Offline use") that the **real** Android app must work without internet for map display, guidance (already works), **new route and reroute**, and **search and reverse geocoding**. The coverage is all of Mongolia, it is needed at launch, the data is **downloaded in the app** (Wi-Fi by default, not bundled), it is refreshed **weekly**, the server keeps the daily NAV-006 rebuild, and Android comes first.

Constraints:
- Use upstream components unchanged (ADR-0001), with permissive licences, and Docker Compose on the server.
- The app already owns its route transport: `RouteRequester` → classify → keyed text rewrite → Ferrostar `createOsrmResponseParser(6u)` (ADR-0009 §2, §3.1). `FerrostarCore` is not used.
- MapLibre Native 13.6.1 reads `pmtiles://file://` but not `pmtiles://asset://` (ADR-0016 F1, F2).
- NAV-006 builds immutable, verified slots and switches them with a pointer rename (ADR-0014).
- Phones without Google Play services are supported (D62).

The spike measured on copies of the dev-stack artefacts (spike §2):
- PMTiles z0–14: 117.5 MB
- Valhalla tar: 63.7 MB
- prototype search DB: 20.4 MB
- total about 202 MB on the phone, about 119 MB gzip download
- `valhalla-mobile` 0.6.3 (Valhalla 3.6.3) on the server's 3.9.0 graph: **12/12 routes identical** to the server. On a graph rebuilt with 3.6.3: 7/12 identical.
- cold route 97–144 ms and 58–76 MiB peak RSS on one x86 core
- the FTS5 prototype passes 29/30 applicable NAV-003 tier A/B rows

## Decision

### 1. One pack per country, three files, one version
- The pack `mn` contains:
  - `basemap.pmtiles`: the slot's tiles archive, byte-identical, z0–14
  - `routing.tar`: the slot's `valhalla_tiles.tar`, byte-identical
  - `search.sqlite`: built from the slot's Photon dump, schema in §3
- **`packVersion` = the NAV-006 slot ID** it was cut from (UTC `YYYYMMDDTHHMMSSZ`, ADR-0014 §1).
- All three files come from the same slot. They are published only after that slot is **active and verified**, so a pack is exactly the data the public server verified, and offline routes match server routes for that graph.
- **The graph is never rebuilt for the phone.** The spike showed that a separate build changes routes (§2.3).
- One country pack, not per-region packs. Cross-region routing needs every graph anyway, and the PO chose all of Mongolia.

### 2. Routing on the device: `valhalla-mobile`, unmodified, behind `RouteRequester`
- Dependency: `io.github.rallista:valhalla-mobile` (MIT), **pinned** (0.6.3 at the time of writing), used as published. It is not forked by us.
- Add an `OnDeviceRouteRequester` that sends the **exact ADR-0009 §2 body** (the same fields, `language`, `costing_options`, `heading` rule, `alternates`) to `Valhalla.routeRaw(json)` and returns the OSRM bytes. Everything after that is unchanged: the same classification and rewrite, the same parser, and the same `ReroutePolicy` pacing (at most 1 in flight). Valhalla's narrative is still replaced, so no user-visible text depends on the engine version.
- Classification without HTTP: OSRM `code` `Ok` → `Ok`, `NoRoute` → `NoRoute`, `NoSegment` → `OutOfCoverage` (measured), `DistanceExceeded` → `TooFar`. Any other error or exception → `Unavailable` (local engine), which shows the existing reroute and preview error states.
- Config: built from the library's config builder, with `tile_extract` = the pack's `routing.tar`, `max_cache_size` = 32 MiB, and the server's `service_limits` for the used costings. No elevation, admin or timezone DB (the same as the server).
- Lifecycle: opened lazily on the first on-device request; kept while the app is in the foreground or guidance runs; closed on `onTrimMemory(RUNNING_CRITICAL)` when not guiding. A pack switch never happens during guidance.

### 3. Search on the device: SQLite FTS5 + R*Tree, read with `androidx.sqlite:sqlite-bundled`
- The framework SQLite has no FTS5. `sqlite-bundled` (Apache-2.0) ships SQLite 3.50 with FTS5, R*Tree and the trigram tokenizer (checked in the published binary).
- The DB is built server-side. Its schema version is `search_schema` in the manifest. It holds:
  - a `place` table: display name by the label rule, `osm_key`/`osm_value`/`type` for the NAV-003 type labels, context fields, centroid, extent, importance
  - an FTS5 index over Cyrillic-folded names (ү→у, ө→о, ё→е), a Latin skeleton of every name variant (Cyrillic→Latin, kh→h and similar, doubled letters collapsed, adjacent words joined) and context
  - a trigram table
  - a vocabulary for edit-distance expansion
  - an R*Tree for reverse geocoding
  - Traditional script is stripped at build time

  The exact normalisation tables are specified in story 2 and shared as test vectors between the builder and the app.
- The Android engine reuses the ADR-0012 query plan (coordinate parser, abbreviation expansion, Latin and vowel variants) and returns `PhotonFeature` objects, so the search UI, cards and type labels are unchanged.
- Quality gate: the NAV-003 golden set offline at the threshold the PO sets (spike §10 Q5).

### 4. Compatibility and parity
- The manifest records `graph_builder` (for example `valhalla 3.9.0`), `search_schema`, `pack_schema` and the PMTiles spec version. The app has a compiled-in allow-list of graph builder versions that CI verified with the shipped engine. A pack outside the list is not offered, and the installed pack stays.
- **CI parity job** (backend + QA, before a pack is published): the Valhalla Docker image of the **mobile engine's version** routes the golden route set on the candidate `routing.tar` and compares it with the server engine's results. The comparison covers distance, duration, manoeuvre sequence and geometry, as in spike §2.3. A difference blocks publication.
- A server Valhalla upgrade, or a `valhalla-mobile` upgrade, needs an ADR note that re-runs this job first.

### 5. Delivery: static files, a manifest, an app-side pack manager
**Server (NAV-006 extension).**
- A `pack-publish` step runs after a successful switch when the last pack is ≥ 7 days old. It hard-links or copies the two slot files, builds `search.sqlite`, writes gzip copies, computes SHA-256 of raw and gzip files, runs the self-tests, and writes `packs/mn/<packVersion>/`.
- It then replaces `packs/mn/manifest.json` with one `rename(2)`.
- Retention: the last 3 versions.
- The gateway serves `/packs/` statically:
  - Range 206 and a strong ETag
  - no `Content-Encoding`
  - `immutable` caching on version paths and `no-cache` on the manifest
  - a per-IP limit
  - no auth, no personal data
- A plain static host or CDN may serve the same tree.

**App.** A `PackManager` on WorkManager:
- `UNMETERED` by default. Mobile data only after an explicit confirmation (if the PO allows it).
- Range resume with `If-Range` at immutable URLs.
- Checks the compressed and installed SHA-256, then decompresses with streaming gzip.
- Self-tests: PMTiles header; a test route on the tar; SQLite `quick_check` and a test query.
- Storage pre-check with `getAllocatableBytes`.
- Installs into `noBackupFilesDir/packs/<version>.partial/` → rename → `active.json` (temp + rename).
- Consumers switch at safe points: search at once; map at the next style load; routing after guidance.
- A weekly refresh through a 24 h periodic check with `If-None-Match`, which skips files whose hash is unchanged.

The map uses the installed pack (`pmtiles://file://…/packs/<version>/basemap.pmtiles`); each version has its own path.

**Fallback order** (technical default, product choice in spike §10 Q1):
- map: from the pack when installed
- preview route and search: gateway first, then on-device on `Offline` / `Unavailable` / a 4 s first-byte timeout
- reroute: on-device first when a pack is installed

### 6. Contract draft (applied as openapi 0.6.0 only on acceptance)
```yaml
/packs/{region}/manifest.json:
  get:
    operationId: getOfflinePackManifest
    parameters: [region (enum [mn]), If-None-Match]
    responses:
      "200": { content: { application/json: { schema: OfflinePackManifest } }, headers: [ETag, Cache-Control: no-cache] }
      "304": Not modified
      "404": GatewayNotFound   # no pack published yet
/packs/{region}/{packVersion}/{file}:
  get:
    operationId: getOfflinePackFile
    parameters: [region, packVersion (^[0-9]{8}T[0-9]{6}Z$), file (enum [basemap.pmtiles.gz, routing.tar.gz, search.sqlite.gz]), Range, If-Range, If-None-Match]
    responses: { "200", "206" (Content-Range), "304", "404", "416" }   # headers as getBasemapPmtiles; Cache-Control immutable

OfflinePackManifest:
  required: [pack_schema, region, pack_version, published_at, osm_data_timestamp, attribution, licence, files, total_bytes, total_download_bytes]
  properties:
    pack_schema: { type: integer, const: 1 }
    region: { type: string, enum: [mn] }
    pack_version: { type: string, pattern: "^[0-9]{8}T[0-9]{6}Z$" }   # NAV-006 slot ID
    published_at: { type: string, format: date-time }
    osm_data_timestamp: { type: string, format: date-time }          # shown to the user as the map data date
    photon_data_timestamp: { type: string, format: date-time }
    attribution: { type: string }                                    # contains "OpenStreetMap"
    licence: { name: ODbL-1.0, url: <ODbL URL>, method_url: <public repository URL of the pipeline> }
    total_bytes: { type: integer }            # installed size
    total_download_bytes: { type: integer }
    files:
      type: array
      items:
        required: [kind, path, encoding, download_bytes, download_sha256, bytes, sha256]
        properties:
          kind: { enum: [tiles, routing, search] }
          path: { type: string }              # relative to /packs/{region}/, e.g. "20261004T193412Z/routing.tar.gz"
          encoding: { enum: [gzip] }
          download_bytes: { type: integer }
          download_sha256: { type: string, pattern: "^[0-9a-f]{64}$" }
          bytes: { type: integer }
          sha256: { type: string, pattern: "^[0-9a-f]{64}$" }
          format: { type: object }            # tiles: {pmtiles: 3, minzoom, maxzoom}; routing: {graph_builder: "valhalla 3.9.0"}; search: {search_schema: 1}
    self_test:
      route: { from: [lat, lon], to: [lat, lon], costing: auto }
      search: { q: string }
```

### 7. Licences
- ODbL: attribution «© OpenStreetMap contributors» on every map screen (rule 8), in the pack screen and in the licences screen.
- Packs are offered under ODbL, with no DRM.
- The method of making them (the public pipeline) and the files themselves are freely available, which meets ODbL §4.4 and §4.6.
- Library notices: `valhalla-mobile` and its Valhalla, protobuf, Boost and lz4 code (MIT, BSD-3, BSL-1.0, BSD-2), and `sqlite-bundled` (Apache-2.0). Nothing GPL ships.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Chosen: one weekly 3-file pack, `valhalla-mobile` on the server's graph, SQLite FTS5 search** | Measured sizes (202 MB / 119 MB gzip), 12/12 route parity, reuses ADR-0009 and ADR-0012 seams, all permissive | Third-party wrapper lags the server version (R1); about 0.5 GB per user per month of downloads |
| Own JNI build of upstream Valhalla | Same engine version as the server, no third-party wrapper | We own an NDK/vcpkg cross-build for 4 ABIs: +10–15 person-days plus upkeep |
| Graph rebuilt for the phone with the mobile engine's version | No reader-compatibility question | Measured route differences against the server (7/12 identical) |
| GraphHopper or OSRM on the device | Mature engines | Different engine and graph: no parity with server routes and NAV-005 fixtures |
| MapLibre `OfflineManager` regions | Built-in API | Per-tile HTTP, no file-level integrity or atomic version, slow for 442k tiles |
| Per-aimag packs | Smaller per user | The PO chose all of Mongolia; cross-region routing needs all graphs; more versions |
| Binary delta updates | Smaller weekly transfers | Not measured yet, more code; revisit after two weekly packs exist |
| Bundled in the APK / Play Asset Delivery | No download step | Rejected by the PO (download in the app); weekly data would need app releases; PAD excludes phones without Play (D62) |

## Consequences
- ADR-0001's "offline navigation is a later phase" no longer holds. An amendment records that offline is a launch requirement and points here.
- The server gains a pack-publish step, a `/packs/` static location and about 1 GB of retained packs (3 versions). The NAV-006 verify step gains a pack check with the mobile engine version.
- The app gains about 10 MB per ABI (installed), a pack manager, two new transports (`OnDeviceRouteRequester`, on-device search and reverse) and pack settings. Today's offline states («Интернэт холболт алга» …) remain for users without a pack.
- Valhalla upgrades on either side are coupled through the parity job (§4).
- Fewer coordinates leave the phone when the on-device paths are used (relevant to D9).
- iOS can reuse the same packs later with `valhalla-mobile` for Swift (not in scope).
- Follow-up work: spike §9 items 1–11.
