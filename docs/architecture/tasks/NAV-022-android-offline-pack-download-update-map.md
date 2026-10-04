# NAV-022 task breakdown: Android pack download, update and map from the pack (mobile)

- **Story:** [NAV-022](../../requirements/stories/NAV-022-android-offline-pack-download-update-map.md) (P1 / standard, D192; item 4 merged as section H; delivery slot 3, after NAV-021, D197)
- **Design:** [ADR-0017](../adr/0017-offline-mongolia-pack-android.md) §5, §6 and Amendment A1; ADR-0016 (`pmtiles://file://` with MapLibre 13.6.1, `MapStyle.resolve`)
- **Contract:** `openapi.yaml` **0.6.1**, `getOfflinePackManifest`, `getOfflinePackFile`. Read the 0.6.1 note: `pack_version` is **not monotonic** (server rollback), so decide by `sha256` per file
- **Owner of this file:** architect. **Date:** 2026-10-04. Task list only, no code
- **Coordination:** shared files with NAV-019 and the NAV-005 change (`NavApplication.kt`, `AppModule.kt`, `strings.xml`, Settings). Read first, make small edits. During development, the packs come from the NAV-020 test gateway (`127.0.0.1:18090` via `adb reverse`) or a local static server, **never** from the shared dev stack (AC 47)

## 1. Shared on-device format (binding for NAV-021 R4 and NAV-023)
`noBackupFilesDir/packs/active.json` (written to a temp file, then renamed; the commit point of every install):
```json
{ "schema": 1, "region": "mn", "manifest_etag": "\"…\"",
  "files": {
    "tiles":   { "version": "20260927T193105Z", "path": "20260927T193105Z/basemap.pmtiles", "bytes": 117536866,
                 "sha256": "…", "data_timestamp": "2026-09-26T20:21:03Z", "format": { "pmtiles": 3, "minzoom": 0, "maxzoom": 14 } },
    "routing": { "version": "20261004T193412Z", "path": "20261004T193412Z/routing.tar", "…": "…", "format": { "graph_builder": "valhalla 3.9.0" } },
    "search":  { "version": "20261004T193412Z", "path": "20261004T193412Z/search.sqlite", "…": "…", "format": { "search_schema": 1 } } } }
```
- A kind may be missing (not installed, or incompatible).
- Paths are relative to `packs/`.
- `routing` and `search` normally share a version directory; `tiles` may live in an older one.

**Install rule (clarifies AC 18):** verified files are written in `packs/<fileVersion>.partial/`.
- If `packs/<fileVersion>/` does **not** exist, the partial directory is renamed to it.
- If it **does** exist (for example a rollback manifest needs a routing file for a version whose tiles file is still installed), each verified file is renamed into it individually.
- In both cases `active.json` is the commit point. Files not referenced by `active.json` are garbage and are deleted at the next start, or when no consumer uses them.

## 2. Mobile task list
**P1. Manifest model and decisions.**
- Parse `OfflinePackManifest`, ignoring unknown fields.
- Reject unknown `pack_schema`. Skip a `routing` file whose `format.graph_builder` is not on the NAV-021 allow-list (R11). Skip a `search` file whose `search_schema` the app does not know.
- Files to fetch = compatible files whose `sha256` differs from `active.json`. Never compare `pack_version` (contract 0.6.1). An older `version` is downloaded like any update (AC 25).
- Required space = Σ `bytes` + the largest `download_bytes` + 50 MB.
- Size display rule: decimal, rounded up, «МБ» below 1,000 MB, «ГБ» with one decimal and a comma.
- Checks: JVM tests per rule (AC 5, 24, 25; Terms).

**P2. Pack store.**
- `noBackupFilesDir/packs/`, the §1 format and install rule.
- Reconcile at start: delete `*.partial` and unreferenced files within 10 s.
- Consumers hold a version (search: per query; map: the current style; routing: per guidance session, NAV-021 R3). Old files are deleted ≤ 60 s after the last consumer releases them.
- Checks: `kill -9` at 10 points over download, check and install. `active.json` only ever references complete files with matching SHA-256 (AC 18, 19, 20).

**P3. Downloader.**
- One file at a time. The URL comes from the manifest `path` relative to `/packs/mn/`.
- `Range: bytes=<have>-` with `If-Range: <ETag>`. 206 continues; 200 restarts the file.
- 404 → re-read the manifest and recompute the files to fetch. 429 → wait `Retry-After`. 416 → verify and restart on a mismatch.
- 5 consecutive network or 5xx failures in a user-started download → OF12 with «Дахин оролдох»; partial files are kept.
- Hash the bytes while they stream (`download_sha256`). On a no-space error: stop, delete partial files ≤ 5 s, show OF15.
- `User-Agent` = app name and version only.
- Checks:
  - MockWebServer cases: 200, 206, 304, 404, 416, 429
  - resume at about 50 % transfers ≤ `download_bytes` + 1 MB in total (test proxy)
  - (AC 7, 14, 15, 42)

**P4. Verify and install.**
- Check `download_sha256` → gunzip streaming to `bytes` with `sha256` → self-tests:
  - tiles: PMTiles v3 header, z0/z14, bounds contain P1–P6
  - routing: allow-list, plus `selfTest(self_test.route)` in the NAV-021 `:routing` service
  - search: `PRAGMA quick_check`, plus `self_test.search` ≥ 1 row (until NAV-023 lands, use the server self-test query semantics of the NAV-020 task file §3.6)
- Then install (§1).
- A failure deletes the version's partial files ≤ 5 s and keeps the installed pack.
- Checks: a flipped byte in `routing.tar.gz` and a valid gzip of a wrong file both fail without activation (AC 16, 17).

