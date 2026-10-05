# ADR-0012: Android route preview and search parity. ADR-0006 query assistance ported to Kotlin with one shared vector fixture, reverse on the coordinate card, alternatives with a single-route hand-off to guidance, and an on-device typing lock

- **Status:** accepted (Phase 1, Android). Closes ADR-0006 R11 for Android, with option (a), the Kotlin port. The Photon synonym spike is deferred, not a prerequisite (see §1).
- **Date:** 2026-10-03. Amended 2026-10-03 (integration review) and 2026-10-04 (D140: typed coordinates open the coordinate card, §3 and the last amendment)
- **Stories:** NAV-011 (AC 1–47). Touches NAV-005 AC 3–6 on Android, as the NAV-011 Context table lists. Shares the vector fixture with NAV-003 (web). Keeps NAV-012 (parallel, `mobile/android`) out of NAV-011's packages.

## Context
NAV-011 brings the verified web behaviour (NAV-003 query assistance and reverse, NAV-004 alternatives and «Дугуй») to the Android app built in NAV-005 (ADR-0009), and adds the D65 typing lock. ADR-0006 R11 asked for a new ADR before native search assistance is built: either (a) port the ADR-0006 pure functions with the same vectors, or (b) move assistance server-side (Photon synonyms/analyser or a gateway endpoint). It recommended a Photon 1.3.0 synonym spike first.

Forces:
- **Contract and backend.** `openapi.yaml` 0.5.2 already contracts everything NAV-011 needs: `search` (`q` ≤ 200, `lang`, `lat`/`lon`, `limit`), `reverse` (`lat`, `lon`, `lang`, `limit`, `radius`), and `postRoute` with `alternates` 0–2, `costing` `bicycle` and `auto.exclude_unpaved`. Option (a) needs **no** wire change.
- **Parallel work.** NAV-012 is editing `mobile/android` right now. The shared dev stack must not be restarted, and a Photon re-import (needed for option (b) synonyms) is a restart-class operation on the search index.
- **The web is frozen for this story.** NAV-003 and NAV-004 behaviour must not change (NAV-011 AC 41, Out of scope).
- **Existing Android code (NAV-005).** `search/` already has the request profile (`SearchClient`: debounce 250 ms, bias rounded to 3 decimals, 8 s `callTimeout`, outcome classes) and the display rules (`PlaceDisplay`: name, context line, D34/D45 type labels, traditional-script stripping). `route/RouteRequest` builds a fixed body with `alternates: 0`. `preview/PreviewController` holds one route. ADR-0009 §7 has already set a pattern for a fixture shared between `web/` and `mobile/` (a Gradle `Sync` copy).
- **PO go-ahead (2026-10-03).** The PO said "go ahead" and asked to skip the extra step, which here is the separate synonym spike before this decision. So this ADR decides without the spike's result and records how to revisit.
- **Dev stack during this design:** `http://127.0.0.1:8080/health` did not answer (connection refused, retried for about 60 s). Nothing in this ADR was measured live. The facts used come from ADR-0006 (F1–F14), ADR-0009 and openapi 0.5.0–0.5.2 measurements.

## Decision

### 1. Option (a): port ADR-0006 §2.1–§2.5 to Kotlin. No server-side assistance in NAV-011
- The Android app gets pure Kotlin equivalents of `settle`, the coordinate rule, `planQuery` (rules A–D, `ABBREVIATION_SENDS_AS_TYPED = true`, the 200-code-unit cap), `latinToCyrillic` and `merge`. The rules are exactly ADR-0006 §2.1–§2.5. Nothing is re-specified here.
- **No `openapi.yaml` wire change, no gateway, Photon or index change, no backend task.** NAV-003 web code and behaviour stay unchanged.
- **Synonym spike: deferred, not cancelled.** Revisit trigger: **before NAV-015 (iOS) builds search.** A third port is the point where server-side assistance may pay off. The spike then goes through triage as ADR-0006 R11 asked. If it later picks server-side, a new ADR supersedes §1 for all clients together. The shared fixture (§2) becomes that service's acceptance test, so nothing done here is wasted.

