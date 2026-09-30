# ADR-0006: Web search. Query assistance (Latin to Cyrillic, vowel fallback, district abbreviations) and request orchestration in the client, over the unchanged Photon pass-through

- **Status:** accepted (Phase 0, web demo). Amended 2026-09-30 for PO approvals D33 F2–F4: type-label rule 4a and Latin name endings (§2.6), the 200-character cap (§2.3, §6), and rule A confirmed. There is no wire change and no openapi change. Revisit when NAV-005 starts native search (see Consequences).
- **Date:** 2026-09-30
- **Stories:** NAV-003 (answers "the algorithm is the architect's and mobile-engineer's choice" in AC 15, and risks R1, R2, R3, R11). Affects NAV-005 (native search later).

## Context
NAV-003 adds search-as-you-type, a place card and a coordinate card (reverse) to the NAV-002 web demo. The contract already has everything it needs: `search` (`GET /v1/search`, Photon 1.3.0 `/api` pass-through) and `reverse` (`GET /v1/reverse`, Photon `/reverse`), including 429 `RateLimited` with `Retry-After` (openapi 0.4.0). The story expects **no backend change**, and the parallel NAV-008 work owns the gateway rate-limit, `real_ip` and header configuration. The shared dev stack must not be restarted.

Photon does not transliterate, and the index (ADR-0003) has `name:en` for only about 7.3k of 44k records, so the open question was where Latin input, Russian-layout vowels and district abbreviations («БЗД») are handled.

**Measured on 2026-09-30 against the running dev gateway** (`http://localhost:8080`, GraphHopper Mongolia dump, `lang=mn` unless stated, bias rounded to 3 decimals, limit 8, about 50 requests at ≤ 1.5 requests/s):

| # | Query (bias) | Result | Consequence |
|---|---|---|---|
| F1 | "Sukhbaatar", "suhbaatar", "Sükhbaatar" (P1) | «Сүхбаатар дүүрэг» #1, and «Сүхбаатарын талбай» #2 for the first and third. Photon matches Latin against `name:en`/other names, applies ICU folding (ü → u) and edit-distance fuzziness, and **returns the `mn` name** | A Latin query already works **where OSM has a Latin name**. The as-typed request must stay |
| F2 | "Ikh delguur" (P2) | 4 results, nearest 1.8 km; «Улсын их дэлгүүр» is **missing**. The Cyrillic «Их дэлгүүр» gives it #1 (374 m) | Places without a Latin name need a **Cyrillic transliteration request** |
| F3 | «Сухбаатар», «сухбаатар», «Сухбаатарын талбай», «Сухбаатар дуурэг» (у instead of ү) | **0 results, every time** (repeated). «Их дэлгуур» and «Баянзурх дүүрэг» do find the right places | The index does **not** fold ү→у / ө→о. Fuzziness rescues only some multi-word queries. A **vowel fallback** is needed for Russian-layout input |
| F4 | «БЗД 4-р хороо» as typed | «БЗД-ийн 4-р хороо» (khoroo office) #1 | OSM names **contain the abbreviations** («БЗД-ийн 4-р хороо», «БГД 1-р хороо», «СБД Ардчилсан нам») |
| F5 | «Баянзүрх дүүрэг 4-р хороо» (the AC 16 expansion of F4) | #1 is «Чингэлтэй дүүрэг 4-р хороо». No Bayanzürkh khoroo in the top 8 | Expansion **alone** loses F4's match. Expansion plus the as-typed query in the same budget gets both |
| F6 | «Баянзүрх дүүрэг», «Хан-Уул дүүрэг», «Чингэлтэй дүүрэг» | The district is #1 (`boundary=administrative` or `place=suburb`, `type=district`) | AC 16 expansion works for A9–A12 |
| F7 | Address fields in UB results | `district` = the **neighbourhood** (OSM `place=suburb`, for example «Бага Тойрог», «Гандан», «Цайз», «Дарь-Эх»). `city` = «Улаанбаатар». `county` absent. The düüreg is **not** in any field (it appears only when a suburb node happens to be named after it) | Answers R1: no better field exists in this dump. The AC 17 context rule stays. See Consequences |
| F8 | Khoroo objects | «1-р хороо», «4-р хороо» return only khoroo **offices** (`office=government`) and buildings, no khoroo boundary | R4 confirmed. Type-label rule 2 («… хороо») labels the offices «Хороо» |
| F9 | `extent` | `[minLon, maxLat, maxLon, minLat]`, for example «Сүхбаатар дүүрэг» `[106.86347, 48.197306, 107.03429, 47.9080639]` | Matches openapi. The client must reorder before `fitBounds` |
| F10 | Traditional script | In `name` («Сүхбаатар ᠰᠦᠬᠡ ᠪᠠᠭᠠᠲᠤᠷ», aimag), `state`, `county` and `country` («Монгол улс ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ») | AC 18 stripping is needed on every displayed field, including `name` |
| F11 | `reverse` P1 / X2 / P6 / P2 (`limit=1`, `radius=0.5`) | «Сүхбаатарын хөшөө» (1 m) / empty / «102-р цэцэрлэг» / **an unnamed `building=yes`** | The card needs the AC 17 name fallback. At P2 it will show «Газар» (see Open questions in the handoff) |
| F12 | Latency | Search 14–96 ms, reverse under 50 ms end to end on the dev machine | Two parallel requests per settled query fit AC 12 (1,000 ms) easily |
| F13 | Headers on the **running** dev gateway | `Access-Control-Expose-Headers: Content-Range, Content-Length, ETag, Accept-Ranges` (no `Retry-After` yet). The gateway source (`backend/gateway/snippets/cors-headers.conf`) already has it and takes effect when NAV-008 redeploys | The client must treat an unreadable `Retry-After` as missing (5 s fallback, AC 35). Local dev returns no 429 anyway |
| F14 | Staging limit (deployment-staging.md §7.1) | 30 requests/s per IP, burst 60, one zone for search + reverse | Worst case for this design is about 8 requests/s (2 per settled query at a 250 ms debounce), typically under 1/s |