**P5. WorkManager jobs.**
- **User-started job:** long-running with `setForeground` (notification channel OF1, low importance, OF8 progress at least every 2 s, OF10 cancel action). Foreground service type `dataSync` (Android 14+): an install-time normal permission, so no runtime prompt (AC 41 "no new permission" holds). Constraint `UNMETERED`, or `CONNECTED` only for a download confirmed with OF13 or the 14-day offer.
- **Periodic job:** 24 h, `UNMETERED`, battery not low, storage not low. Manifest with `If-None-Match`; 304 → done. Fetch only installed kinds (AC 38). `routing` and `search` in one job. No dialog, no completion notification.
- Cancel: all transfers stop, 0 partial files, the state is as before, within 5 s.
- Survives reboot (WorkManager persistence).
- Checks: WorkManager test harness; AC 23 (48 h on a metered network → 0 file requests); AC 12, 13, 21, 22.

**P6. Network rules.**
- Use `NetworkStateSource` from NAV-021 R7. Wi-Fi = validated and not metered; mobile data = validated and metered (a hotspot marked metered counts as mobile data).
- The manifest may use any validated network. Pack files never use mobile data without OF13 or the 14-day offer.
- Checks: Robolectric network fakes; device OF13 path (AC 8–11).

**P7. First-launch offer.**
- After S1 has rendered, on Wi-Fi with no pack and no flag: fetch the manifest within 10 s and show OF2 / OF3 / OF6 / OF7 with «Татах» and «Дараа».
- The flag is set on any close, and also when the first start has no Wi-Fi. The flag is **not** set when the manifest fails to load (error, 404, 429, 10 s).
- An app update counts as a first launch (Open question 3, default (a)).
- Never during guidance.
- Checks: AC 1–3.

**P8. Settings section «Офлайн газрын зураг».**
- The three states of AC 4: data dates OF19 / OF20 (`data_timestamp` → Asia/Ulaanbaatar date `YYYY-MM-DD`), OF28 space used, OF16 only when there are files to fetch, OF21 delete, «© OpenStreetMap contributors», and OF29 opening `licence.url`.
- Checks: Robolectric UI tests (AC 4, 26, 39).

**P9. Dialogs.**
- OF13 (per download, never remembered; «Wi-Fi хүлээх» queues with OF9 and starts ≤ 30 s after Wi-Fi returns).
- The 14-day offer OF17 / OF18 when all AC 27 conditions hold. It covers routing and search only, never tiles. A decline is stored with its time and the offer is shown again after 7 × 24 h (D200). Not during guidance (Open question 1, default (a)). At most once per foreground session.
- Checks: JVM tests with a fake clock and network (AC 8, 9, 27–30).

**P10. Map from the pack (item 4).**
- With a `tiles` file installed, `MapStyle.resolve` substitutes `pmtiles://file://<packs>/<fileVersion>/basemap.pmtiles` (ADR-0016 §9). Online PMTiles stay the fallback when no tiles file is installed.
- Switching: not guiding → style reload ≤ 5 s, keeping camera and zoom. Guiding → at the first style load after guidance, or at the next app start.
- Attribution and the ESA credit are unchanged.
- Checks:
  - network capture: 0 `/tiles/basemap.pmtiles` requests in a 5-minute browse session
  - airplane mode at P1, Darkhan, Khovd, Choibalsan, Ölgii and Dalanzadgad: z14 renders ≤ 3 s
  - 0 MapLibre decode errors in the 60 s after a switch
  - (AC 31–35, 40)

**P11. Delete.**
- OF21 → OF22 → «Устгах».
- Not guiding: everything is gone ≤ 5 s and the map switches to online ≤ 5 s.
- Guiding: search is deleted at once; map and routing are deleted ≤ 10 s after guidance ends.
- The offer is not shown again. Periodic jobs download nothing.
- Checks: AC 36–38.

**P12. Notifications, strings, accessibility.**
- OF11 / OF12 for user-started downloads only. Progress keeps working with notifications denied.
- Every `mn` value is a glossary §2.7 term exactly (`needs native review` rows stay provisional). `en` values exist.
- TalkBack at 200 % font scale; 48 dp targets; progress announced at most every 10 s.
- Checks: AC 41, 44, 45.

**P13. Privacy.**
- Requests go only to the configured base URL. No coordinates or IDs.
- Stored state: only `active.json`, the offer flag, the decline time and WorkManager's own records.
- Checks: capture and log scan (AC 42, 43).

**P14. Debug base URL.**
- The packs base URL comes from a build property or an uncommitted `local.properties` key (loopback allowed in debug, no hostname or IP in the repo).
- Document the NAV-020 test gateway plus `adb reverse` in `mobile/android/README.md`.
- Checks: AC 47; repo scan.

**P15. Device checks** (with QA, AC 46): a full first install on Wi-Fi; the OF13 path; resume after airplane mode at about 50 %; the airplane-mode map; an offline route with NAV-021; delete.

**Order:** P1 → P2 → P3 → P4 → P5 → P6 → P10 (early: visible value, low risk) → P7 → P8 → P9 → P11 → P12 → P13 → P14 → P15. Estimate 12–17 person-days (spike: pack manager 10–14 + map 2–3).

## 3. Dependencies
| From | Needed |
|---|---|
| backend (NAV-020) | A published pack from the test setup (B17) with ≥ 2 publications (per-file versions); `/packs/` on the test gateway |
| mobile (NAV-021) | `NetworkStateSource`, the allow-list, `:routing` `selfTest` |
| ux-designer | Offer, Settings section, OF13, 14-day offer, progress, delete, data dates |
| business-analyst | Glossary §2.7 native review of OF1–OF29 |
| qa-engineer | MockWebServer fixtures, a corrupting test proxy, the kill-point list, the device checklist |
