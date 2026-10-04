# ADR-0015: Android route preview points and turn list. One typed point model for start and destination, guidance only from a device start, field search on a shared 429 cooldown, and the turn list built from the already parsed plan

- **Status:** accepted (Phase 1, Android). §3 follows the BA default (a) of NAV-018 Open question 1. If the PO chooses (b) or (c), §3 gets an amendment (see Consequences).
- **Date:** 2026-10-04
- **Stories:** NAV-018 (AC 1–38). Builds on NAV-011 (ADR-0012) and NAV-005 (ADR-0009). Reuses ADR-0008 (client-side instruction text) and ADR-0006 §3 (search orchestration, 429 per operation). Parity reference: NAV-004 (web).

## Context
NAV-018 brings the web NAV-004 origin choice (search, map point, «Миний байршил», swap) and the turn list «Маршрутын заавар» to the Android route preview. NAV-011 left the points block "display only".

Forces:
- **Contract.** `openapi.yaml` 0.5.3 already covers everything NAV-018 sends and reads:
  - `postRoute` takes any two `locations`. Its 200 response carries `legs[].steps[]` with `maneuver.type`, `modifier`, `exit`, `bearing_after` and `location`, plus `name`, `distance` and `waypoints[].distance`.
  - `search` and `reverse` are unchanged.
  - Checked on the seven recorded Android responses in `mobile/android/app/src/test/resources/routes/`: every route response has 1 leg, starts with `depart`, ends with `arrive`, and has every field above on every step.
- **Existing Android code** (read 2026-10-04, before the parallel D137 change run edits it):
  - `PreviewState.origin` is a `Fix?`, and the destination is a separate `Destination(point, name)` type. The origin is set only by the `PREVIEW_ORIGIN` location action in `AppViewModel`, which waits up to 10 s for a fix (`freshFix`). A pending action is resumed in `onResume`.
  - `PreviewController` is the only route requester for the preview. It has one generation counter and the 429 cooldown.
  - `OsrmPlanParser` already turns every step into a `PlanStep` (manoeuvre input, location, cleaned street name, distance, duration). `PreviewRoutes.process` builds a `ParsedRoute` (with its `GuidancePlan`) for **every** route when the response arrives (ADR-0012 Amendment).
  - `ManeuverRules.key` and `BannerText.text` are the ADR-0008 Android rules. They are tested against the shared fixture `web/src/route/maneuvers.fixture.json`: 53 cases with the expected `mn` and `en` text.
  - `SearchController` keeps its 429 cooldown per **instance**, so a second instance would have its own cooldown. ADR-0006 §3 and the openapi `search` profile require the cooldown per **operation** for the device.
- **Guidance assumes a device start.** NAV-005 guidance, reroute and NAV-012 restore follow the device position. «Эхлэх» sends 0 requests and navigates the previewed route (NAV-011 AC 19). A route that starts somewhere else cannot be navigated from here.
- **Parallel work.** The D137 change run will change how `SearchController` treats typed coordinates. The NAV-012 housekeeping fix touches the battery row. NAV-018 must keep its own packages and make only targeted edits to shared files (story AC 36).
- **Dev stack during this design:** `http://127.0.0.1:8080/health` refused the connection (checked once, 2026-10-04). Nothing in this ADR was measured live.

## Decision

### 1. No contract change. openapi 0.5.4 is documentation only
- The NAV-018 Android profile is recorded under `postRoute`, `search` and `reverse`, with one new request example, `androidPreviewChosenStartWalk`.
- No path, parameter, status code, header, schema or gateway behaviour changes. Backend has nothing to implement.
- No gateway wrapper is added. The turn list, the instruction text and the swap are all client-side.

### 2. One point model for both points (`preview/points`, pure JVM)
A sealed `RoutePoint` with `point: LatLon` (6 decimals in the body, never rounded) and four kinds:

| Kind | Set from | Field text | Notes |
|---|---|---|---|
| `MyLocation(fix)` | the «Миний байршил» option, or preview open with a good fix (AC 1, 3) | «Миний байршил» / "My location", rendered from resources at composition | The fix is **frozen** when the point is set (AC 10). It is never refreshed, also after a swap (AC 11) |
| `Place(point, name)` | a search result (AC 4) | the result name as shown in the list, stored once | Not re-localised on a language switch (AC 33) |
| `MapPoint(point)` | the coordinate-card buttons (AC 6) | «Сонгосон цэг» / "Selected point", from resources | Never takes the `reverse` name (NAV-011 AC 9) |
| `TypedCoordinate(point)` | only after the D137 change is applied (AC 5, second bullet) | «Сонгосон цэг» / "Selected point", from resources | Mapped from whatever coordinate outcome D137 adds to the search code. The fields have no parser of their own |