## Decision

### 1. No backend change, no gateway wrapper, no new endpoint
The web client calls `search` and `reverse` directly, as specified in openapi 0.4.0 (0.4.1 adds only documentation of the facts above). No response shaping is needed: the client reads the GeoJSON as it is. A gateway wrapper would be justified only for auth, rate limits, caching or shaping (architect principle), and none applies.

### 2. Query assistance lives in `web/` as pure, unit-tested functions
Location: `web/src/search/`. It is plain TypeScript with no new runtime dependency (no fuzzy-search or transliteration library). Every function below is **pure** (no DOM, no fetch), so it is unit-tested with vitest against the vectors in §6 and can be ported to Kotlin and Swift later with the same vectors.

**2.1 Settled query** (story "Terms"): Unicode NFC, trim, collapse whitespace runs to one space, cut at 200 code units (the input also has `maxlength=200`). Fewer than 2 characters → no request.

**2.2 Coordinate input** (AC 26): `^(-?\d{1,2}(?:\.\d+)?)(?:\s*,\s*|\s+)(-?\d{1,3}(?:\.\d+)?)$` on the settled query, then range-check lat −90…90 and lon −180…180. A match gives one «Сонгосон цэг» option and **no request**. Anything else, including decimal commas and swapped pairs out of range, is text.

**2.3 Query plan.** Every settled text query produces a `QueryPlan { primary, secondary?, mode }`, where `mode` is `none`, `parallel` or `ifEmpty`. **At most 2 `search` requests per settled query**, always. Rules are applied in this order, and the first that matches wins:

| Rule | Condition on the settled query | `primary` | `secondary` | `mode` |
|---|---|---|---|---|
| A. Abbreviation | contains a whitespace-delimited token equal (case-insensitive) to «СБД», «БЗД», «ХУД», «БГД», «ЧД» or «СХД» | the query with each such token replaced by the full name (AC 16 table) | the query **as typed** | `parallel` |
| B. Latin | every letter is Latin script (including diacritics such as ö, ü) and there are ≥ 2 letters | the query as typed | `latinToCyrillic(query)` (§2.4) | `parallel` |
| C. Cyrillic with у/о | every letter is Cyrillic, and it contains at least one of у У о О | the query as typed | the query with every у→ү, У→Ү, о→ө, О→Ө | `ifEmpty` |
| D. Otherwise | mixed scripts, digits only, Cyrillic without у/о | the query as typed | none | `none` |

- A token attached to a suffix by a hyphen («БЗД-ийн») is **not** a separate word, so it is not expanded (it matches OSM names as typed, F4).
- `ifEmpty`: the secondary request is sent only after the primary returned **200 with zero features**, so a correct ү/ө query, or a correct у/о query, is never "corrected" (story edge case "Russian keyboard layout"). «Сүхбаатар» contains neither у nor о, so it sends exactly **1** request (AC 3).
- If `secondary` equals `primary` after normalisation, it is dropped (mode `none`).
- Rule A's second request and rule C are **technical choices within the story's "at most 2" budget**. The PO approved both on 2026-09-30 (D33 F3), and story AC 3, 15 and 16 now say the same thing. The switch stays one constant, `ABBREVIATION_SENDS_AS_TYPED = true`. Setting it to `false` would make rule A `none` with only the expanded query, which would need a new change request.
- **Length cap (D33 F4):** every planned `q` (primary and secondary, all rules) is at most **200** UTF-16 code units, the contract's `maxLength`. This is stricter than JSON Schema's code-point count, so it is always within the contract. The cut never splits a surrogate pair and then trims. Only rule A can make a query longer than the settled query (for example СХД adds 18 characters). An expansion that would go over 200 is **cut to 200**, not skipped, because the parallel as-typed request still carries the full text. The duplicate check (previous bullet) runs after the cut.

**2.4 `latinToCyrillic`** (normative, lower-case output; Photon is case-insensitive):
1. Lower-case. Map `ö ő` → `ө` and `ü ű` → `ү`. Strip every other combining diacritic (NFD, then remove U+0300–U+036F).
2. Split into words on spaces and hyphens, keeping the separators.
3. **Vowel harmony per word:** a word is *front* if it contains `e`, `ü` or `ö` and contains no `a`. Otherwise it is *back*. In front words `u` → `ү` and `o` → `ө`. In back words `u` → `у` and `o` → `о`.
4. Scan left to right, longest match first:
   - `shch` → щ
   - `kh` → х, `ts` → ц, `ch` → ч, `sh` → ш, `zh` → ж
   - `ya` → я, `yu` → ю, `yo` → ё, `ye` → е
   - `ii` → ий
   - `y` or `i` directly after a vowel and not followed by a vowel → й (for example `ai`/`ay` → ай, `ei` → эй, `oi` → ой)
   - singles: a→а b→б c→ц d→д e→э f→ф g→г h→х i→и j→ж k→к l→л m→м n→н p→п q→к r→р s→с t→т v→в w→в x→х z→з, any other `y` → ы, `u`/`o` per step 3
   - digits and punctuation unchanged
5. Known misses are accepted and counted in the tier B 80 % threshold (R3): compound words that break harmony (Sukhbaatar → «сухбаатар», Bayanzurkh → «баянзурх»; F1 shows the as-typed request finds both anyway), and short words without a harmony cue (Tov → «тов», not «төв»).

