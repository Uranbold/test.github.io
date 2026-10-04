---
id: NAV-018
title: Android route preview, choosing the start point (search, map point, swap) and the turn list «Маршрутын заавар» (parity with web NAV-004)
phase: 1                # Phase 1 beta follow-up of NAV-011 (backlog row placement); parity item
priority: should        # triage P2 / standard, PO-confirmed 2026-10-04 (D136); BA mapping P2 → should
size: M                 # D136 (triage). BA note: close to L if UX puts the fields on their own search screen
needs_design: true      # delta screen spec on NAV-011 S3: fields, swap, card buttons in the preview, turn list, O1 hint; origin marker for a non-device start
needs_backend: false    # the route response already carries the steps (osrm format, banner_instructions); the architect confirms that no contract change is needed
needs_mobile: true      # Android only (mobile/android); iOS is NAV-015
status: ready
---

# NAV-018: Android route preview, start point choice and turn list

## Story
As a **taxi / delivery driver**, I want **to set the start of a route to a pickup point (by search or by long-pressing the map), swap start and destination with one tap, and read the list of turns before I leave**, so that **I can plan the trip from the customer's pickup to the drop-off and check the turns in Mongolian, not only routes from where my phone is now**.

Secondary personas:
- **UB commuter by car**: plans tomorrow's trip from home to work while still at the office. Swaps the points to check the way back. Reads «Маршрутын заавар» to see where the turns into the ger-district streets are.
- **Pedestrian**: plans a walk from a bus stop it searched for to a place, and reads the turns on «Явган».
- **Tourist (English UI)**: types "Gandan" as the start and "Zaisan" as the destination in Latin letters (NAV-011 assistance) and reads the turn list in English. Street names stay as returned, mostly Cyrillic (D11).
- **Intercity / countryside driver**: checks a long route to a soum centre, starting from the city exit and not from home, and scrolls the long turn list. On weak signal it gets the same clear states as NAV-005 / NAV-011.