Rules (pure functions with JVM tests, injected clock):
- **Both points have the same type**, so a swap is a pure exchange of two values. The destination is never null. The origin is null only in the AC 2 case.
- **Device start** = `origin is MyLocation`, where the fix was good (≤ 25 m accuracy, ≤ 10 s old) and ≤ 60 s old when the point was set (story Terms). Every other origin is a **chosen start**.
- **Start gate:** «Эхлэх» is enabled only with a route state and a device start (§3). This replaces `PreviewState.canStart`.
- **Same point:** haversine ≤ 10 m between the two points → `SamePoint`, 0 requests (AC 9). This is the existing `SAME_POINT_M` rule, now applied to any pair of kinds.
- **Display labels are not stored for the resource-backed kinds.** The UI renders them from the current language, which gives AC 24 and AC 33 with 0 requests.

### 3. Guidance only from a device start (Open question 1, default (a))
- The NAV-005 / NAV-011 hand-off is unchanged. «Эхлэх» passes the selected single-route slice to the guidance pipeline with 0 requests, after the existing F4 fresh-fix check.
- `onStart()` returns early unless the start gate holds. That check sits before the notification-permission and location prompts, so a disabled «Эхлэх» asks for nothing and sends nothing (AC 15).
- `Trip` takes the destination point and its display text. The guidance, reroute, foreground service and NAV-012 restore record do not change: with a device start, reroute from the live position is correct.

### 4. Location actions never overwrite a point the user set (AC 2, 3, 10)
This race exists today but cannot be seen, because nothing else sets the origin:
- Today, `PREVIEW_ORIGIN` launches a coroutine that waits up to 10 s for a fix and then calls `setOrigin`.
- `onResume` can resume the pending action later (back from Settings).

With NAV-018, a late fix must not replace a start the user chose meanwhile. Rule:
- Each origin attempt (preview open, or the «Миний байршил» option) gets a token.
- A fix is applied only if the token is still current **and** the origin was not set by the user after the attempt started.
- Setting a start any other way (search, map point, swap) cancels the attempt and clears the `PREVIEW_ORIGIN` pending action. It does not clear the NAV-005 location message for other actions.
- When the attempt fails, the origin stays as it was and 0 requests are sent (AC 3).

### 5. Field search reuses `SearchController`, with one 429 cooldown per operation (AC 4, 5, 7, 34)
- **A second `SearchController` instance for the preview fields.** It has the same class, client, settle, plan, bias function (D30, rounded to 3 decimals by `SearchClient`), lang and timeouts.
  - It is separate from the map-screen instance, so the map-screen query and list survive while the preview is open.
  - Only one field list is open at a time. Moving focus to the other field, leaving a field or choosing a result calls `close()`, which bumps the generation. A response for the old field can therefore never fill the new field's list (AC 4).
- **Shared cooldown.** The 429 cooldown moves into a small injected holder, one per operation. `AppViewModel` passes the same `search` holder to both instances.
  - The constructor parameter has a default (a private holder), so existing call sites and NAV-005 / NAV-011 tests compile and behave as before.
  - This is the only `SearchController` edit NAV-018 needs. It does not touch `requestsFor`, which is the D137 area.
  - `reverse` keeps its own cooldown (ADR-0012 §4).
- **Typed coordinates** follow whatever the shared controller does at verification time (AC 5). The fields add no coordinate logic.
- **Typing lock:** both fields are text-entry surfaces and use the existing NAV-011 `TypingLock` gate (`readOnly` while engaged, K1/K3 card).
  - The «Миний байршил» option is a list item, not typing, so it stays selectable under the lock (AC 34).
  - It sends 0 `search` requests.
- **Request budget** is unchanged (NFR-R2): at most 2 `search` per settled query, at most one active field.

### 6. The coordinate card in the preview (AC 6)
- The existing NAV-011 card and `ReverseController` are reused: exactly one `reverse` request per card.
- In preview context the card shows «Эхлэх цэг болгох» and «Очих газар болгох» instead of «Маршрут гаргах». Both buttons are usable before `reverse` answers.
- A button sets a `MapPoint` at the long-press coordinate, closes the card and triggers one route request through `PreviewController`. «Хаах» / Back changes nothing and sends 0 requests.
- The card mode is a parameter of the card state, not a second card implementation.