**2.5 Merging** (AC 8, AC 15): for `parallel`, interleave `p1, s1, p2, s2, …`, drop later duplicates by `osm_type` + `osm_id`, stable-partition `countrycode == "MN"` first, and keep the first **10**. For `ifEmpty`, the list is the secondary response (then MN-first, top 10). For `none`, it is the primary (MN-first, top 10). Each request sends `limit=8` (inside the story's 5–10).

**2.6 Display rules** (AC 17–20): implement exactly the story's name, context-line and type-label tables as pure functions. The type-label table has 33 ordered rules, 1–32 plus 4a (amended with D33 F2). Rules 1–4 match the Cyrillic **and** the Latin name endings from `lexicon.json`. The comparison is NFC and case-insensitive, and the ending must follow a space. Rule 4a (`osm_key=boundary`, `osm_value=administrative`, `type=district`) and rules 5–31 read only Photon properties that do not depend on `lang`. So the same OSM object gets the same row, and therefore the same zoom (§5), in both UI languages (F6, story R13). Photon's `type` comes from the OSM administrative rank, not from Mongolian administrative meaning. Rule 4a is therefore measured only for the UB düüregs, and tier C row C6 records what else it catches. Traditional Mongolian script is removed by deleting every run of characters in U+1800–U+18AF, **together with** adjacent U+202F (narrow no-break space, used inside Mongolian-script words), U+200C/U+200D and whitespace, then trimming and collapsing spaces (F10). Coordinates are shown as `lat.toFixed(5) + ", " + lon.toFixed(5)`, independent of locale.

### 3. Request orchestration (one controller, `web/src/search/searchController.ts`)
- **Debounce** 250 ms after the last input event. Enter skips the debounce (AC 41).
- **Generation counter plus `AbortController`:** every settled query, clear, selection or language switch increments the generation and aborts in-flight requests. A response whose generation is not current is discarded before it touches the DOM (AC 5). Identical settled query to the one shown → no request (AC 6).
- **Parameters:** `q`, `lang` (`mn` or `en`, the UI language), `limit=8`, and `lat`/`lon` of the bias point formatted with `toFixed(3)` (AC 9, 46). The bias point is the last device fix if my location is active, the camera is following and the fix is ≤ 60 s old. Otherwise it is `map.getCenter().wrap()` (world copies are on). Do not send `zoom`, `location_bias_scale`, `bbox`, `layer` or `osm_tag` in NAV-003: the Photon defaults gave the measured results (F1–F6).
- **Client timeout** 8 s per request (AC 33), implemented with its own `AbortController` so that a timeout is distinguishable from supersession.
- **Outcome classification per request:**

  | Outcome | Class |
  |---|---|
  | 200 with a parseable FeatureCollection | ok (possibly empty) |
  | 200 whose body is not JSON (for example a proxy HTML page) | unavailable |
  | 400, or any other 4xx except 429 | badRequest → «Алдаа гарлаа», no retry (AC 37) |
  | 429 | rateLimited; cooldown = `Retry-After` if it matches `^[1-9][0-9]*$`, otherwise **5 s** (also when the header is not readable, F13) |
  | 5xx, network `TypeError`, CORS failure, or the 8 s timeout, while `navigator.onLine` is true | unavailable → «Хайлт түр ажиллахгүй байна» (AC 33) |
  | `navigator.onLine` false before sending, or a network failure while offline | offline → «Интернэт холболт алга», **no request** (AC 34) |

- **Combining a `parallel` pair:** if either is rateLimited → rateLimited (drop both). Otherwise, if at least one is ok → merge the ok ones. Otherwise → the primary's class. For `ifEmpty`, the secondary's class replaces the primary's empty result.
- **Cooldown** per operation (`search` and `reverse` separately, as AC 35 and 36 specify): while it runs, nothing of that operation is sent and «Дахин оролдох» is `aria-disabled`. When it ends, **nothing is sent automatically**. The next settled query, or a press on «Дахин оролдох», sends. (The gateway uses one zone for both operations, so a 429 on one usually means the other is limited too. Keeping them separate follows the ACs and is safe because each operation honours its own 429.)
- **Online resume:** on the `online` event, if the list is in the offline state and the input holds a settled query of ≥ 2 characters, search it once, immediately (AC 34).
- **Language switch:** re-send the current settled query once with the new `lang` if the list is open (AC 38). Card labels switch; the card's place name is kept.
- **Loading:** `aria-busy="true"` plus «Ачаалж байна…» when a request of the current generation has been pending for > 300 ms (AC 13).
- **Nothing retries automatically** except the single online resume.

### 4. Reverse profile (AC 25–30)
One `reverse` request per coordinate card: `lat`/`lon` with `toFixed(6)` (the user chose this point, so no rounding to 3), `lang`, `limit=1`, `radius=0.5`. Same classification, timeout and cooldown rules as §3. Empty → «Илэрц олдсонгүй» in the nearest-place area. The pin and heading stay at the chosen point.

### 5. Camera and pin (AC 20–24)
- `extent` `[e0, e1, e2, e3]` = `[minLon, maxLat, maxLon, minLat]` → the camera for `[[e0, e3], [e2, e1]]` with `{ padding, maxZoom: 17 }` (`cameraForBounds` + `flyTo`/`jumpTo`, equivalent to `fitBounds`). Here `padding` is 40 px plus the insets of the UI that covers the map (top bar with the search box, the open place card), taken from the NAV-003 screen spec. If that padding leaves no room, 40 px on every side is used.
- No `extent` → centre on the point in the **viewport** at zoom 13 or 16 by type label, with **no** persistent camera padding (story AC 20 as written, NAV-003 screen spec › Camera rules; the layout keeps the viewport centre uncovered on all AC 23 viewports). `map.getCenter()` therefore equals the point, and QA can measure "centred ±5 px" either at the viewport centre or with `map.getCenter()`. *(Amended 2026-09-30 in the NAV-003 integration review: the first draft centred points in the padded visible area; UX and the implementation follow AC 20 instead. Centring in the uncovered area stays a possible change request for native apps.)*
- `prefers-reduced-motion: reduce` → no animation (`jumpTo`).
- Exactly one pin: a MapLibre `Marker` with a DOM element that has `role="img"` and an `aria-label`. It is replaced on a new selection and removed on close.
- Coordinate card triggers: the map `contextmenu` event (desktop). Long-press is its own touch handler: a 600 ms timer, cancelled by movement > 10 px or a second touch. Coordinates typed in the search box are the keyboard path (AC 26).

### 6. Test vectors (normative for the unit tests in `web/`, and later for NAV-005)
| Input (as typed) | Plan: `primary` | `secondary` | `mode` |
|---|---|---|---|
| «С» or "  " | — | — | no request |
| «  Сүхбаатар  » | «Сүхбаатар» | — | `none` |
| «Сухбаатар» | «Сухбаатар» | «Сүхбаатар» | `ifEmpty` |
| «Сухбаатар дуурэг» | same | «Сүхбаатар дүүрэг» | `ifEmpty` |
| "Sukhbaatar" | "Sukhbaatar" | «сухбаатар» | `parallel` |
| "suhbaatar" | "suhbaatar" | «сухбаатар» | `parallel` |
| "Sükhbaatar" | "Sükhbaatar" | «сүхбаатар» | `parallel` |
| "Ikh delguur" | "Ikh delguur" | «их дэлгүүр» | `parallel` |
| "Zaisan" / "Zaysan" | as typed | «зайсан» / «зайсан» | `parallel` |
| "Gandan" | "Gandan" | «гандан» | `parallel` |
| "Erdenet" | "Erdenet" | «эрдэнэт» | `parallel` |
| "Chingeltei" | "Chingeltei" | «чингэлтэй» | `parallel` |
| "Enkhtaivan" | "Enkhtaivan" | «энхтайван» | `parallel` |
| "Khan-Uul" | "Khan-Uul" | «хан-уул» | `parallel` |
| "Bayanzurkh" | "Bayanzurkh" | «баянзурх» | `parallel` |
| «БЗД» | «Баянзүрх дүүрэг» | «БЗД» | `parallel` |
| «худ» | «Хан-Уул дүүрэг» | «худ» | `parallel` |
| «БЗД 4-р хороо» | «Баянзүрх дүүрэг 4-р хороо» | «БЗД 4-р хороо» | `parallel` |
| «БЗД-ийн 4-р хороо» | as typed | «БЗД-ийн 4-р хөрөө» | `ifEmpty` (rule C; no expansion because the token is hyphen-attached) |
| «Улаанбаатар Sukhbaatar» (mixed) | as typed | — | `none` |
| "47.9189, 106.9176" / "47.9189 106.9176" | — | — | coordinate option, no request |
| "106.9176, 47.9189" / "47,9189, 106,9176" | as typed | — | text (`none`) |
| 192 × «а» + « СХД» (196 characters; the expansion would be 214) | the expansion cut to exactly 200 (… «Сонгино») | the query as typed (196) | `parallel` (D33 F4: no planned `q` over 200) |

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Client-side assistance over the unchanged pass-through (chosen)** | No backend or contract change during parallel NAV-008 work. Pure functions with shared vectors. Easy to tune in one place. Photon answers still arrive unchanged | Logic must be ported to Android/iOS (R11). Up to 2 requests per settled query |
| Photon import-time synonyms / custom analyser (vowel folding, abbreviation synonyms, transliterated names) | One place for all clients. No extra request | Changes the `photon-import` builder and the index (backend, forbidden in NAV-003). Photon's synonym support and analyser options need a spike on 1.3.0 (not verified here). A re-import is needed for every tweak |
| Gateway search wrapper (`/v1/search` does the transliteration and fan-out server-side) | One place for all clients. One round trip from phones | New service code in the gateway, which is under NAV-008 change right now. Breaks the pass-through principle for search. Photon JSON would need re-shaping or merging server-side |
| Latin transliteration sent **sequentially** (only when the as-typed result looks poor) | Fewer requests | No reliable "poor" signal (F2 returned 4 plausible but wrong results). Adds a round trip (about 100 ms from phones to staging, D26) |
| Always add the Cyrillic vowel variant in parallel | Simple | Doubles requests for most Cyrillic queries and can push wrong-vowel matches into correct queries. Breaks AC 3's "exactly 1" case |
| Expansion only for abbreviations (AC 16 as literally written) | 1 request | Loses OSM names that contain the abbreviation (F4/F5, golden row B15) |
| Client-side fuzzy/transliteration library | Less own code | New dependency for about 60 lines of table-driven code. Licence check. No Mongolian-specific library known |

## Consequences
- **No backend task for NAV-003.** `openapi.yaml` 0.4.1 documents the measured behaviour (no transliteration, no ү/у folding, `district` is the neighbourhood, unnamed reverse results, unreadable `Retry-After` handling) with no wire change.
- **R11 (client duplication):** the rules in §2 and the vectors in §6 are the spec for NAV-005. When native search starts, decide (new ADR) between (a) porting the pure functions with the same vectors and (b) moving assistance server-side (Photon synonyms or a gateway endpoint). A spike on Photon 1.3.0 synonym and analyser support should come first, raised through triage, not in NAV-003.
- **R1 (düüreg in the context line):** not solvable from this dump's address fields (F7). Candidate fixes for NAV-006: (a) our own Nominatim import (ADR-0003 production direction) with a check of whether Photon then exposes the düüreg (for example as `district` or `county`), or (b) build-time enrichment of the dump with the düüreg from the district boundaries. Both are backend and data work, so they go through triage as a follow-up.
- **R4 (khoroo boundaries missing):** OSM data issue. Mapping tasks go through triage lane `osm-data` for human mappers only.
- Rate budget: at most 2 search requests per settled query and 1 reverse per card, well under the staging 30 requests/s per IP (F14), including several testers behind one carrier NAT.
- **Golden row A8 / AC 10 (architect's note, 2026-09-30 integration review, recorded here on the BA's request):** F2 measured the store «Улсын их дэлгүүр» at 374–375 m from P2. So the ≤ 300 m condition passes today only through the bus stop «Наран их дэлгүүр» (268 m, QA test plan §6.1). The earlier review flagged this but did **not** file a change request. The architect recommends story Open question 7 option (b): the name contains «Улсын их дэлгүүр», ≤ 400 m, top 3, for both A8 and AC 10. This is a tier A change, so the PO decides it.
- Privacy: bias coordinates are rounded to 3 decimals. Query text and coordinates are never written to storage or the console. The gateway logs paths only (ADR-0002 §3.4), and Photon logs no queries at INFO (backend README). The D9 legal review still gates outside testers on staging.