**Port rules that keep Kotlin identical to TypeScript** (these are the usual sources of drift):
- **Script classes per code point, not per `Char`.** "Letter" = `Character.isLetter(cp)`. "Latin" = `Character.UnicodeScript.of(cp) == LATIN`. "Cyrillic" = `… == CYRILLIC`. Iterate with `codePoints()`. These are the Java equivalents of the web's `\p{L}`, `\p{Script=Latin}` and `\p{Script=Cyrillic}` with the `u` flag.
- **Case and normalisation:** `lowercase(Locale.ROOT)` / `uppercase(Locale.ROOT)` (never the default locale), and `java.text.Normalizer` NFC/NFD. Abbreviation matching is case-insensitive with `Locale.ROOT`.
- **Length cap:** Kotlin `String.length` counts UTF-16 code units, like JS. Cut at 200 without splitting a surrogate pair (`Character.isHighSurrogate` on index 199), then trim. Same order as the web: cap first, then the duplicate check.
- **Whitespace:** the web's `normalizeQuery` uses JS `/\s+/gu`. On the JVM, `Regex("\\s+")` matches ASCII whitespace only, and `(?U)\s` differs from JS at U+0085 and U+FEFF. So the port uses an explicit class equal to the JS set: `[\t\n\u000B\f\r    -     　﻿]+`. The current NAV-005 `SearchController.settle` (ASCII `\s+`, a plain `take(200)` that can split a surrogate pair) is replaced by the ported `Settle`. Fixture rows (§2) cover NBSP, U+202F and a surrogate pair at position 200.
- **Merge:** interleave `p1, s1, p2, s2, …`, drop duplicates by `osm_type` + `osm_id`, stable partition `countrycode == "MN"` first, keep 10. `limit=8` per request (unchanged from NAV-005).

### 2. One shared vector fixture, read by both test suites
- **Location:** `web/src/search/queryPlan.vectors.json`, next to `maneuvers.fixture.json` (ADR-0009 §7 pattern; the mobile engineer owns both `web/` and `mobile/`).
- **Content (version 1):** settle rows `{ "raw", "settled" }` (NBSP, U+202F, tabs, a surrogate pair straddling position 200), every ADR-0006 §6 row, as `{ "input", "kind": "skip"|"coordinate"|"text", "primary", "secondary", "mode" }`, plus `latinToCyrillic` rows `{ "latin", "cyrillic" }` for every §2.4 rule (digraphs, `ii`, vowel-then-`y/i` → й, harmony front/back, diacritics) and `merge` rows (`parallel` interleave with a duplicate, MN-first partition, cut at 10, `ifEmpty`). A top-level `"version": 1` and `"source": "ADR-0006 §6"`.
- **Web:** one **new** vitest file (for example `web/src/search/queryPlan.vectors.test.ts`) reads the JSON and asserts `planQuery` / `latinToCyrillic` / `merge` for 100 % of the rows. **The existing `queryPlan.test.ts`, `text.test.ts` and every other web test stay byte for byte unchanged**, and no web source file changes. This is how this ADR reads NAV-011 AC 5 and AC 41 ("`npm test` passes unchanged" = no existing web assertion or behaviour changes; adding one test file that reads the fixture is allowed and required).
- **Android:** a Gradle `Sync` task copies that one file into the generated test-resources directory of `:app`, in the same way as `maneuvers.fixture.json`. The JVM test fails if the file is missing, has `version` ≠ 1, or has 0 rows in any section, and asserts 100 % of the rows (AC 5).
- **Change rule:** any change to the fixture keeps both suites green in the same commit (ADR-0009 §7 rule).

### 3. Package layout and the request orchestration on Android
New code goes into NAV-011-owned packages (story Coordination, AC 42). The names are recommendations, and the mobile engineer may rename them:

| Package | Contents | Android types? |
|---|---|---|
| `mn.navmn.app.search.assist` | `Settle`, `CoordinateInput`, `QueryPlan` / `planQuery`, `LatinToCyrillic`, `Merge`, `CombineOutcomes` (pair rules) | no (pure JVM) |
| `mn.navmn.app.search.reverse` | `ReverseClient`, `ReverseController`, `ReverseView` states | OkHttp only |
| `mn.navmn.app.route.alternatives` | `RouteOptions` (parse k routes), `SingleRouteSlice` (§5.3), `RouteSelection`, `AlternativeHitTest` | no, except the map adapter |
| `mn.navmn.app.typinglock` | `FixSpeed`, `TypingLockRule` (pure, fake clock), `LockFixSource` (LocationManager adapter), `PassengerOverride` | only `LockFixSource` |

