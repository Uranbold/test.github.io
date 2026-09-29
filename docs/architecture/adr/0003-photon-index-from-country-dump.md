# ADR-0003: Build the Photon index from the GraphHopper Mongolia JSON dump (Nominatim deferred)

- **Status:** accepted (dev / Phase 0). Revisit for production in NAV-006.
- **Date:** 2026-09-29
- **Stories:** NAV-001 (answers its Open questions 1 and 2), affects NAV-003

## Context
Photon cannot read an OSM PBF. It builds its index either from a **Nominatim database** or from a **Photon JSON dump**. NAV-001 needs search-as-you-type and reverse geocoding for UB, with `lang=mn` accepted (AC 27), within 30 minutes of first-run build and 10 GB of `data/` on the reference machine.

Facts checked on 2026-09-29:
- **Photon 1.3.0** (current release) has `import -import-file <dump|->`, `-languages`, `-country-codes` and `dump-nominatim-db`.
- **GraphHopper publishes a weekly per-country Photon dump**: `https://download1.graphhopper.com/public/asia/mongolia/photon-dump-mongolia-1.0-latest.jsonl.zst` (8.1 MB). It is marked compatible with Photon 0.7.x–1.x, has `data_timestamp` 2026-09-26, and holds 44,251 records. It is produced from a full-planet Nominatim, so address parts (district, city, country) are complete. Names include `name` (39k), `name:mn` (11.8k), `name:en` (7.3k) and `name:ru` (1.3k).
- Measured in this container: `photon import -languages mn,en,ru` from that dump took **31 s** and produced a **27 MB** index. `photon serve` then gave:
  - `/api?q=Сүхб&lat=47.9189&lon=106.9176` → "Сүхбаатарын талбай" as the first hit, 200
  - `/api?q=Sukhbaatar&lang=en` → "Sukhbaatar Square", 200
  - `/api?q=xqzjwvk` → empty FeatureCollection, 200
  - `/api?lang=de` → 400 "Language is not supported. Supported are: default, mn, ru, en"
  - `/reverse` at P1 → "Сүхбаатарын хөшөө", 200
  - `/reverse` at Beijing → empty, 200
- A **Nominatim import from the BBBike UB extract** would be internally consistent with the tiles and graph, but:
  - the extract is a bounding-box clip, so the country, aimag and city boundary relations are incomplete, and Nominatim's address hierarchy (city, district) would be broken or missing for most places
  - it needs PostgreSQL/PostGIS + osm2pgsql + Nominatim, and the common images (`mediagis/nominatim`, `postgis/postgis`) are on Docker Hub, which returns 429 here
  - it adds a GPL server component and a database to the PoC

## Decision
1. **Dev and Phase 0:** the `photon-import` builder imports the **GraphHopper Mongolia country dump**. Keys:
   - `PHOTON_DUMP_URL` (default: the URL above)
   - `PHOTON_DUMP_FILE` (optional local file, which wins over the URL and prevents any request to it)
   - `PHOTON_LANGUAGES` (default `mn,en,ru`)
   - `photon serve` runs with `-default-language mn` and `-max-results 20`
2. **Nominatim is not part of NAV-001.** Reverse geocoding uses Photon `/reverse` (NAV-001 Open question 1 → option a). Structured search and admin lookups wait for the story that needs them.
3. The dump's `data_timestamp` and URL (or `file:` path) are written to `data/build-info.json` next to the OSM PBF date. Search results are **not** guaranteed to match the PBF used for tiles and routing to the day.
4. **Production direction (to confirm in NAV-006, not decided here):** run our own Nominatim import from the same Geofabrik `mongolia-latest` PBF. Then either run `photon dump-nominatim-db` for a consistent dump, or use Photon's Nominatim update mode for daily updates. That brings back the GPL server-side component already accepted in ADR-0001.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **GraphHopper country dump (chosen for dev)** | 8 MB download, 31 s import, 27 MB index. Correct address hierarchy. Has `name:mn`. No database, no GPL component | Data date differs from the PBF (weekly, up to about a week apart). Covers all of Mongolia while dev routing and tiles cover only UB. Third-party availability. No update stream |
| Own Nominatim from the UB PBF | Same data as tiles and routing. Enables structured search and admin lookups | Bbox-clipped boundaries break addresses. Docker Hub images unavailable here. PostGIS plus GPL. More moving parts in the PoC |
| Own Nominatim from Geofabrik `mongolia-latest` | Consistent data in production. Full boundaries | Geofabrik is blocked in this container. Heavier build. Needed later, not now |
| GraphHopper planet / Asia DB dumps | Official | Tens of GB. Wrong scale for dev |

## Consequences
- NAV-001 AC 5 ("rebuilt from the new source") holds for tiles and routing. For Photon, the force-rebuild command re-imports from `PHOTON_DUMP_URL`/`PHOTON_DUMP_FILE`, which is a separate key and **not** derived from `OSM_PBF_URL`. The backend README must say this plainly (raised in the architect handoff as a non-blocking question).
- *Updated 2026-09-29 (ADR-0002 Amendment 1):* the default dev PBF is now all of Mongolia, so the dump and the routing and tiles data cover the same area, and Erdenet is routable. Only on the optional BBBike UB build can search return places that routing cannot reach, such as Erdenet. The route call then returns 400 `NoSegment`, which is the expected out-of-coverage behaviour (NAV-001 AC 32). The data-date difference between the dump and the PBF remains.
- `lang` accepts `mn`, `en`, `ru` and `default`. Any other value returns **400**, not a fallback. Clients send `lang` explicitly (the UI language), because otherwise Photon uses the browser's `Accept-Language`.
- Some OSM `name` values contain traditional Mongolian script after the Cyrillic (for example country "Монгол улс ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ"). NAV-003 UX must decide how to display address lines. Noted for the UX designer.
- If GraphHopper stops publishing the country dump, the fallback is option 2 or 3, and only the `photon-import` builder changes. The HTTP contract does not change.
