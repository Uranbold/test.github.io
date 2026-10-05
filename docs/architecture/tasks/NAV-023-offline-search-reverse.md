# NAV-023 task breakdown: offline search and reverse geocoding (mobile, plus builder follow-up on the backend)

- **Story:** [NAV-023](../../requirements/stories/NAV-023-offline-search-reverse.md) (P1 / standard, D191; delivery slot 4, after NAV-022, D197)
- **Design:** [ADR-0017](../adr/0017-offline-mongolia-pack-android.md) §3, §5 and Amendment A1; ADR-0012 (Android query plan, `PhotonFeature`); ADR-0006 (query assistance). Builder v1 spec: [NAV-020 task file §3](NAV-020-offline-pack-build-publication.md#3-searchsqlite-builder-v1-production-spec-port-of-spike-24)
- **Contract:** no change (`search`, `reverse` as today; the file comes from the pack)
- **Owner of this file:** architect. **Date:** 2026-10-04 (review notes 2026-10-05, ADR-0017 A4). Task list only, no code

## Boundary
- **Builder v1** (NAV-020 B3) already implements NAV-023 AC 1–2 (schema 1: `place`, `place_fts` with folded names, skeletons and joined words, `place_tri`, `vocab`, `place_geo`, `meta`).
- This story owns the rules and the vectors. If the engine needs stored data that v1 lacks, the backend adds it here and increments `search_schema` when stored keys change. The app installs only schemas it knows (NAV-022 P1).

## Backend tasks (small)
**SB1. Shared vectors.**
- The builder tests read the shared vector file instead of the inline story groups. **Location (2026-10-05, ADR-0017 A4 item 3):** `backend/pack/vectors/search-normalisation.v1.json`, read by both suites. QA adopts or reviews it; the BA aligns the AC 3 wording.
- Checks: all AC 3 groups give one skeleton each, in both the builder and the Android tests (AC 3, 30).

**SB2. Schema additions, only if the engine needs them** (for example a precomputed deletion index for edit-distance expansion, or a small category synonym table).
- Each addition is listed in this file before it is built. Increment `search_schema` if stored keys change.
- Checks: the NAV-020 AC 12 self-test and the NAV-023 AC 4 budgets still hold (≤ 30 MB, ≤ 10 MB gzip, ≤ 5 min).
- **Recorded 2026-10-05 (builder v2, `search_schema` stays 1 by the one-time exception of ADR-0017 A4 item 1):**
  1. context values from `<key>:mn` first, else the base key (NAV-020 task file §3.3 item 5)
  2. new `place.locality` column from `address.neighbourhood`, mapped to `PhotonFeature.locality` (suburb stays `district`)
  3. `vocab` also holds the skeleton joined-word keys
  4. `PACK_SEARCH_BUILDER_VERSION` defaults to 2
  - Measured on the dev dump (2026-09-26): 43,892 rows, 53,888 vocab terms, 24.2 MB raw, 8.6 MB gzip, 8.7–10.2 s, two builds with the same SHA-256.
  - Follow-up (minor): store the skeleton joined-word key as `skeleton(w1 + w2)` (doubled boundary letters collapsed: «Баян нуур» → `baianur`, as typed «Баяннуур») instead of the concatenation of the two skeletons (`baiannur`). The AC 2 step order stays as written. Do this before the first published pack, and append a `joined_pairs` vector.

**SB3. Determinism check (AC 5).** Two builds of one dump give the same SHA-256 (already a NAV-020 B3 check; reported here as well).

## Mobile task list
**SM1. Engine host.**
- `androidx.sqlite:sqlite-bundled` (`BundledSQLiteDriver`, FTS5 and R*Tree) in the main process.
- Open the file read-only, one connection per installed search version. A query holds its version until it finishes (NAV-022 P2).
- Check `meta.search_schema`.
- JVM unit tests use the same driver's JVM artefact, so the gate harness runs the real engine code. *(Done as: the Android `BundledSQLiteDriver` class with the host native library of the same 2.5.2 release, which bundles SQLite 3.46.0; ADR-0017 A4 item 4.)*
- Checks: AC 6, 10.

**SM2. Normalisation.**
- `fold` and `skeleton` exactly as AC 2. Unit tests read **QA's shared vector file** (the same file as the builder).
- Checks: AC 3.

**SM3. Query plan (ADR-0012, unchanged).**
1. The coordinate parser runs first: typed coordinates send 0 queries to the file and 0 requests (AC 8).
2. ADR-0006 abbreviation expansion, with a ranking bonus.
3. Latin and vowel variants. These must cover Latin «ө»/«ү» written as "u" ("Khuvsgul", "Ulgii"), which the skeleton alone does not join (NAV-020 task file §3.6).
4. FTS5 match on `names` and `skel`, with a prefix match on the last token.
5. With 0 hits, edit-distance expansion over `vocab` (1 for 5–8 letters, 2 for ≥ 9), using a SymSpell-style deletion index or a BK-tree built lazily and cached per file version.
6. Then the `place_tri` trigram fallback.

Ranking: exact or prefix name match, importance, distance to the bias point, bm25. Results of an older query never replace a newer one; the NAV-011 debounce is kept.
- **Reference (2026-10-05, ADR-0017 A4 item 2):** `backend/pack/search_engine.py` `RANKING_VERSION` 1 is the single ranking, and the Kotlin engine is a port of it. An app-only step (today the Latin «u»→«o» variant at 0 hits) must also be added to the reference before the next held-out report.
- Checks: JVM tests; AC 6, 9.

**SM4. `PhotonFeature` mapping.**
- A `place` row maps to the online model: name, `osm_type`/`osm_id`, `osm_key`/`osm_value`/`type`, street, housenumber, postcode, district/suburb, city, county, state, extent.
- The list, the type labels, the card and «Маршрут гаргах» stay unchanged.
- Checks: AC 7.

**SM5. Reverse.**
- `place_geo` with an expanding box returns the nearest **named** object. It is shown under «Ойролцоох газар» exactly as an online result.
- Checks: P1–P6 against online on the same slot: the same object or one within 50 m (AC 20); p95 ≤ 100 ms on device (AC 9).

**SM6. Fallback.**
- Reuse NAV-021's `OnlineFirstPolicy` and `NetworkStateSource` for `search` and `reverse`: the 3.0 s header budget, stickiness, the 429 window, and the 8 s body timeout after headers.
- A 200 with 0 results and a 400 are authoritative.
- Checks: JVM tests with a fake clock and mock server; device ≤ 3.2 s (AC 11–15).

**SM7. Bias.** The same bias point as online (D30 / D174 rule) (AC 16).

**SM8. Offline indicator** (after the UX spec; OF24 / OF25, `needs native review`).
- Shown once on an on-device results list (including «Илэрц олдсонгүй»), on the place card, and next to «Ойролцоох газар» on the coordinate card (Open question 1, default (a)).
- TalkBack: «{count} илэрц олдлоо» followed by OF25.
- Never covers the field, names, «Маршрут гаргах» or the attribution. Contrast ≥ 4.5:1.
- Checks: AC 21–24, 29.

**SM9. States.**
- A damaged file → «Хайлт түр ажиллахгүй байна» with «Дахин оролдох», 0 crashes, logged without query text or coordinates.
- Without a file, NAV-011 is unchanged.
- Checks: AC 25, 26, 27 (web untouched).

**SM10. Gate harness (with QA).**
- A JVM harness runs the Kotlin engine on the published file over the NAV-003 golden set: rows A1–A14, A16 and B1–B15, with type labels evaluated. Rows that also fail online on the same slot are excluded. The gate passes when `passed × 10 ≥ counted × 9`.
- A device spot check of ≥ 10 rows.
- The held-out report (≥ 30 queries, written by QA after the freeze, not tuned on) is **report-only** (D198).
- Checks: AC 17–19.

**SM11. Device timing.** On the NAV-021 benchmark phones: search p95 ≤ 150 ms (mid-range) / ≤ 400 ms (low-end); reverse ≤ 100 ms (AC 9).

**SM12. Privacy and strings.** 0 query texts and 0 coordinates in logs and storage; 0 requests while answers come from the device offline; glossary check (AC 28, 29).

**Order:** SM1 → SM2 → SM4 → SM3 → SM5 → SM6 → SM7 → SM10 (freeze the ranking, then QA writes the held-out set) → SM8 (after UX) → SM9 → SM11 → SM12. Estimate 7–10 person-days mobile (spike 6–9) + 1–2 backend.

## Dependencies
| From | Needed |
|---|---|
| qa-engineer | The shared skeleton vector file; the gate harness inputs; the held-out set **after** the freeze |
| business-analyst | An AC 3 vector group for Latin «ө»/«ү» as "u", or a statement that query-side variants cover it |
| mobile (NAV-021, NAV-022) | `OnlineFirstPolicy`, `NetworkStateSource`, the installed search file and version holding |
| ux-designer | The indicator spec for the list, the card and the coordinate card |