Edits to existing NAV-005 files are small and targeted: `SearchController` (plan → 0, 1 or 2 calls; 0 for a typed coordinate, D140), `RouteRequest`/`TravelMode` (§5.1, §6), `PreviewController` (k routes, selection), the screens, `AppViewModel` wiring and `strings.xml` in `values/` and `values-en/`. Shared files (`MainActivity`, `strings.xml`, `Settings`, the navigation service) are re-read before every edit. Lines added by NAV-012 are never removed (AC 42).

**Search orchestration** (AC 2–4, 7). The ADR-0006 §3 rules are reused as they are, applied to the existing NAV-005 `SearchController`:
- Each settled query gets a generation number. The controller cancels the coroutine `Job` of the previous generation (which cancels the OkHttp calls) and discards any result whose generation is stale before it touches UI state.
- `parallel`: two `async` calls on the existing `SearchClient`. `ifEmpty`: the secondary call is sent only after the primary returns `Ok(empty)`. `none`: one call.
- Pair combination (ADR-0006 §3): if either result is `RateLimited`, the outcome is `RateLimited`. Otherwise, if any is `Ok`, the `Ok` results are merged. Otherwise the outcome is the primary's class. This is a pure function with a unit test.
- Debounce, `lang`, D30 bias, 8 s timeout, 429 cooldown and offline handling are unchanged from NAV-005 (AC 7).
- **Coordinate input** ("47.9189, 106.9176"; amended 2026-10-04, **D140**, which supersedes the "sent as typed / no card" part of D115): the pure plan returns `Coordinate` (fixture parity, unchanged rule including integer pairs, D143). The Android controller now handles it **as the web `searchController.ts` `settle()` does** (NAV-003 AC 26):
  - **0 `search` requests.** The controller emits the request-free list state `SearchView.Coordinate(point)`, which the UI renders as one «Сонгосон цэг» option. A recognised pair never reaches `q` (NAV-011 AC 39).
  - **The coordinate check runs before the offline and 429 cooldown checks**, so the option also shows offline and during a `search` cooldown. Retry and a network resume send 0 requests for it.
  - **Selecting the option opens the §4 `ReverseController` card**, after a camera centre (D142). The field closes as after a normal result (D146).
  - Pairs the rule rejects (out of range such as "106.9176, 47.9189", decimal comma) are ordinary text: planned and sent as typed.
  - The exact state, run order and the selection API that the NAV-018 fields reuse are in **Amendment 2026-10-04 (D140)** at the end of this ADR.
- The field always shows what the user typed (AC 4). Planned variants never reach the UI.

### 4. Reverse on the coordinate card (AC 8–13)
- `ReverseClient` reuses the NAV-005 `SearchClient` HTTP stack and outcome classification (same OkHttp instance, 8 s `callTimeout`, no cache). Request: `GET {gateway}/v1/reverse?lat=…&lon=…&lang=mn|en&limit=1&radius=0.5`. `lat`/`lon` are formatted with **6 decimals, `Locale.ROOT`** (ADR-0006 §4: the user chose the point, so it is not rounded to 3).
- **One `ReverseController` per card instance**, with a generation counter. A new long-press, a selected typed-coordinate option (§3, D140) or closing the card cancels the `Job` and bumps the generation, so at most 1 request is in flight (AC 12). Nothing else calls `reverse`: not pans, zooms, the device position or search results. The typed-coordinate option is not a search result: it opens a card, and that card sends its one `reverse` at the typed point (6 decimals, not rounded).
- Offline: `NetworkMonitor` reports no validated network, so 0 requests are sent and the offline state shows. On the transition to validated while the card is open and in the offline state, exactly 1 request is sent (AC 11, ≤ 2 s).
- 429 sets a cooldown for `reverse` only, separate from `search` (ADR-0006 §3, AC 11). Nothing is sent automatically when it ends.
- **Language switch:** the card keeps the parsed `PhotonFeature`. Labels and the type label are recomputed from resources and the stored properties. The type-label rules read only `lang`-independent properties (ADR-0006 §2.6), so they give the same row. The name already shown is kept. 0 requests (AC 13).
- The display uses the existing `PlaceDisplay` (name fallback, context line, D34/D45 labels). The route-preview destination text stays «Сонгосон цэг» (AC 9).
- **Rotation / configuration change:** the card state lives in the ViewModel, so a rotation sends no new request.