### 7. `PreviewController` stays the only route requester (AC 11–13, 17)
Targeted edits:
- `origin: RoutePoint?`, `destination: RoutePoint`.
- `setOrigin(RoutePoint)`, `setDestination(RoutePoint)`, `swap()`. The existing `setOrigin(fix: Fix)` stays as a thin overload to `MyLocation(fix)`, so NAV-005 and NAV-011 tests keep compiling.
- Every triggering action calls the existing `request(0)`. Mode tabs keep their 300 ms settle.
- The generation counter cancels the older job, so at most 1 request is in flight and an older response never replaces a newer one.
- During a 429 wait the points and markers update and the state stays `RateLimited`, with 0 requests (AC 12). The NAV-005 retry-on-user-action rule after the wait is unchanged.
- The body builder (`RouteBody`) is unchanged:
  - `locations[0]` = `origin.point`, `locations[1]` = `destination.point`;
  - `alternates: 2`;
  - **no `heading`** in any preview request, whatever the kind.
- `waypoints[0].distance` and `waypoints[1].distance` both feed the D51 notice. The larger one is shown (AC 14). This is already in `GuidancePlan.snapDistances`.

### 8. Turn list from the parsed plan (`preview/turnlist`, pure JVM model)
- **Source:** `ParsedRoute.plan.steps` of the **selected** route. Every route is already parsed when the response arrives, so switching routes is a pure state change (AC 21, 0 requests). The list does not:
  - re-parse JSON;
  - read Ferrostar `Route` steps, which hold opaque tokens (ADR-0009 §3.1);
  - read `maneuver.instruction`, banner text or voice text (ADR-0008).
- **Row model** (one per step, in order): `TurnRow(index, key: KeyResult, street: String, distanceM: Double?, location: LatLon)`.
  - **Row *i* uses step *i*'s own manoeuvre, at `maneuver.location`.** This is the NAV-004 list semantics. Banners differ: they announce step *k+1*'s manoeuvre during step *k* (ADR-0009 §3). The model must not use the banner index shift.
  - `distanceM` = `step.distance`, or null on the `arrive` row.
  - `street` = `PlanStep.street` (zero-width characters removed, D11, shown as returned). It is omitted when empty.
- **Text:** `BannerText.text(row.key, lang, strings)`, called at composition. There is no second mapping and no new instruction string (AC 19). Distance uses the existing NAV-004 AC 23 formatter in `format/`. The model holds keys and numbers only, so a language switch is a recomposition with 0 requests (AC 24).
- **Icon:** the NAV-005 banner manoeuvre icon for the same `ManeuverKey`. It is decorative and not focusable.
- **Legs:** with exactly 2 `locations` there is one leg, so the plan's steps are `legs[0].steps`. The builder takes the steps of the first leg only if more legs ever appear. That is not reachable in NAV-018.
- **Performance:** the build is O(steps), with no allocation per frame. A 500-step model must build in ≤ 200 ms on the JVM (AC 25). The UI is a `LazyColumn` with stable keys `(route index in the response, step index)` (AC 25 laziness check).
- **Row tap (AC 22):**
  - a pure camera rule (next to `map/CameraRules`): target = `maneuver.location`, zoom = max(17, current), padding = 40 dp plus the current top-bar and sheet insets;
  - 0 requests, the selection and the scroll position are kept;
  - whether the sheet collapses first is a UX decision.

### 9. Markers
- **Device start:** the NAV-005 location puck, with no second marker.
- **Chosen start:** the map-style §7.3 origin marker (tokens `route.origin-fill` / `route.origin-stroke`, 18 dp circle with a 4 dp ring), drawn natively like the NAV-011 destination pin, from `docs/design/tokens.json` values bundled under ADR-0009 §6. No new colour.
- **Destination:** always the existing NAV-011 pin at the destination point, also when «Миний байршил» has been swapped to the destination. The pin then sits at the frozen fix, and the live puck stays separate.
- MapLibre symbols are not exposed to TalkBack. The content descriptions «Эхлэх цэг: …» and «Очих газар: …» (AC 8) therefore go on an accessibility node the app owns, the same approach as the NAV-011 destination pin.
- The NAV-011 camera fit already takes both markers into its bounds.

### 10. State, privacy and lifetime (AC 31–33)
- Points, field texts, the selected route and the list scroll position live in the ViewModel (in memory). They survive rotation, theme and language changes with 0 requests.
- They are **not** in `SavedStateHandle`, DataStore or files, so no preview is restored after process death (AC 33).
- A new preview always starts from AC 1–2. A chosen start is not kept (Open question 3, default (a)).
- Points, fixes, field texts and street names are never logged, even in debug. The NAV-005 AC 67 log and storage scan covers the NAV-018 session.
- The device position leaves the phone only in a route body whose point is `MyLocation`, and as the D30 bias already allowed. It is never sent as a `reverse` point. The gateway logs paths only (ADR-0002 §3.4, NFR-P1).