## Context
- **Origin:** PO decision [D112](../decisions.md) (2026-10-04) took these two items out of NAV-011 into "one separate follow-up item through triage" (NAV-011 Out of scope, Open question 3). Triage 2026-10-04 (`docs/triage/log.md`): type feature, new Android story, size M. **PO-confirmed as P2 / standard, [D136](../decisions.md)**. Starts after NAV-011 is accepted (same preview sheet, default "finish first"). This story replaces the backlog draft row "NAV-011 follow-up: origin choice and turn list".
- **Parity means the web behaviour, already specified and verified, on Android:** [NAV-004](NAV-004-route-preview-web.md) AC 4–9 (fields, «Миний байршил» option, search in the fields, coordinate card buttons, swap, markers), AC 14 (same point), AC 26–30 (turn list) and AC 45 (turn list accessibility), with the screen spec `docs/design/screens/NAV-004-route-preview.md` and **ADR-0008** (instruction text from the client-side templates, already ported to Android for the NAV-005 banners: `instructions/ManeuverRules.kt`, tested against `web/src/route/maneuvers.fixture.json`). Where Android needs a different rule (draggable sheet, touch targets, TalkBack, the NAV-005 permission flow, guidance), this story states it.
- **Built on NAV-011** (preview sheet, alternatives, «Дугуй», assisted search, reverse on the coordinate card, typing lock; ADR-0012) and **NAV-005** (permission flow AC 8–12, preview states AC 7, guidance). The NAV-011 screen spec has a points block that is "display only (origin editing is out of scope)". This story replaces that row. **NAV-011 itself is not edited now** (it is waiting for acceptance, and a parallel change-request run, D137, is analysing NAV-011 AC 7 / AC 39). The QA handoff lists every NAV-005 / NAV-011 test that changes because of this story (AC 35).
- **Guidance from a start that is not the device position.** NAV-005 guidance follows the device position. The web demo has no guidance, so NAV-004 never had to decide this. **Decided by the PO ([D147](../decisions.md), Open question 1 (a)):** «Эхлэх» is disabled when the start is not «Миний байршил», with the new hint O1 (AC 15).
- **PO decisions that apply:** [D11](../decisions.md) (Cyrillic names in the English UI), [D30](../decisions.md) (search bias), [D51](../decisions.md) (snap notice above 500 m), [D55](../decisions.md) (draggable sheet), [D58](../decisions.md) / [D112](../decisions.md) (scope), [D60](../decisions.md) (no toll option), [D62](../decisions.md) (no Google Play services), [D66](../decisions.md) (320 dp above 100 % font scale), [D9](../decisions.md) / [D26](../decisions.md) (team-only staging), [D110](../decisions.md) / [D113](../decisions.md) (typing lock), [D115](../decisions.md) / [D137](../decisions.md) (typed coordinates), [D136](../decisions.md) (priority and start), [D140](../decisions.md) / [D145](../decisions.md) (typed coordinates in the fields), [D147–D153](../decisions.md) (this story's open questions and build alignment, 2026-10-04).
- **Light-QA mode (PO decision for this run, the [D108](../decisions.md) pattern):**
  - QA writes the test plan. It runs only the JVM/Robolectric tests for the changed areas, plus the existing Android suite as regression (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`). No long suites.
  - Live checks run only if the shared dev stack answers `/health` 200. Otherwise they are reported as not verified.
  - The PO has no Android phone yet, so every real-device check is reported as **not verified** (AC 38).
  - **Full QA runs before any public release.**
- **Coordination (binding for this delivery):**
  - A parallel **change-request run** is analysing the typed-coordinate change in the Android search field (NAV-011 AC 7 / AC 39, D115 follow-up, D137). A small **housekeeping fix** touches the NAV-012 battery row.
  - Before editing a shared file (`SearchController`, `PreviewController`, `strings.xml` in `values/` and `values-en/`, `MainActivity`, `AppViewModel`, `RoutePreviewSheet`), read its latest version. Make small, targeted edits and **never revert another story's changes**.
  - Put NAV-018 code in its own package(s) where possible (for example `…/preview/points` and `…/preview/turnlist`; the names are the mobile engineer's choice).
  - Never stop, restart or rebuild the shared dev stack at `http://127.0.0.1:8080`. If `/health` is not 200, wait and retry.
  - Live requests: **at most 2 per second** from the whole test run.
  - No secrets, hostnames or IPs in the repo (loopback for local dev is allowed).
- **API:** `docs/architecture/api/openapi.yaml` (`search`, `reverse`, `postRoute`). No contract change is expected. The `postRoute` 200 response already carries `legs[].steps[]` with `maneuver` (`type`, `modifier`, `exit`, `bearing_after`, `location`), `name` and `distance`, which the web turn list uses. The architect confirms.

### Terms used in this story
- **Point:** the start or the destination. A point is set from (1) «Миний байршил», (2) a search result, (3) a map point («Эхлэх цэг болгох» / «Очих газар болгох» on the coordinate card), or (4) typed coordinates (AC 5).
- **Device start:** the start is «Миний байршил», set from a good fix (≤ 25 m accuracy, ≤ 10 s old, NAV-005) that is ≤ 60 s old when the start is set. Any other start is a **chosen start**. After a swap that moves «Миний байршил» to the destination, the start is a chosen start.
- **Triggering action:** an action after which a new route is needed: a point is set or changed, swap, plus the NAV-011 triggers (mode tab settles, avoid toggle, «Дахин оролдох», network return).
- **Field:** the start field or the destination field in the route preview. Both are **text-entry surfaces** in the sense of NAV-011 (Terms), so the typing lock applies to them (AC 34).
- **Reference points and routes:** P1–P6, X1, X2 (NAV-001); RS1–RS8 (NAV-004). Recorded Android route responses in `mobile/android/app/src/test/resources/routes/` (`p1-p3-car-mn/en`, `p1-p2-walk-mn/en`, `g8-roundabout-car-mn`).

### User-facing strings
Every Mongolian string comes from the glossary. **Reused, no new wording:**

| Use | Glossary row | `mn` |
|---|---|---|
| Start field name, generic start | "Origin / starting point" | «Эхлэх цэг» |
| Start when it is the device position; first list option | "My location / Origin" | «Миний байршил» |
| Destination field name | "Destination" | «Очих газар» |
| Empty start field placeholder | N2 | «Эхлэх цэг сонгох» |
| Destination field placeholder while editing | N3 | «Очих газар сонгох» |
| Swap button (content description) | N4 | «Эхлэх цэг, очих газрыг солих» |
| Turn list heading and name | N5 | «Маршрутын заавар» |
| Same point | N10 | «Эхлэх цэг, очих газар ижил байна» |
| Coordinate card buttons in the preview | N17, N18 | «Эхлэх цэг болгох», «Очих газар болгох» |
| Map point as a field text | T6 | «Сонгосон цэг» |
| Outside the service area (either point) | N9 | «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» |
| Instruction texts | section 3 rows, N14, N15, N23, C2, C4 banner ordinals | the NAV-004 AC 27 table, already in the Android resources |
| Search, reverse, location and preview states | as NAV-005 / NAV-011 | unchanged |

**New row O1, added 2026-10-04 to glossary section 2.6, `needs native review`** (BA proposal; its use is decided by [D147](../decisions.md). The PO may pre-review the wording, as in D69. The NAV-007 panel rates it):

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| O1 | Guidance starts only from your location (hint) | «Замчлал зөвхөн таны байршлаас эхэлнэ» | "Navigation starts only from your location" | AC 15, next to the disabled «Эхлэх» when the start is a chosen start |

## Acceptance criteria

### A. Start and destination fields
1. **Given** the route preview opens (from a search result or from the coordinate card «Маршрут гаргах», NAV-005 AC 3–4), precise location is granted and a good fix ≤ 60 s old exists or arrives within **10 s** (NAV-005 AC 9), **When** it opens, **Then**:
   - the start field shows «Миний байршил» and the destination field shows the destination text (the result name, or «Сонгосон цэг» for a map point);
   - the fields' accessible names are «Эхлэх цэг» and «Очих газар»;
   - the route request is sent exactly as today (NAV-005 AC 5 with the NAV-011 AC 14 body). Opening the preview adds **0** requests.
2. **Given** the preview opens and the start cannot be the device position (permission never granted, denied, approximate only, location services off, or no good fix within 10 s), **When** it opens, **Then**:
   - the NAV-005 AC 8–12 location message for that case (with its actions) is shown in the result region, as today ([D148](../decisions.md));
   - at the same time, the start field is **empty** with the placeholder «Эхлэх цэг сонгох» and the accessible name «Эхлэх цэг»;
   - **0** route requests are sent, and no OS permission dialog appears unless the user acts on the message (NAV-005 AC 8).
   **When** the user then sets a chosen start (AC 4–6), **Then** within **500 ms** the location message is removed, and **one** route request is sent.
3. **Given** the start field has focus and is empty, or shows a chosen start, **When** its list opens, **Then** «Миний байршил» is the first option. **When** it is selected, **Then**:
   - with precise permission and a good fix ≤ 60 s old, the start becomes that fix, the field shows «Миний байршил», and **one** route request is sent;
   - otherwise the NAV-005 AC 8–12 flow runs (rationale, OS dialog only after «Үргэлжлүүлэх», approximate, services off, 10 s timeout → «Байршил тодорхойлж чадсангүй»). On a good fix within 10 s, the start is set and one request is sent. In every failure case the start stays as it was, and **0** route requests are sent.
   The destination field's list has no «Миний байршил» option (as NAV-004). «Миний байршил» reaches the destination only by swap (AC 11).
4. **Given** a field, **When** the user types, **Then** it is the NAV-011 search:
   - the same request profile and query assistance as the map-screen search field (NAV-011 AC 2–7: debounce 250 ms, at most 2 requests per settled query, D30 bias, 8 s timeout, 429 `Retry-After`, the states and strings), using the existing search code (`SearchController` and `search/assist`), not a copy;
   - a results list attached to the field, with the same row display as the main search;
   - only one field's list is open at a time; a response for a field that has lost focus or whose text has changed is ignored and never fills the other field's list;
   - selecting a result sets that point to the feature's coordinate (≥ 5 decimals) and shows the result name in the field; within **500 ms** the list closes, and **one** route request is sent when both points are set.
5. **Given** coordinates typed into a field, **When** the query settles, **Then** the field follows the rule that NAV-011 has for the map-screen search field when this story is verified:
   - **until the D137 change is applied:** NAV-011 AC 7 (D115), the pair is sent as typed in exactly 1 `search` request, and no point is set from it directly;
   - **after the D137 change is applied:** its typed-coordinate rule applies to both fields, and choosing the pair sets the point to that coordinate with the field text «Сонгосон цэг» and **0** `search` requests (NAV-004 AC 6 parity), with no separate coordinate card.
   QA tests the rule that NAV-011 states at verification time and names it in the test plan.
6. **Given** the preview is open, **When** the user long-presses the map, **Then**:
   - the coordinate card «Сонгосон цэг» opens with the coordinates and the NAV-011 nearest-place area (exactly 1 `reverse` request, NAV-011 AC 8–13);
   - instead of «Маршрут гаргах», the card has **two** buttons, «Эхлэх цэг болгох» and «Очих газар болгох», each ≥ **48 dp** tall and usable at once without waiting for `reverse`.
   **When** one is activated, **Then**:
   - that point is set to the coordinate, and the field text is «Сонгосон цэг» (the nearest place is never used as the field text, NAV-011 AC 9);
   - within **500 ms** the card closes, the preview is shown again and the marker of the changed point is at the new coordinate ([D150](../decisions.md));
   - **one** route request is sent when both points are set. The old route lines are removed when that request starts (they are not kept until the response arrives), and the new lines appear with the response.
   «Хаах» or Back on the card returns to the preview unchanged, with **0** route requests.
7. **Given** a field is being edited (only one field is edited at a time, [D150](../decisions.md)), **When** the user leaves it without choosing an option, by Back or by a tap on the map, **Then** the field shows its previous text again, the point is unchanged, and **0** route requests are sent. Back and a map tap are the only ways to leave a field without choosing. Clearing the text does not clear the point. The destination is never empty in the Android preview. The start is empty only in the AC 2 case.
8. **Given** both points are set, **When** the map is shown, **Then**:
   - exactly one start marker and one destination marker are visible;
   - a device start uses the NAV-005 location marker (no second marker);
   - a chosen start uses the start marker that UX defines (map-style), distinct from the location marker and the destination marker in both themes;
   - the markers' content descriptions are «Эхлэх цэг: <field text>» and «Очих газар: <field text>»;
   - the NAV-011 AC 16 camera fit includes both markers. With a **chosen start**, the fit covers only the start and destination markers; the live device position is **not** part of the fit, even when the location marker is shown ([D153](../decisions.md)). With a device start, the fit is unchanged (the start marker is the location marker).
9. **Given** the start and destination are within **10 m** of each other (haversine), whatever the kind of start, **When** they are set, **Then** **0** route requests are sent and «Эхлэх цэг, очих газар ижил байна» is shown within **500 ms** (NAV-005 AC 7).
10. **Given** a set start, **When** location fixes arrive, GPS is lost or the permission changes while the preview is open, **Then** the start does not change and **0** route requests are sent (the start is fixed when it is set, as NAV-004 Edge cases). Permission or GPS changes affect only «Эхлэх» (AC 15, NAV-005 AC 10).

### B. Swap
11. **Given** both points are set, **When** the swap button «Эхлэх цэг, очих газрыг солих» (icon button ≥ **48 × 48 dp**) is activated, **Then** within **200 ms**:
    - the points, field texts and markers swap;
    - exactly **one** route request is sent, subject to the AC 9 rule and the NAV-005 AC 7 offline and 429 rules;
    - «Миний байршил» moves with its point and keeps the fix it was set from (it is not refreshed).
    Swapping twice restores the original points and sends **2** requests in total. While either point is empty, the swap button is disabled. TalkBack still reads it, as disabled.
11a. **Given** «Миний байршил» was swapped to the destination (AC 11), so the destination holds the fix it was set from, and the device has since moved **> 10 m** (haversine) from that fix, **When** the user selects «Миний байршил» as the start (AC 3) and a good fix ≤ 60 s old is available, **Then** ([D151](../decisions.md)):
    - the start is the new fix and the start field shows «Миний байршил»;
    - the destination keeps the old fix as a map point, and its field text and marker content description become «Сонгосон цэг» («Очих газар: Сонгосон цэг»); the destination coordinate does not change;
    - exactly **one** route request is sent, from the new fix to the old fix, and «Эхлэх» follows the device-start rule of AC 15 (enabled, no O1);
    - no new string is used.
    If the device is ≤ **10 m** from that fix, AC 9 applies (N10, **0** requests); the destination field text in that case is Open question 4.
12. **Given** a route request is in flight or a 429 wait is running, **When** swap or a point change happens, **Then**:
    - at most **1** route request is in flight, and an older response never replaces a newer one (NAV-011 AC 14; QA delays the first response by 1 s);
    - during the `Retry-After` wait the fields and markers update, and **0** requests are sent until the wait ends (NAV-005 AC 7).

### C. Route requests and «Эхлэх»
13. **Given** a triggering action with both points set, **When** the request is sent, **Then** it is exactly **one** `POST {gateway}/v1/route` with the NAV-011 AC 14 body:
    - `locations` = [start, destination], each with ≥ 5 decimals and **not** rounded;
    - `alternates: 2`, the mode's `costing`, `costing_options` only per NAV-011 AC 23.
    The `GET` form is never used.
14. **Given** a 200 response, **When** either `waypoints[0].distance` (start) or `waypoints[1].distance` (destination) is **> 500 m**, **Then** the D51 notice «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» shows the larger of the two (NAV-004 AC 21). With a chosen start, this check also covers the start.
15. **Given** a route state, **When** «Эхлэх» renders, **Then** ([D147](../decisions.md)):
    - with a **device start** and precise permission, «Эхлэх» is enabled as today (NAV-005 AC 7, 10; NAV-011 AC 19);
    - with a **chosen start**, «Эхлэх» is **disabled**, and within **200 ms** of the route rendering the hint «Замчлал зөвхөн таны байршлаас эхэлнэ» (O1) is shown next to it. TalkBack reads «Эхлэх» as disabled, followed by O1;
    - activating the disabled «Эхлэх» does nothing: no guidance, **0** requests, no permission dialog.
    **When** the user makes the start «Миний байршил» again (AC 3, or a swap back, AC 11), **Then** one route request is sent, O1 disappears, and «Эхлэх» is enabled with the new route.
16. **Given** a device start and a route, **When** «Эхлэх» is activated after any point change or swap, **Then** guidance follows the selected route of the latest response with **0** extra requests (NAV-011 AC 19 unchanged). Guidance itself (NAV-005 sections C–J, NAV-012) is unchanged.
17. **Given** a chosen start, **When** a request fails or cannot be sent, **Then** every NAV-005 AC 7 / NAV-011 preview state applies unchanged, with the same strings and precedence: N9 when either point is outside the service area, N11 on «Явган» / «Дугуй», `NoRoute` with the N12 hint, 429, offline (one request by itself when the network returns, within **2 s**), unavailable with «Дахин оролдох», «Алдаа гарлаа».

### D. Turn list «Маршрутын заавар»
18. **Given** a route state, **When** the sheet is expanded (or the wide-window side sheet is shown), **Then** a list headed «Маршрутын заавар» (heading semantics) shows one row per step of `legs[0].steps` of the **selected** route, in order: the first row for `depart`, the last for `arrive`. Each row shows:
    - a manoeuvre icon (decorative, no content description);
    - the instruction text (AC 19);
    - the street name: `step.name` with zero-width characters removed, only if non-empty, shown as returned (D11);
    - the step distance in the NAV-004 AC 23 format, except on the `arrive` row.
    The list is not part of the collapsed sheet. It is not shown in states without a route.
19. **Given** a step, **When** its instruction text is produced, **Then**:
    - it comes from the existing Android ADR-0008 rules and resources (the NAV-005 banner mapping), with no second mapping and no new instruction strings;
    - it is exactly the NAV-004 AC 27 text in the UI language, including the D56 rule (`continue` / `new name` with a turning modifier → turn text).
    JVM test: for **100 %** of the cases in the shared fixture `web/src/route/maneuvers.fixture.json` (copied by Gradle, not moved), the turn-list row text equals the expected text rendered from the Android `mn` and `en` resources.
20. **Given** the recorded responses `p1-p3-car-mn`, `p1-p2-walk-mn` and `g8-roundabout-car-mn` (and RS3, RS4, RS6 if QA records them while `/health` is 200), **When** every instruction text in the Mongolian UI is scanned (street-name line excluded), **Then** it contains:
    - **0** Latin letters;
    - **0** `<`, `>`, `{`, `}` or placeholder tokens;
    - **0** zero-width characters (U+200B, U+200C, U+200D, U+FEFF);
    - **0** occurrences of «зүүн» or «баруун» that are not followed by «тийш», «талаа», «талаас», «талын», «талд», «зүг», «хойд», «өмнө» or «эгнээ» (C2);
    - **0** glossary Avoid terms (as NAV-004 AC 28).
    Every text is one of the NAV-004 AC 27 texts. In the English UI (`p1-p3-car-en`, `p1-p2-walk-en`), every instruction text contains **0** Cyrillic letters outside the street-name line.
21. **Given** *k* ≥ 2 routes, **When** another route is selected (line tap or «Маршрут сонгох», NAV-011 AC 17), **Then** within **200 ms** the list shows the steps of the newly selected route, scrolled to its first row, and **0** requests are sent. After a new response the list shows route 1 (NAV-011 AC 21).
22. **Given** the list, **When** the user taps a row (or double-taps it with TalkBack), **Then** within **1 s**:
    - the camera centres on that step's `maneuver.location` at zoom **17**, or at the current zoom if it is higher;
    - the point is inside the map area not covered by the sheet or the top bar, with ≥ **40 dp** padding (UX decides whether the sheet collapses to achieve this);
    - the route, the selection and the list's scroll position stay, and **0** requests are sent.
    Every row is ≥ **48 dp** tall.
23. **Given** TalkBack, **When** the list is explored, **Then**:
    - the list is a collection named «Маршрутын заавар», with row positions announced;
    - each row's content description is the instruction text, the street name and the distance, joined with ", " (the `arrive` row has no distance);
    - icons are not focusable;
    - the list follows the summary and the «Маршрут сонгох» group in reading order, before «Эхлэх».
24. **Given** a route and the list are shown, **When** the UI language is switched, **Then**:
    - labels and instruction texts switch within **1 s**, with **0** route requests (the text is generated on the client, ADR-0008);
    - street names are unchanged;
    - the selection and the list are kept.
    The same holds for a theme change and rotation (NAV-011 AC 20).
25. **Given** a long route (the recorded RS6 response P1 → X1, or a synthetic response with **500** steps if RS6 cannot be recorded), **When** the list model is built in a JVM test, **Then** it completes within **200 ms**. A Robolectric/Compose test shows that the list is rendered lazily: only the visible rows plus a small buffer are composed. Scrolling smoothness is a real-device check (AC 38).
26. **Given** any mode («Машин», «Явган», «Дугуй») and any kind of start, **When** a route is shown, **Then** the list follows AC 18–25 unchanged.

### E. Layout, accessibility and strings
27. **Given** a route state at 360×640 dp and font scale 100 %, in both languages and both themes, **When** the sheet is collapsed, **Then**:
    - the start text, the destination text, the summary and «Эхлэх» are visible without dragging, plus O1 when it is shown (AC 15);
    - the map band above the collapsed sheet is ≥ **160 dp** (NAV-011 screen spec P3);
    - «© OpenStreetMap contributors» is not covered (NAV-005 AC 2).
    At font scale 200 %, the NAV-011 P2 fallback applies (the top part gives way first). In wide windows, the side sheet holds the fields, swap and list (NAV-011 P5), and the coordinate card is the narrower card at the start edge. That card is **one layout** for both the long-press card (AC 6) and the typed-coordinate card of the map screen (NAV-011 AC 7a), as built ([D152](../decisions.md)). Checked with the UX layout checker and a Compose test.
28. **Given** the new controls (fields, swap, card buttons, list rows, O1), **When** they render, **Then**:
    - touch targets are ≥ **48 × 48 dp**;
    - text contrast is ≥ **4.5:1** in both themes;
    - at font scale 200 % no new text is cut mid-word;
    - D66 applies unchanged.
29. **Given** the Android resources, **When** the NAV-005 AC 61 checks run, **Then**:
    - every new user-facing string comes from resources, with identical `mn` and `en` key sets;
    - there are **0** hard-coded literals;
    - every `mn` value matches a glossary term exactly: N2, N3, N4, N5, N9, N10, N17, N18, T6, "Origin / starting point", "My location", "Destination", the manoeuvre rows and **O1** (section 2.6, `needs native review`). No other new Mongolian.
30. **Given** the NAV-012 battery hint B2 on the route preview (NAV-012 AC 26), **When** O1 and B2 would both be shown, **Then** both are visible after expanding the sheet, neither covers «Эхлэх» or the attribution, and the NAV-012 AC 26 rules for B2 are unchanged. UX defines the order.

### F. Privacy, session, typing lock, regression and verification
31. **Given** a full session (start by search, by map point and by «Миний байршил», swap, every mode, list taps, a language switch), **When** all requests are captured, **Then**:
    - every request goes to the configured gateway, and **0** to other hosts (NAV-005 AC 65);
    - coordinates appear only in route POST bodies, the D30-rounded search bias, the user-chosen `reverse` point, and typed coordinates as text in `q` while AC 5's first bullet applies;
    - the device position appears only in a route POST body where a point is «Миний байршил», and never in a `search` or `reverse` request;
    - list-row taps and language switches send **0** route requests (AC 22, AC 24), and each point change or swap sends at most the one request stated in AC 4, 6 and 11;
    - the NAV-005 AC 67 log and storage scan finds **0** coordinates after the session. The start and destination of a preview are never stored across app restarts (the NAV-012 restore record applies only during guidance, which needs a device start).
32. **Given** a preview with a chosen start, **When** it is closed («Хаах», Back) and a new preview opens from a search result or the coordinate card, **Then** the start resets to «Миний байршил» and follows AC 1–2 again: a chosen start is not kept, on every new route preview ([D149](../decisions.md)). The mode is kept for the app session (NAV-011 AC 22). Nothing is stored.
33. **Given** a chosen start and a route, **When** the phone rotates, or the theme or language changes, **Then**:
    - the points, the swap state, the list, the selection and the sheet state are kept, with **0** route requests;
    - «Миний байршил» and «Сонгосон цэг» switch to their `en` values ("My location", "Selected point") and back; result names in the fields stay as they were.
    After process death in the preview, no preview is restored (NAV-012 restores only guidance).
34. **Given** the NAV-011 typing lock is engaged, **When** the user taps a field, **Then**:
    - within **500 ms** the lock card (K1, K3, «Би зорчигч») is shown and the keyboard does not open (NAV-011 AC 31);
    - for the start field, the «Миний байршил» option is shown and selectable at the same time, without the keyboard (selecting an option is allowed, NAV-011 AC 33);
    - swap, long-press with «Эхлэх цэг болгох» / «Очих газар болгох», the list rows, tabs, route options and «Эхлэх» stay usable;
    - «Би зорчигч» unlocks typing as NAV-011 AC 34.
35. **Given** the existing Android suite, **When** `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required` runs in `mobile/android`, **Then**:
    - it passes;
    - the only changed NAV-005 / NAV-011 / NAV-012 tests are those that assert behaviour this story replaces (the start is always «Миний байршил»; the points block is display only; the coordinate card in the preview offers «Маршрут гаргах»), each listed in the QA handoff with the reason;
    - no other assertion is weakened.
    `./gradlew :app:assembleDebug` builds. `npm test` in `web/` passes unchanged (the shared fixture is not modified).
36. **Given** the repository after this story, **When** it is inspected, **Then**:
    - NAV-018 code is in its own package(s) where possible;
    - shared files (`SearchController`, `PreviewController`, `strings.xml` in `values/` and `values-en/`, `MainActivity`, `AppViewModel`, `RoutePreviewSheet`) show only targeted NAV-018 edits;
    - **no line added by the parallel D137 change run or the NAV-012 battery-row fix is removed or reverted** (the reviewer checks the diff of shared files);
    - there are no secrets, hostnames or IPs other than loopback;
    - the shared dev stack was not stopped, restarted or rebuilt, and live requests stayed at ≤ 2 per second.
37. **Given** light-QA mode, **When** QA reports, **Then**:
    - the test plan `docs/qa/test-plans/NAV-018.md` maps **every** AC to a JVM/Robolectric test, or to "not verified" with the reason;
    - only the tests of the changed areas plus the existing Android suite are run, with no long suites;
    - live checks (recording RS3, RS4 and RS6, AC 20 and AC 25) run only if `/health` is 200;
    - everything not run is listed for the full QA before any public release.
38. **Given** the checks that need a real Android phone, **When** QA reports, **Then** these are listed as **not verified** (the PO has no Android phone yet):
    - field editing with several OEM keyboards, including Mongolian Cyrillic keyboards;
    - long-press with «Эхлэх цэг болгох» / «Очих газар болгох» on a device;
    - swap and the camera after a list-row tap;
    - turn-list scrolling on a 500-step route;
    - TalkBack on a device;
    - the lock card with the «Миний байршил» option in a moving car;
    - the layout at 360×640 on real hardware with OEM font scaling.

## Edge cases
- **GPS lost / no fix:** with a device start already set, the start does not change (AC 10). With no usable location, the start field is empty and the user chooses a start (AC 2). «Миний байршил» as an option falls back to the NAV-005 failure texts (AC 3).
- **Location permission never granted, denied or approximate only:** the NAV-005 message stays, and the preview works with a chosen start (AC 2). «Эхлэх» stays disabled with a chosen start anyway (AC 15).
- **No network:** field search shows «Интернэт холболт алга» with 0 requests (NAV-011 AC 7); a point set from the map still works; the route shows the offline state and requests once by itself when the network returns (AC 17).
- **Rate limits (429):** points and swap update during the wait, with 0 requests (AC 12).
- **No result:** «Илэрц олдсонгүй» in the field list; no route → «Маршрут олдсонгүй»; a start outside coverage (for example X2 typed as a start) → N9 (AC 17).
- **Off-route:** not applicable to the preview. With a device start, guidance and reroute are NAV-005. With a chosen start, there is no guidance (AC 15).
- **Cyrillic/Latin:** "Sukhbaatar" vs «Сүхбаатар», "suhbaatar", Russian-layout «Сухбаатар» and «БЗД 4-р хороо» behave in the fields exactly as in the main search field (AC 4, NAV-011 AC 2–6).
- **Typed coordinates:** follow NAV-011 AC 7 until the D137 change is applied, then its rule (AC 5).
- **Start equals destination** (the same result picked twice, or a long-press on the user's own position): N10 with 0 requests (AC 9).
- **Swap with «Миний байршил»:** the destination becomes the device position at the time the start was set, so «Эхлэх» is disabled with O1 until the user swaps back (AC 11, 15). A tester who has moved since will see a route from the old fix. This is accepted, because the start is fixed when it is set (AC 10). If the user has moved > 10 m and then picks «Миний байршил» as the start, the destination turns into «Сонгосон цэг» (the old fix as a map point) and the route runs from the new fix ([D151](../decisions.md), AC 11a).
- **Camera with a chosen start far from the device:** the fit covers only the start and destination markers, so a driver planning a trip across town is not zoomed out to include its own position (AC 8, [D153](../decisions.md)).
- **Point far from any road** (ger plot, steppe), now also for the start: snap notice for the larger distance (AC 14).
- **Unpaved roads:** the avoid toggle on «Машин» works for any start (NAV-011 AC 23). Countryside turn lists can contain many `continue` rows on unnamed tracks (R2).
- **Winter:** no seasonal rule. Rows and buttons are ≥ 48 dp for gloves (AC 22, 28). A cold GPS start can take more than 10 s, so the start field falls back to empty with «Байршил тодорхойлж чадсангүй» (AC 2–3) and the user can still choose a start.
- **Very long routes:** lazy list (AC 25).
- **Language, theme, rotation:** AC 24, AC 33.
- **Typing lock while moving:** the lock card, with «Миний байршил» still selectable (AC 34).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **Street names** (NAV-004 R4): Valhalla returns mostly `name` (Cyrillic). Many ger-district and countryside streets are unnamed | Turn rows without a street name. Tourists see Cyrillic names (D11) | Empty names are omitted (AC 18). Mapping through the `osm-data` lane (human mappers only) |
| R2 | **Countryside tracks and `surface`** (NAV-004 R2, R11) | Long lists of «Чигээрээ явна уу» rows on unnamed tracks, and optimistic durations | Accepted for Phase 1. The list stays readable and lazy (AC 25) |
| R3 | **Address coverage** (`addr:*`, khoroo, ger plots; NAV-001 R9) | A pickup address may not be searchable as a start | The map point («Эхлэх цэг болгох») is the fallback (AC 6). The nearest place is shown, never as the field text |
| R4 | **`name:en` coverage** (NAV-001 R6) | Latin queries in the fields depend on NAV-011 assistance | NAV-011 golden set (tier B ≥ 80 %) covers the fields, because they use the same code (AC 4) |
| R5 | **Valhalla manoeuvre output** (`type`/`modifier` combinations not in the fixture) | A step type outside the NAV-004 AC 27 table shows a fallback text | ADR-0008 fallbacks, AC 20 scans. A new combination is added to the shared fixture by the architect |
| R6 | **`maxspeed` coverage, no traffic** (NAV-001 R8) | Durations and «Хүрэх цаг» for a planned trip at another time of day are wrong, because no departure time is supported | Out of scope (departure time). Phase 3 traffic |
| R7 | **Personal data:** a chosen start (for example a customer's pickup) goes to the backend in the route body (D9) | The legal gate before outside testers use Singapore staging | Team-only staging (D9 / D26). NAV-008 AC 24 |
| R8 | **Parallel edits** in shared files (D137 change run, NAV-012 housekeeping fix) | Merge conflicts or lost changes | AC 36. Own packages, latest-file reads |
| R9 | **Open question 1 changes «Эхлэх»** | Closed: the PO chose (a), [D147](../decisions.md). Remaining risk is the O1 wording only (`needs native review`) | The NAV-007 panel or a PO pre-review may change the O1 wording; only resources and test expectations change |

## Out of scope
- Guidance from a chosen start (a simulated or step-through "preview drive"). Guidance needs a device start ([D147](../decisions.md)).
- More than two points (waypoints «дайрах цэг»), dragging the route, departure or arrival time.
- Saved places (home, work), recent starts, favourites.
- A turn list during active guidance (the guidance screen is NAV-005).
- Changing the typed-coordinate rule itself (the D137 change on NAV-011).
- iOS (NAV-015). Any change to the NAV-003 / NAV-004 web behaviour.
- Any `openapi.yaml` or gateway change.
- A toll option (D60).

## Open questions
Questions 1–3 were **decided by the PO on 2026-10-04** ("All as recommended", option (a) each: [D147](../decisions.md), [D148](../decisions.md), [D149](../decisions.md)). Question 4 is new and does not block anything.
1. **What «Эхлэх» does when the start is not the device position** (AC 15, O1). Options:
   - (a) «Эхлэх» disabled, with the hint O1 «Замчлал зөвхөн таны байршлаас эхэлнэ» (new glossary row, `needs native review`). The user swaps back or picks «Миний байршил» to navigate (BA default);
   - (b) «Эхлэх» starts guidance from the current device position to the destination: the chosen start is ignored, and one new route request is sent (no new string, but it silently drops the user's choice);
   - (c) «Эхлэх» hidden with a chosen start (no new string, but nothing explains why it is gone).
   *Recommendation: (a).* It is what Google Maps does ("Start" is not offered from another start), it never sends the driver along a route that starts somewhere else, and only one new string is needed. **Decided: (a), [D147](../decisions.md).**
2. **Location messages when the preview opens without usable location** (AC 2). Options:
   - (a) keep the NAV-005 AC 8–12 messages (with their actions) and also show the empty start field (BA default);
   - (b) web parity: no message, only the placeholder «Эхлэх цэг сонгох». The permission flow starts only when the user picks «Миний байршил».
   *Recommendation: (a).* Most Android users want to start from where they are, so the message helps them, and NAV-005 AC 8–12 and their tests stay unchanged. **Decided: (a), [D148](../decisions.md).**
3. **Whether a chosen start is kept** (AC 32). Options:
   - (a) reset to «Миний байршил» on every new preview (BA default);
   - (b) keep the chosen start for the rest of the app session.
   *Recommendation: (a).* Most previews start from the device position. A start kept from an earlier plan would disable «Эхлэх» unexpectedly (AC 15). **Decided: (a), [D149](../decisions.md).**
4. **Destination text when «Миний байршил» is picked as the start within 10 m of the swapped fix** (AC 11a, last sentence; D151 covers only > 10 m). AC 9 already gives N10 with 0 requests. Options:
   - (a) the destination also turns into «Сонгосон цэг», as in D151, so two fields never both show «Миний байршил» (no new string);
   - (b) both fields show «Миний байршил» until the user changes a point.
   *Recommendation: (a).* One rule for both distances, and it is simpler to test. Non-blocking: QA tests the built behaviour and reports it.

Also decided on 2026-10-04 (not story open questions): AC 6 and AC 7 describe the built behaviour, old route lines removed when the new request starts and one field edited at a time, left by Back or a map tap ([D150](../decisions.md)); a swapped «Миний байршил» destination turns into «Сонгосон цэг» when the user has moved > 10 m and picks «Миний байршил» as the start ([D151](../decisions.md), AC 11a); one narrower start-edge coordinate card layout in wide windows for both entries ([D152](../decisions.md), AC 27); the camera fit with a chosen start leaves out the device position ([D153](../decisions.md), AC 8).

## Traceability
| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–10 | `docs/design/screens/NAV-018-android-origin-turn-list.md` (UX, TBD), delta on the NAV-011 S3 spec; NAV-004 screen spec (reference) | `search`, `reverse`, `postRoute` | ADR-0006, ADR-0012 (search reuse) | TBD (`…/preview/points`) | JVM / Robolectric (TBD) | N2, N3, N17, N18, T6; D30, D115, D137, D140, D145; NAV-005 AC 8–12; D148 (AC 2), D150 (AC 6, 7), D153 (AC 8 camera fit, `PointRules.isChosenStart`); AC 8 location marker = NAV-005 section N dot, origin stays the raw fix (D171, D177, note 2026-10-04) |
| AC11–12 (incl. AC 11a) | swap (UX, TBD) | `postRoute` | — | TBD | JVM (TBD) | N4, T6; D151 (AC 11a); Open question 4 |
| AC13–17 | «Эхлэх» footer, O1 hint (UX, TBD) | `postRoute` | ADR-0009 (guidance unchanged) | TBD | JVM / Robolectric (TBD) | O1; D51; D147 (AC 15) |
| AC18–26 | turn list (UX, TBD); map-style (camera, start marker) | `postRoute` (`legs[].steps[]`) | ADR-0008 (accepted), shared fixture | TBD (`…/preview/turnlist`), `instructions/ManeuverRules.kt` (reused) | fixture JVM test, text scans, Robolectric list (TBD) | N5; C2; NAV-004 AC 26–30, 45 |
| AC27–30 | layout rules (UX checker) | — | — | TBD | Compose / Robolectric (TBD) | D66; NAV-012 AC 26 (B2); D152 (AC 27 wide-window card) |
| AC31–38 | — | all | — | `mobile/android/**` | existing Android suite; test plan `docs/qa/test-plans/NAV-018.md` | D9, D108 pattern, D110, D113, D136, D149 (AC 32); coordination with the D137 run and the NAV-012 fix |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-04 | NAV-011 follow-up "Android origin choice and turn list" (D112 draft row; triage 2026-10-04, PO-confirmed P2 / standard, [D136](../decisions.md); feature-delivery lane) | Created as **NAV-018** (next free ID after NAV-017; replaces the backlog draft row). 38 AC in sections A–F: start and destination fields with NAV-011 search reuse, «Миний байршил» option and the NAV-005 permission flow, map point as start or destination on the coordinate card, swap, request rules, «Эхлэх» only from the device start (Open question 1 default), turn list «Маршрутын заавар» from the ADR-0008 Android rules with fixture and text-quality checks, layout and TalkBack, privacy, typing lock, regression, coordination and light-QA verification. Priority should (P2), phase 1, size M. Glossary row **O1** added in new section 2.6 (`needs native review`). Three non-blocking open questions with BA defaults. NAV-011 not edited (waiting for acceptance; parallel D137 run) | Refine the D112 follow-up to `ready` for UX and the architect |
| 2026-10-04 | PO answer "All as recommended" in chat to seven NAV-018 items ([D147–D153](../decisions.md)) | **Open questions 1–3 decided**, option (a) each: AC 15 «Эхлэх» disabled with O1 for a chosen start (D147; Context, User-facing strings, R9 and Out of scope reworded from "default" to decided); AC 2 keeps the NAV-005 AC 8–12 message next to the empty start field «Эхлэх цэг сонгох» (D148); AC 32 resets a chosen start to «Миний байршил» on every new preview (D149). **AC 6** bullet 2 reworded to the built behaviour: the changed marker moves at once and the old route lines are removed when the new request starts (D150). **AC 7:** one field is edited at a time; Back and a map tap are the only ways to leave without choosing ("the other field" removed) (D150). **New AC 11a:** after a swap of «Миний байршил» to the destination and a move > 10 m, picking «Миний байршил» as the start turns the destination into «Сонгосон цэг» (old fix kept), 1 request, «Эхлэх» enabled; no new string (D151); ≤ 10 m case → new non-blocking Open question 4. **AC 8:** with a chosen start the camera fit covers only the start and destination markers, not the live device position (D153). **AC 27:** in wide windows, one narrower start-edge coordinate card layout for both long-press and typed-coordinate cards (D152). Edge cases (swap with «Миний байршил», camera with a chosen start), Context decision list and Traceability updated. Glossary O1 row already present (section 2.6, `needs native review`); only its section intro updated | Record the PO decisions and align AC 6 / AC 7 with the build so QA tests what was built |
| 2026-10-04 | NAV-005 change "calmer browse location dot" (triage row 2026-10-04 "Location position jumps or drifts inside or near buildings"; [D171–D178](../decisions.md)) | **Note only, no AC change.** The "NAV-005 location marker" in AC 8 is now the filtered NAV-005 section N dot (accuracy circle, hold while standing, stale state; NAV-005 AC 74–79). The device start used for the route stays the raw good fresh fix (NAV-005 AC 5, 9; [D177](../decisions.md)), so the route line may start some metres away from a held dot. AC 10 (start fixed once set) is unaffected. The NAV-018 device-start tests run as regression for the NAV-005 change | Keep the device-start wording traceable to the new NAV-005 dot rules without a parallel story |