### 5. Alternatives in the preview and the hand-off to guidance (AC 14–21)
**5.1 Request.** `RouteRequest` gets an explicit purpose: `Preview` → `alternates: 2`, `Reroute` → `alternates: 0`. Every other field is the NAV-005 fixed set (ADR-0009 §2), including `voice_instructions: true` and `banner_instructions: true`, so the selected route can be navigated with **0** further requests (AC 19). A unit test asserts the reroute body still has `alternates: 0` (NAV-005 AC 43). At most 1 preview request is in flight, with a generation counter (AC 14).

**5.2 Parse for preview.** From the 200 body: k = `routes.size` (1–3). For each route, keep its index, decoded `geometry` (polyline6), `distance`, `duration`, and the shared `waypoints[].distance` for the D51 snap notice. Route 1 = `routes[0]` is selected. The full `GuidancePlan` is **not** built for every route in advance.

**5.3 Hand-off: a single-route slice into the unchanged NAV-005 pipeline.** On «Эхлэх» with route *s* selected, the app builds a new JSON body from the stored bytes. It is identical to the response except that `routes` = `[routes[s]]`; `code`, `waypoints` and every other top-level field are copied unchanged. This body goes into the existing NAV-005 path unchanged: `GuidancePlan` → token rewrite (ADR-0009 §3.1, tokens `nav:<gen>:m:<step>` unchanged) → `createOsrmResponseParser(6u)` → Ferrostar `Route` → session.
- **Why:** no change to the token format, the step mapping (F4: plan step *i* = Ferrostar step *i*), the guidance engine, NAV-005 tests or NAV-012's service work. Ferrostar never sees more than one route.
- **Check (mobile, JVM):** for a recorded 3-route response, the slice for each *s* parses into a Ferrostar `Route` whose step count equals the plan's. The AC 19 replay (select route 2, start, compare the banner sequence) runs on that slice.
- **Not measured yet (dev stack down):** whether Valhalla 3.9.0 attaches `bannerInstructions` and `voiceInstructions` to alternates exactly as it does to `routes[0]`. The NAV-005 pipeline reads only manoeuvre fields and empties the voice text (ADR-0009 §3), so text presence does not matter. What matters is that Ferrostar's parser accepts an alternate's steps. QA records one 3-route response with the Android preview body (example `androidPreviewCarAlternates`) when `/health` is 200, and the slice test runs on it. If an alternate fails to parse, the outcome is `BadResponse` for that selection only, and the summary keeps the other routes (a mobile fallback; it should not happen).

**5.4 Rendering and selection.**
- Lines come from map-style §7.2 and the Android section §7.6 that UX is adding for NAV-011 (tokens `route.selected`, `route.alternative`, casings, widths in dp). The selected line is drawn above the alternatives. The native style keeps being generated from the web style code (ADR-0009 §6). New native layers are added by the app at runtime on the route GeoJSON source and re-added after `setStyle` (theme switch), like the NAV-005 route line.
- **Hit test (AC 17):** on a map tap, `queryRenderedFeatures(RectF(tap ± 24 dp))` over the alternative and selected line layers. If any non-selected route is hit, the first one is chosen ("the line that is **not** selected wins" when lines overlap). This gives the ≥ 48 dp target without depending on the drawn line width. The tap never moves the camera and sends 0 requests.
- **Camera (AC 16):** one fit at first render, to the union bounds of all drawn routes and both markers, with padding = 40 dp + the insets of the top bar and the sheet in its current state, `maxZoom` 17. If that padding leaves no room, 40 dp on every side is used (ADR-0006 §5 rule).
- **State:** routes, selected index, mode and the avoid toggle live in the ViewModel, so rotation, theme and language changes redraw without a request (AC 20). A new response resets the selection to route 1 (AC 21).
- The «Маршрут сонгох» group and the D55 draggable sheet follow the UX screen spec. Compose `Modifier.selectable` / `selectableGroup` exposes the selection to TalkBack (AC 18).

### 6. «Дугуй» (AC 22–26)
- `TravelMode` gains `BICYCLE("bicycle")`. Bodies for `pedestrian` and `bicycle` carry **no** `costing_options` (contract 0.5.0). Reroutes use the costing of the route being followed (ADR-0009 §4, unchanged).
- The existing NAV-005 classification already maps 400 `DistanceExceeded` to `TooFar`. The preview shows N11 for walk and bike, and «Маршрут олдсонгүй» for car (AC 24). The dev limit for bicycle is 500 km straight line (openapi `postRoute`).
- The voice schedule reads a per-mode column from `docs/design/navigation-ux.md` §4.2/§4.4 and the camera table. Until UX adds the «Дугуй» column, `BICYCLE` maps to the walk column (AC 26). The Ferrostar deviation and step-advance values stay as ADR-0009 §1 for every mode (no story change).
- The mode selection is in-memory for the session. It is not written to DataStore (AC 22: a restart returns to «Машин»).