### 11. Packages and shared files
| Package (names are the mobile engineer's choice) | Contents | Android types? |
|---|---|---|
| `mn.navmn.app.preview.points` | `RoutePoint`, point rules (device start, start gate, same point, swap), origin-attempt token | no |
| `mn.navmn.app.preview.turnlist` | `TurnRow`, `TurnListModel.build(plan)`, row-tap camera rule | no |
| `mn.navmn.app.ui.screens.preview` (new files) | `PointFields`, `TurnList`, the card buttons in preview context | Compose |

Targeted edits only, each re-read just before editing:
- `PreviewController` (§7);
- `SearchController` (constructor cooldown holder, §5);
- `AppViewModel` (second search instance, origin-attempt token, start gate, wiring);
- `RoutePreviewSheet` (slots for the fields, list and O1);
- the coordinate card (mode);
- `strings.xml` in `values/` and `values-en/` (O1 only, plus any missing glossary keys);
- `MainActivity` only if wiring needs it.

Lines from the D137 run and the NAV-012 battery-row fix are never removed.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Typed `RoutePoint` for both points, `PreviewController` as the only requester (chosen)** | Swap is a value exchange. One place for request ordering, 429 and generation. Start gate and label rules are pure and tested on the JVM | Targeted edits to `PreviewController` and `AppViewModel` while other runs also touch them |
| Keep `origin: Fix?` and add a separate "chosen origin" field | Smaller diff | Two sources of truth for the start. Swap with «Миний байршил» has nowhere to go (the destination is not a `Fix`). Easy to send the wrong one |
| A new `PointsController` that owns its own route requests | Fully separate package | Two requesters break "at most 1 in flight" and "older never replaces newer" (AC 12) unless they share state, which means editing `PreviewController` anyway |
| A separate `SearchController` with its own 429 cooldown | No `SearchController` edit | A 429 just received on the map-screen field would be ignored by the fields. That breaks the per-operation cooldown (ADR-0006 §3, openapi `search`) |
| Reuse the map-screen `SearchController` instance for the fields | No second instance | The map-screen query and list are lost when the preview opens. Its view state would leak into the preview lists and back |
| Turn list from Ferrostar `Route` steps | Same object as guidance | Holds opaque tokens, not text. Only the selected slice is parsed at start. Couples the preview to the engine |
| Turn list from Valhalla `maneuver.instruction` | No mapping work | The NAV-007 `mn-MN` defects. Violates ADR-0008 |
| A gateway endpoint that returns a shaped turn list | One place for every client | Response shaping with no auth, cache or rate-limit reason. A contract change and backend work for text the client already renders (ADR-0008) |
| Guidance from a chosen start (simulated drive) | Matches the drivers' "pickup first" wish | Out of scope. The guidance engine assumes live positions. The PO would decide this as Open question 1 (b), and it is a separate story |

## Consequences
- **No backend work.** openapi 0.5.4 is documentation only. The backend re-runs its contract check with the new example when `/health` is 200.
- **Route load:** still one request per settled action. A swap is one request, and swapping twice is two. The NFR-L1 route p95 is unaffected: same body shape, same `alternates: 2`.
- **NAV-005 / NAV-011 tests that change:** only those asserting the start is always «Миний байршил», the display-only points block, or «Маршрут гаргах» on the card in the preview (story AC 35). The QA handoff lists each one.
- **If the PO picks Open question 1 (b)** («Эхлэх» starts from the current device position): «Эхлэх» with a chosen start must first replace the origin with `MyLocation(fresh fix)`, wait for one new preview response, and then hand off. That breaks the "0 requests at start" rule of NAV-011 AC 19 for that path, and the user's choice of route is lost. This needs an amendment of §3 and a story change. **(c)** («Эхлэх» hidden) changes only the UI.
- **If the PO picks Open question 3 (b)** (keep a chosen start for the app session): a process-scoped holder like the NAV-011 passenger override, never `SavedStateHandle`. This affects §10 only.
- **Edge case not covered by the story (open question for the PO):** «Миний байршил» swapped to the destination, then «Миний байршил» picked again as the start after the user has moved more than 10 m. Both points are then `MyLocation`, the start gate holds, and guidance would lead to the old position labelled «Миний байршил». The point rules make whatever the PO decides a one-line change. The architect recommends converting the destination to a `MapPoint` («Сонгосон цэг»), which needs no new string.
- **iOS (NAV-015)** can reuse this point model and list semantics. The text rules are already shared through the fixture.
- **Not verified in this design:**
  - live routes with a chosen start, or the new example (dev stack down);
  - the RS3, RS4 and RS6 recordings (QA records them when `/health` is 200);
  - every real-device behaviour (story AC 38).