### 7. Typing lock (AC 27–37). On-device only, no API
- **No contract or backend impact.** The lock sends 0 requests and stores nothing (AC 28, NFR-P4).
- **Fix source:** `LockFixSource` uses `LocationManager.GPS_PROVIDER`, 1,000 ms, `minDistance` 0 (D62, no Play services). It is collected with `repeatOnLifecycle(STARTED)` only while S1 or S3 is the visible destination, so updates stop within 2 s after leaving (AC 27). It never asks for permission or settings. With no permission, approximate-only permission or the provider off, it emits nothing, and the lock stays released (AC 35). It is **separate from** the guidance provider and the NAV-012 foreground service and does not share their lifecycle.
- **Rule:** `FixSpeed` (story Terms) and `TypingLockRule` are pure functions over `(fix, nowMs)` with an injected clock. They cover every AC 30–32 and AC 36 sequence. The rule also receives "permission revoked" and "provider disabled" events (from `PROVIDERS_CHANGED_ACTION` and a permission check on `ON_START`) and releases the lock on them (AC 32).
- **Passenger override storage: process memory only.** A process-scoped holder (an `object` or an app-scoped singleton in the existing DI module). It is **not** in `SavedStateHandle`, `onSaveInstanceState`, DataStore or any file. A `SavedStateHandle` entry would survive process death and restore the override on relaunch, which AC 34 forbids. Robolectric check: recreate the process (new `Application` instance), and there is no override.
- **UI:** while the lock is engaged and there is no override, the text field is `readOnly` (it stays focusable for TalkBack). A tap shows K1, K3 and «Би зорчигч». When the lock engages with the keyboard open, `SoftwareKeyboardController.hide()` runs and focus is cleared. The typed text, any in-flight search and the results list are kept. A polite live region announces K1 once per engagement (AC 31). Every other control stays enabled (AC 33).
- **Privacy:** fixes and speeds are never logged, even in debug, and never stored. The NAV-005 AC 67 scan is extended to the lock session (AC 28).

### 8. What the contract says (openapi 0.5.3, documentation only)
Documentation only: the NAV-011 Android client profile under `search`, `reverse` and `postRoute`, plus two request examples (`androidPreviewCarAlternates`, `androidPreviewBicycleEn`). No path, parameter, status code, header, schema or gateway behaviour changes. The backend re-runs its contract check with the new examples; there is nothing to implement.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **(a) Kotlin port with one shared vector fixture (chosen)** | No contract, backend or index change while NAV-012 and the frozen web run in parallel. Pure functions about 150 lines long, JVM-tested. The fixture guards against drift (R3). Same request budget as the web (≤ 2 per settled query) | A second copy of the logic now, and a third with NAV-015 (iOS). Port pitfalls (script classes, locale, whitespace) need care (§1). Phones send up to 2 requests per settled query |
| (b1) Photon import-time synonyms / custom analyser | One place for all clients, 1 request per query | Unverified on Photon 1.3.0 (the spike was not run). A re-import is needed for every tweak, which restarts the shared stack (forbidden in this run). Transliteration of every Latin input cannot be expressed as synonyms. Rule C (vowel fallback only if empty) cannot be expressed in an analyser without also changing correct queries |
| (b2) Gateway search wrapper doing assistance and fan-out | One place for all clients, 1 round trip from the phone | New service code in the gateway (nginx today, ADR-0002), and the end of the pass-through principle for search. A contract change, and the web would need its own change request to use it. Merging Photon JSON server-side is response shaping with no auth, cache or rate-limit reason |
| Run the synonym spike first, then decide | Decides with data | Delays NAV-011 against the PO's go-ahead. Even a positive result would not cover Latin transliteration, so a client port would still be needed for rule B |
| Build a `GuidancePlan` for every route and change the token format to include the route index | Instant start | Changes NAV-005 tokens, tests and engine code that NAV-012 is also touching. Builds unused plans |
| Pass all k routes to Ferrostar and pick an index | Closer to Ferrostar's model | The step mapping and rewrite assume one route (ADR-0009 F4). Ferrostar has nothing to do with alternatives during guidance (switching is out of scope) |
| Store the passenger override in `SavedStateHandle` | Survives rotation for free | Also survives process death, which breaks AC 34. Rotation is already covered by the ViewModel plus the process-scoped holder |
| A tap-target hit layer with ≥ 48 dp line width (as the web's invisible layer) | Same mechanism as the web | Ties the hit area to style widths at each zoom. A query rectangle of ±24 dp gives the AC 17 guarantee at every zoom |

## Consequences
- **No backend work for NAV-011.** openapi 0.5.3 is documentation only. NFR-R2 (search budget) now applies to Android too. Route load per preview is unchanged (1 request), but each preview request asks Valhalla for alternates, which costs more server time than `alternates: 0`. The web preview already does this (NAV-004). NFR-L1 (p95 ≤ 500 ms) stays defined on the NAV-001 car profile. AC 43 measures the client path (≤ 1,500 ms, 19 of 20).
- **R3 (drift)** is guarded by the shared fixture. **The R11 revisit is set:** run the synonym spike through triage before NAV-015 search.
- **NAV-005 tests:** only those asserting AC 3, 4, 5 and 6 first-slice behaviour change (query as typed, 0 reverse, `alternates: 0` in preview, one line, two tabs). The reroute `alternates: 0` assertion stays.
- **NAV-012:** no shared runtime component. The lock uses its own foreground-only location subscription. Guidance gets a one-route response, as before.
- **Not verified in this design:** live alternates with `voice_instructions: true` on the Android body (stack down, §5.3), and Photon synonym capability (spike deferred).

## Amendment 2026-10-03 (integration review): implementation deviations accepted
The NAV-011 integration review accepted these mobile deviations. None of them changes the contract or the NAV-005 guidance pipeline:
- **§5.2, parse up front.** Each route's single-route slice is processed when the preview response arrives, not on selection. The processor is stateless, so slices that share a generation do not interfere, and selection stays a pure state change (AC 17). If route 1 fails to parse the outcome is `BadResponse`. If an alternative fails, it is dropped and the options are renumbered in display order (`Маршрут {n}` = position in the shown list, not the Valhalla index).
- **§5.4, two sources.** Unselected routes are drawn from a second GeoJSON source `nav-route-alt` below the selected line's source, not from one source with a `selected` property. The hit test queries both line layers in a 48 × 48 dp box (±24 dp), which keeps the AC 17 guarantee.
- **Camera fit (§5.4).** The fit runs once per response (keyed on the routes list), after the sheet has re-measured. It also runs again when the composition is recreated (rotation). This sends 0 requests and still meets AC 20.
- **The handle zone is 48 dp** (UX spec said 24 dp) to keep every touch target ≥ 48 dp (AC 44).

## Amendment 2026-10-04 (D140): typed coordinates open the coordinate card on Android
**Why.** The PO approved the change-request impact for NAV-011 (D140, triaged by D137). It supersedes the "sent as typed / no card" part of D115, which this ADR recorded in §3. PO answers D141–D146 fix the details: NAV-011 accepted first (D141), camera at zoom 16 or higher (D142), the integer-pair rule kept (D143), this change lands before NAV-018 and this section defines the API NAV-018 reuses (D144), the NAV-018 fields get the same option (D145), and the field closes after selection with the existing `ic_location_searching` icon (D146).

**No new ADR.** The change is small and reversible, and it stays inside the packages and the controller this ADR already defines. **No contract change.** `openapi.yaml` stays at 0.5.4, untouched. Its `search` text already makes Android typed coordinates follow "the NAV-011 rule in force (AC 7 / D115 until the D137 change is applied)", and its `reverse` profile is unchanged (`lat`/`lon` 6 decimals, `lang`, `limit=1`, `radius=0.5`, one request per card). The only effect on the wire is fewer `search` calls (0 for a recognised pair). No backend task, and no web change (NAV-011 AC 41; `web/src/search/queryPlan.vectors.json` unchanged).

### A1. The coordinate state of `SearchController` (`mn.navmn.app.search`)
`SearchView` gets one new variant. Nothing else in the sealed interface changes:

```kotlin
/** D140 (ADR-0012 §3): the settled query is an ADR-0006 §2.2 coordinate pair. One option, 0 requests. */
data class Coordinate(val point: LatLon) : SearchView
```

- `point` holds the parsed values from `CoordinateInput.parse`, **not rounded**. The UI formats the option text with the existing `Formatters.coordinates` (5 decimals, point separator). `ReverseClient` formats the `reverse` request with 6 decimals, as for a long-press.
- The state carries no strings. Labels come from resources at composition, so a language switch or rotation only re-renders: 0 `search` and 0 `reverse` requests (NAV-011 AC 13). The state lives in the ViewModel-owned controller, so it survives rotation.
- The controller has **no selection method**. Selection belongs to the owner of the list (A3), as for a `Results` item.

### A2. Run order (web parity with `searchController.ts` `settle()`)
In `run(q, force)`:
1. Same-query dedupe: `!force && q == lastSettled` and the view is `Results` **or `Coordinate`** → return, 0 requests. `onQuery`'s existing check (`q == lastSettled && view !is Closed`) already covers the state.
2. `lastSettled = q`, then plan with `QueryPlanner.plan(q)`.
3. **`QueryPlan.Coordinate` → `generation++`, view = `SearchView.Coordinate(point)`, return.** This step comes **before** `isOnline()` and `cooldown.active(now())`. The generation bump discards any older response still on its way, including the second half of an `ifEmpty` pair. The debounce job of the previous query was already cancelled by `onQuery`.
4. Only `QueryPlan.Text` continues to the existing offline → cooldown → loading row → `fetch` path, unchanged. `fetch` takes the `QueryPlan.Text` from step 2. `requestsFor` no longer maps `Coordinate` to a text plan. The mobile engineer may delete it or make it return the plan unchanged.

Consequences that the tests pin down:
- Offline, or inside a `search` 429 cooldown (also one started by the other instance through the shared `SearchCooldown`, ADR-0015 §5), the list shows the option, not «Интернэт холболт алга» or the 429 row, and 0 requests are sent.
- `retry()` and a network resume send 0 requests for a coordinate. No retry control is shown for the state. The controller has no network-resume hook today, and adding one is out of scope.
- A text query typed after a coordinate goes through the normal path. A coordinate typed while a text query is in flight discards that response (step 3).
- The loading row never shows for a coordinate.
- Rejected pairs (out of range, decimal comma, full-width comma, DMS) are `QueryPlan.Text` and are sent as typed, at most as the plan allows.

### A3. Selection API, shared by the map screen and the NAV-018 fields
The list composable that NAV-018 already shares (`SearchResults` in `BrowseOverlay.kt`, with `view`, `onResult` and `onRetry` parameters) gets one more parameter:

```kotlin
onCoordinate: ((LatLon) -> Unit)? = null,
```

- **Rendering (`SearchView.Coordinate` branch):** one row ≥ 48 dp with the `ic_location_searching` icon, line 1 «Сонгосон цэг» (`place_selected_point`, glossary T6), line 2 the 5-decimal coordinates, and a TalkBack description built from existing strings (UX screen spec). No new string and no new icon (D146). The row is a list item, not typing, so it can be selected while the typing lock is engaged (NAV-011 AC 33).
- **`onCoordinate == null`:** the row renders without a click action. This is a transitional default so that NAV-018's `PointEditor` call site keeps compiling until its own run passes a handler. Every production call site passes a handler once NAV-018 lands (D145).
- **Exhaustive `when (SearchView)`:** any new one (for example in NAV-018 code) must handle `Coordinate`. Today the only exhaustive one is in `SearchResults`.

**Map-screen handler (this change).** `BrowseActions.onCoordinateOption(p)` → `AppViewModel.onCoordinateOption(p: LatLon)`:
- Same as `onLongPress(p)`: `card = p`, `searchActive = false`, `reverse.open(p)`. Exactly 1 `reverse`.
- The field and list close as after `onResult` (D146), and the keyboard hides within 500 ms. As built, the handler calls `search.close()` (the browse list has no separate visibility flag), so the controller returns to `Closed` while the field keeps the typed text.
- `followingMe = false`, so the NavRoot follow-me effect cannot snap the camera back to the device.
- Emits a **one-shot camera request** to centre on `p` (A4). It is held in the ViewModel with an id and marked as consumed after it runs, so rotation does not repeat it.
- `onCardDirections` is unchanged: the destination is `Destination(p, null)`, so the text is «Сонгосон цэг».

**NAV-018 field handler (built by the NAV-018 run, D145, not by this change).** The same `SearchView.Coordinate` from `fieldSearch`, passed to the editor's `onCoordinate`:
- Sets the edited side to `RoutePoint.TypedCoordinate(point)` (ADR-0015 §2). The field text is «Сонгосон цэг», from resources.
- Closes `fieldSearch` as after a result. There is **no card**, **0** `reverse`, and **0** `search` requests (NAV-018 AC 5, NAV-004 AC 6 parity).
- Exactly one route request once both points are set, through the existing `PreviewController` path. No camera request beyond the existing NAV-011 fit.
- The fields add no coordinate logic of their own (ADR-0015 §5 stands). The ADR-0015 §2 `TypedCoordinate` row names `SearchView.Coordinate` directly.

### A4. Camera on selection (D142). Map screen only
*Aligned 2026-10-04 (integration review) with the UX screen spec camera rule **C1** and layout rule **P8**, as built in `mn.navmn.app.map.CoordinateCamera` and `NavRoot`. The UX spec is the source for the numbers; this section records the mechanism.*
- NavRoot consumes the one-shot request with the existing `MapCamera.focus(p, maxOf(c.zoom, 16.0), left, top, right, bottom, durationMs)` (added by NAV-018, implemented in `NavMapController`).
- **Order:** the card is composed in the same frame as the selection, and the move runs once **after the card's first layout**, so the card height is known. The wait for that layout is capped at 500 ms; after it the move runs without a card box. This is the "centre, then the card's content" order of AC 7a/D142: the camera moves once at the selection and never after that while the card is open (AC 9).
- **Free rectangle and padding** (pure function `CoordinateCamera.padding`, px relative to the map view):
  - narrow windows: full map width, from the bottom of the top group (search row plus any lock card or S1 message still shown) to the top of the card;
  - wide windows (P8: the card is a start-edge column of the P5 side-sheet width, for both entry points): from the card column's end edge to the control lane's start edge, from the bottom of the top group to the bottom of the map view;
  - inside it: top 16 dp + 40 dp (the pin stands above the point), bottom and sides 16 dp;
  - if that leaves no room, the 16 dp margins are dropped (the pin room stays); if there is still no room, 40 dp on every side (the §5.4 / ADR-0006 §5 rule).
- `durationMs` = **700 ms** (`motion.route-camera`, as built in `CoordinateCamera.EASE_MS`; the same value as the NAV-018 step focus `StepCamera.EASE_MS`), or 0 with reduced motion (animator duration scale 0). It is not 300 ms: 300 ms is only the follow-me move below.
- **Stale padding:** MapLibre keeps the padding on the camera position. The follow-me effect therefore calls `focus(device, zoom, 0, 0, 0, 0, 300)` instead of `easeTo`, which resets the padding left by this move or by the NAV-018 step focus. Duration and zoom of the follow-me move are unchanged (300 ms, max(zoom, 15)). The preview fit (§5.4) passes its own padding. On-device confirmation is not verified (no Android test phone, NAV-011 AC 47).
- **Focus for TalkBack:** after the typed entry, input focus moves once to the card title (a heading), per the UX screen spec › Accessibility.
- **Long-press is unchanged:** it never moves the camera (NAV-011 AC 9). After the card has opened, the pin, coordinates and camera do not move.
- The centring happens only for the typed-coordinate entry point.

### A5. Tests (mobile and QA; the list in the approved impact)
- `AssistedSearchTest.typedCoordinateIsStillSentAsTyped` is inverted. For '47.9189, 106.9176', '47.9189,106.9176' and '47.9189 106.9176' it asserts 0 calls and `SearchView.Coordinate(LatLon(47.9189, 106.9176))`.
- New `AssistedSearchTest` cases:
  - offline: the option, 0 calls;
  - cooldown active, also via a shared `SearchCooldown`: the option, 0 calls;
  - `retry()` on a coordinate: 0 calls;
  - the swapped pair and the decimal-comma pair: 1 call each, as typed;
  - generation: a pending text response is discarded after a coordinate;
  - an integer pair '47 106' gives the option (D143).
- Robolectric (Nav011ActivityTest or the QA file, without duplicates): option visible with 0 `search` → tap → card «Сонгосон цэг», 1 `reverse` at 6 decimals, camera at `max(zoom, 16)`, `followingMe` off → rotation and a language switch send 0 → «Маршрут гаргах» gives the destination «Сонгосон цэг». The option is still selectable under the typing lock.
- `QueryPlanVectorsTest`, `ReverseTest`, the NAV-005 suite (`-Pnav.hostFerrostar=required`) and web vitest stay green and unchanged.
