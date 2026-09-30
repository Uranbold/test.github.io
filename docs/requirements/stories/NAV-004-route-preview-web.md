---
id: NAV-004
title: Route preview A→B with alternatives, mode tabs, distance/ETA and a Mongolian turn list in the web demo
phase: 0
priority: must
size: L
needs_design: true
needs_backend: false   # true only if the architect's instruction-text ADR picks option (c) gateway post-processing
needs_mobile: true     # web client in web/ (mobile-engineer owns web/)
status: ready
---

# NAV-004: Route preview A→B with alternatives and mode tabs (web demo)

## Story
As a **UB commuter by car**, I want **to pick a destination from search or the map, get a route from my location (or a start I choose) by car, on foot or by bike, compare up to three routes with their distance, duration and «Хүрэх цаг», and read the turns in correct Mongolian**, so that **I can decide how and which way to go before I leave, in my own language**.

Secondary personas:
- **Taxi / delivery driver**: compares the alternatives and the arrival time for a customer's address, and taps a coordinate on the map when the address is not searchable (ger districts).
- **Pedestrian**: uses the «Явган» tab for walking routes in the city centre, where one-way streets do not apply.
- **Tourist (English UI)**: sees the same preview with English labels and English turn text. Street names and map labels stay Cyrillic (D11).
- **Intercity / countryside driver**: routes to Erdenet or a soum centre, turns on «Шороон замаас зайлсхийх» when possible, and gets a clear message instead of a spinner on a weak connection or when the start/destination is outside coverage.

## Context
- Research `docs/osm-navigation-research.md` §7 Phase 0 PoC ("web demo … with search, route A→B, alternatives, turn-by-turn list"), §2 row 5 (alternative routes), §6 screen flow (place card → route preview with alternatives, mode tabs, avoid options). Team design `docs/team/agent-architecture.md` §5 lists NAV-004. Backlog row NAV-004 (draft since the team design).
- **Client:** the NAV-002/NAV-003 web demo in `web/` (plain TypeScript + Vite + MapLibre GL JS, one MapLibre `Map` owned by our code). NAV-003 reserved the place for «Маршрут гаргах» on the place card below the coordinates (`docs/design/screens/NAV-003-search.md` › Components › Place card). NAV-004 adds the route preview panel, route layers, origin/destination markers and the turn list. Ferrostar Web components are **not** required for the preview (they arrive with NAV-005), but the instruction-text logic should be reusable by NAV-005 (see "Design question for the architect").
- **API (no contract change expected):** `docs/architecture/api/openapi.yaml` **0.4.1**, operation `postRoute` (`POST /v1/route`, Valhalla 3.9.0 pass-through):
  - request `ValhallaRouteRequest`: `locations` (2–20), `costing` (`auto`, `pedestrian`, `bicycle` among the enum), `costing_options.<costing>.exclude_unpaved`, `format: osrm`, `banner_instructions`, `voice_instructions`, `language` (`mn-MN`, `en-US`), `units: kilometers`, `alternates` 0–2 (server limit 2);
  - 200 `OsrmRouteResponse` with 1 to 3 routes ("fewer alternates than requested is normal"), `waypoints[].distance` = snap distance in metres;
  - 400 `OsrmError` `NoSegment` (outside coverage / no road near), `NoRoute` (for example with `exclude_unpaved`), `InvalidOptions`, `InvalidValue`, `TooBig`, `DistanceExceeded`, or `ValhallaError` (for example `error_code` 171); 413 body > 256 KB; **429 `RateLimited` with `Retry-After`** (exposed via `Access-Control-Expose-Headers`; missing or unreadable → wait 5 s, since 0.4.1); 502/503 `UpstreamUnavailable` (within 5 s); 504 `UpstreamTimeout` (gateway read timeout for routing **10 s**).
  - Client rule for a route (0.4.0): show the retryable state and retry at most once after the delay.
  - `exclude_unpaved` is contracted for `auto` (`ValhallaCostingOptions`). Whether an equivalent option exists for `pedestrian` / `bicycle` is the architect's call (AC 12). **No toll option** (request: Mongolia has no toll roads; see Open question 2).
- **Routing engine:** our own Valhalla behind `/v1/route` (R1, PO decision D37). No Hamuga routing.
- **Decisions already made (not open):** Mongolian first, English second; the English UI keeps Cyrillic map labels (D11); supported browsers are current desktop Chrome, Edge and Firefox plus Android Chrome, automated tests on desktop Chromium only (D14); the demo is **local dev only** until staging exists (NAV-008). The static build is also published publicly on the PO's Hostinger web hosting with **no backend** (D44); routing must be off there (NAV-002 AC 53, and AC 54 until the NAV-008 AC 24 legal review says go). Origin wording: generic «эхлэх цэг», default label «Миний байршил» when the start is the device position; the recenter button is «Байршил руу буцах» (glossary "Origin / starting point", "My location", "Recenter", PO 2026-09-29).
- **Reference points and environment:** P1–P6, X1, X2 from NAV-001 ("Reference test locations"): P1 Sükhbaatar Square (47.9189, 106.9176), P2 State Department Store (47.9139, 106.9044), P3 Zaisan (47.8858, 106.9173), P4 Gandan (47.9215, 106.8950), P5 railway station (47.9095, 106.8835), P6 Chingeltei ger district (47.9600, 106.9000), X1 Erdenet (49.0270, 104.0440), X2 Beijing (39.9042, 116.4074). Reference environment = NAV-002/NAV-003 reference environment (NAV-001 machine, stack healthy and warm, desktop Chromium, viewport 1366×768, Vite dev server `http://localhost:5173`).
- **Coordination (binding for this delivery):**
  - The shared dev stack at `http://localhost:8080` is never stopped, restarted or rebuilt. If `/health` is not 200, wait and retry.
  - A NAV-003 follow-up runs in parallel in `web/` and `tests/e2e/nav003/` (the static-build setting that turns search/reverse off, and golden A8/B8/AC 41 test updates). NAV-004 code goes into its own modules where possible (for example `web/src/route/**`, `web/src/ui/routePreview*.ts`). Before editing shared files (`app.ts`, the i18n JSON files, `config.ts`) the mobile engineer reads the latest version, keeps the static-build setting working, and never reverts other changes. NAV-004 **reuses** that static-build setting for routing (AC 53); it does not add a second one.
  - NAV-004 tests live under `tests/e2e/nav004/` (Test approach).

### Design question for the architect (instruction text source): record in an ADR
Valhalla 3.9.0 `mn-MN` narrative has known defects (NAV-007 F1–F10): English roundabout phrases (F1), a broken `<ORDINAL VALUE>` placeholder (F2), a typo (F3), a zero-width space (F4), a wrong verb (F5), bare «зүүн»/«баруун» for both left/east (F6), mixed register (F7), hyphen-attached case suffixes (F8), abbreviated ordinals in voice (F9), no locative (F10). The architect decides where the on-screen turn text comes from and records it in a new ADR (next free number, currently 0008):
- (a) Valhalla's `mn-MN` `maneuver.instruction` / banner text as-is;
- (b) client-side text built from the OSRM maneuver `type` / `modifier` / `exit` / `bearing_after` and the glossary templates in AC 27 (resource files, `mn` and `en`);
- (c) gateway post-processing of Valhalla's text.

**BA position:** the AC below are written so that they hold whatever the source, but AC 27–29 require glossary wording (C1 polite form, C2 «тийш» vs «зүг», C3/C6/C7 units, banner ordinals «2-р»), which option (a) cannot meet with 3.9.0 (F1, F2, F6, F7). The request recommends **(b)**: consistent with the glossary, no backend change, and reusable for NAV-005 banners. If the ADR chooses (c), `needs_backend` becomes true and the gateway work must avoid the NAV-008 rate-limit/`real_ip`/header files.

### Terms used in this story
- **Route preview panel:** the panel titled «Маршрут харах» that holds the origin and destination fields, mode tabs, the avoid toggle, route options, summary and turn list.
- **Point:** the origin or the destination. Each is set from (1) «Миний байршил», (2) a search result, (3) a map coordinate, or (4) typed coordinates.
- **Triggering action:** an action after which a new route is needed: a point is set or changed, a mode tab settles (AC 11), swap, the avoid toggle, «Дахин оролдох», the online resume (AC 38), or a language switch when the text source needs it (AC 50).
- **Location is on:** the user has activated my location (NAV-002 AC 19) and the last fix is **≤ 60 s** old (same freshness rule as NAV-003 AC 9).
- **Reference route set:** RS1 P1→P3 car, RS2 P1→P2 walk, RS3 P1→P4 car (alternatives), RS4 P4→P5 bike, RS5 P1→P6 car with avoid unpaved, RS6 P1→X1 car, RS7 P1→X2 car (out of coverage), RS8 P1→X1 walk (expected `DistanceExceeded`; QA records what the dev server returns).

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. Rows NAV-004 needed and that were missing are **N1–N23**, **added to the glossary on 2026-09-30** in new section 2.1 "Route preview (NAV-004)", all `needs native review`. Implementation uses exactly these strings; if another string is needed, request it from the business-analyst first.

**Existing glossary terms used**

| Use in NAV-004 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Place card / coordinate card button | Get directions (button) | «Маршрут гаргах» | "Directions" |
| Panel heading | Route preview | «Маршрут харах» | "Route preview" |
| Origin field accessible name / generic term | Origin / starting point | «Эхлэх цэг» | "Start" |
| Origin when it is the device position | My location / Origin | «Миний байршил» | "My location" |
| Destination field accessible name | Destination | «Очих газар» | "Destination" |
| Mode tabs (car, walk) | Travel mode | «Машин», «Явган» | "Car", "Walk" |
| Avoid toggle | Avoid unpaved roads | «Шороон замаас зайлсхийх» | "Avoid unpaved roads" |
| Alternative route (accessible description of a non-selected route) | Alternative route | «Өөр маршрут» | "Alternative route" |
| ETA label | ETA (arrival time) | «Хүрэх цаг» (for example «Хүрэх цаг 14:35») | "Arrive at" (for example "Arrive at 14:35") |
| Units | Metre / Kilometre (on-screen unit); C6 | «м», «км» | "m", "km" |
| Loading | Loading (G1) | «Ачаалж байна…» | "Loading…" |
| No route | No route found | «Маршрут олдсонгүй» | "No route found" |
| Offline | No connection | «Интернэт холболт алга» | "No internet connection" |
| Rate-limited | Too many requests (T5) | «Түр хүлээгээд дахин оролдоно уу» | "Too many route requests. Wait a moment and try again" (new `en` value under a route key; the `mn` term is the same concept) |
| Retry | Try again | «Дахин оролдох» | "Try again" |
| Close the panel | Close (dismiss) (G6) | «Хаах» | "Close" |
| Unexpected failure | Generic error | «Алдаа гарлаа» | "Something went wrong" |
| Selected map point | Selected point (T6) | «Сонгосон цэг» | "Selected point" |
| Location could not be determined | G5 | «Байршил тодорхойлж чадсангүй» | "Your location could not be determined" |
| Manoeuvre texts | Section 3 rows, "Arrival (voice)", C2, C4 banner ordinals | see AC 27 | see AC 27 |

**Glossary additions N1–N23 (added 2026-09-30, all `needs native review`)**

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| N1 | Bicycle (travel mode tab) | «Дугуй» | "Bike" | Mode tab, costing `bicycle` |
| N2 | Choose starting point (placeholder) | «Эхлэх цэг сонгох» | "Choose starting point" | Origin field placeholder |
| N3 | Choose destination (placeholder) | «Очих газар сонгох» | "Choose destination" | Destination field placeholder |
| N4 | Swap start and destination | «Эхлэх цэг, очих газрыг солих» | "Swap start and destination" | Accessible name and tooltip of the swap button |
| N5 | Directions (turn list name) | «Маршрутын заавар» | "Directions" | Heading / accessible name of the turn list |
| N6 | Route option (numbered) | «Маршрут {n}» | "Route {n}" | Route options list, `{n}` = 1–3 |
| N7 | Routes count | «{count} маршрут олдлоо» | "{count} routes found" ("1 route found") | Screen-reader announcement |
| N8 | Routing unavailable | «Маршрутын үйлчилгээ түр ажиллахгүй байна» | "Routing is temporarily unavailable" | Gateway down / 5xx / timeout / CORS; static public build |
| N9 | Outside the service area | «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна» | "The start or destination is outside the service area" | 400 `NoSegment` / error 171 |
| N10 | Start and destination are the same | «Эхлэх цэг, очих газар ижил байна» | "The start and destination are the same" | AC 14 |
| N11 | Too far on foot or by bike | «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» | "This distance is too far on foot or by bike" | 400 `DistanceExceeded` on «Явган» / «Дугуй» |
| N12 | No route with avoid unpaved (hint) | «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу» | "Turn off “Avoid unpaved roads” and try again" | Under «Маршрут олдсонгүй» when the avoid toggle is on |
| N13 | Nearest road is far from the point | «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» | "The nearest road is {distance} from the chosen point" | AC 21 |
| N14 | Take the on-ramp | «Орох замаар орно уу»; with side «Баруун талын орох замаар орно уу» / «Зүүн талын орох замаар орно уу» | "Take the ramp"; "Take the ramp on the right" / "on the left" | AC 27, `on ramp` |
| N15 | Take the off-ramp (exit) | «Гарах замаар гарна уу»; with side «Баруун талын гарах замаар гарна уу» / «Зүүн талын гарах замаар гарна уу» | "Take the exit"; "Take the exit on the right" / "on the left" | AC 27, `off ramp` |
| N16 | Next-day arrival suffix | «+{days} өдөр» | "+{days} day" / "+{days} days" | AC 25 |
| N17 | Set as starting point | «Эхлэх цэг болгох» | "Set as start" | Coordinate card during preview (AC 7) |
| N18 | Set as destination | «Очих газар болгох» | "Set as destination" | Coordinate card during preview (AC 7) |
| N19 | Travel mode (tab list name) | «Зорчих хэлбэр» | "Travel mode" | Accessible name of the mode tab list |
| N20 | Choose a route (list name) | «Маршрут сонгох» | "Choose a route" | Accessible name of the route options group |
| N21 | Hour (on-screen unit) | «ц» | "h" | Durations (C6), e.g. «1 ц 25 мин» |
| N22 | Minute (on-screen unit) | «мин» | "min" | Durations (C6), e.g. «25 мин» |
| N23 | Enter the roundabout (no exit number) | «Тойрогт орно уу» | "Enter the roundabout" | AC 27, roundabout without `exit` |

`{n}`, `{count}`, `{distance}` and `{days}` are placeholders kept literally in resource files.

## Acceptance criteria

### A. Entry points, origin and destination
1. **Given** a place card opened from a search result (NAV-003 AC 21), **When** it is shown, **Then** it has a «Маршрут гаргах» button below the coordinates. **When** the button is activated (click, tap, Enter or Space), **Then** within **500 ms** the route preview panel opens with the heading «Маршрут харах», the destination field shows the result name, and the destination is the feature's point (the coordinates shown on the card, ≥ 5 decimals).
2. **Given** a coordinate card (NAV-003 AC 25 or 26), **When** «Маршрут гаргах» is activated, **Then** as AC 1, with the destination field showing «Сонгосон цэг» and the destination = the chosen coordinate (not the location of the `reverse` result).
3. **Given** location is on when «Маршрут гаргах» is activated, **When** the panel opens, **Then** the origin field shows «Миний байршил», the origin is the last fix (≥ 5 decimals), and the route request is sent at once (AC 10). NAV-004 does **not** call the Geolocation API in this case, and the NAV-002 location marker is used as the origin marker (no second marker).
4. **Given** location is **not** on (never activated, denied, unavailable, or last fix > 60 s old), **When** the panel opens, **Then** the origin field is empty with the placeholder «Эхлэх цэг сонгох» and accessible name «Эхлэх цэг», focus moves to the origin field, **no** route request is sent until an origin is set, and the browser shows **no** location prompt (NAV-002 AC 18 stays true).
5. **Given** the origin field has focus, **When** it is empty, **Then** its list shows «Миний байршил» as the first option. **When** that option is selected:
   - if location is on, the last fix becomes the origin and the route is requested;
   - otherwise the NAV-002 location request starts (this user action may show the browser prompt); on a fix within **10 s** the origin is set and the route requested; on denial the NAV-002 AC 21 message appears; on `POSITION_UNAVAILABLE` or timeout «Байршил тодорхойлж чадсангүй» appears; in both failure cases the origin stays empty and **no** route request is sent.
6. **Given** the origin or destination field, **When** the user types, **Then** it behaves as the NAV-003 search box for request rules, bias, states and strings (NAV-003 AC 2–9, 13, 15–16, 26, 31–38, 42, 46), with its own results list attached to the field; the destination field has the accessible name «Очих газар» and, when empty, the placeholder «Очих газар сонгох». Selecting a result sets that point, shows the result name in the field, and sends one route request when both points are set. Typed coordinates (NAV-003 AC 26 format) set the point as «Сонгосон цэг» with **no** `search` request.
7. **Given** the panel is open, **When** the user right-clicks or long-presses the map (NAV-003 AC 25 gesture), **Then** the coordinate card opens with two extra buttons «Эхлэх цэг болгох» and «Очих газар болгох». Activating one sets that point to the coordinate (field text «Сонгосон цэг»), closes the card, and sends one route request when both points are set.
8. **Given** both points are set, **When** the user activates the swap button «Эхлэх цэг, очих газрыг солих», **Then** the two points and field texts swap and **one** route request is sent. The swap button is disabled (`aria-disabled="true"`) while either point is empty.
9. **Given** both points are set, **When** the map is shown, **Then** exactly one origin marker and one destination marker are visible, visually distinct (UX map style), with accessible names equal to their field texts. An origin of «Миний байршил» uses the NAV-002 location marker.

### B. Route request
10. **Given** a triggering action with both points set, **When** the request is sent, **Then** exactly **one** `POST {gateway}/v1/route` is sent whose JSON body has:
    - `locations` = [origin, destination], each `lat`/`lon` with ≥ 5 decimals;
    - `costing` from the selected tab (AC 11), `alternates: 2`, `format: "osrm"`, `banner_instructions: true`, `units: "kilometers"`;
    - `language: "mn-MN"` in the Mongolian UI and `"en-US"` in the English UI;
    - `costing_options` only as in AC 12, and no toll option.
    The `GET /v1/route?json=` form is never used (no coordinates in URLs). `voice_instructions` is the architect's choice (not needed for the preview).
11. **Given** the mode tabs «Машин», «Явган», «Дугуй» (tab list named «Зорчих хэлбэр»), **When** the page loads, **Then** «Машин» is selected. The tabs map to `costing` `auto`, `pedestrian`, `bicycle`. **When** a tab is selected and stays selected for **300 ms**, **Then** one route request is sent with the same points. Pressing ArrowRight twice within 300 ms sends **1** request, for the final tab.
12. **Given** the «Машин» tab, **When** the panel is shown, **Then** the toggle «Шороон замаас зайлсхийх» is visible, **off** by default. **When** it is on, **Then** the request carries `costing_options: { auto: { exclude_unpaved: true } }`, and toggling sends one request. The toggle state is kept while switching tabs and for the rest of the page session. On «Явган» and «Дугуй» the toggle is **hidden** unless the architect's decision (ADR or `openapi.yaml`) names a contract-backed option for that costing; in that case the same rules apply with that option.
13. **Given** a route request is in flight, **When** a new triggering action happens, **Then** the older request is aborted or its response ignored. An older response **never** replaces the result of a newer request, even when it arrives later (QA delays the first response by 1 s). At most **1** route request is in flight.
14. **Given** the origin and destination are within **10 m** of each other (haversine), **When** they are set, **Then** **no** route request is sent and the panel shows «Эхлэх цэг, очих газар ижил байна» within **500 ms**.
15. **Given** the stack is healthy, **When** RS1–RS4 are requested (20 samples in total, at most 2 route requests per second), **Then** for **≥ 19 of 20** samples the selected route line and the summary are rendered within **1,500 ms** of the triggering action. RS6 renders within **3,000 ms** in **≥ 4 of 5** samples.

### C. Routes on the map and alternatives
16. **Given** a 200 response with *k* routes (1–3), **When** it renders, **Then** *k* route lines are drawn; route 1 (Valhalla's first) is selected; the selected line is drawn above the others and emphasised as in the NAV-004 map-style section (UX): its colour has contrast **≥ 3:1** against the `earth` and major-road colours in both themes, and alternatives use a different colour token and a line at least **2 px** narrower. Route lines never cover the attribution, scale bar or controls. For RS3, QA records *k* (fewer alternatives than requested is normal).
17. **Given** routes are drawn, **When** the first route of a response renders, **Then** within **1 s** the camera fits the bounding box of all drawn routes and both markers inside the part of the map not covered by the panel or the top bar, with ≥ **40 px** padding, at zoom ≤ **17**. With `prefers-reduced-motion: reduce` the camera jumps without animation within **500 ms**.
18. **Given** *k* ≥ 2, **When** the user clicks or taps an alternative line (hit area ≥ **10 px** on each side of the line) or chooses it in the route options, **Then** within **200 ms** it becomes the selected, emphasised line, and the summary (section D) and turn list (section E) show that route. **0** route requests are sent, and the camera does not move.
19. **Given** *k* ≥ 2, **When** the panel renders, **Then** a route options group named «Маршрут сонгох» shows one option per route: «Маршрут {n}» with its distance and duration (AC 23–24); the selected option is marked (`aria-checked="true"`), and each non-selected option's accessible description includes «Өөр маршрут». With *k* = 1 the group is not shown.
20. **Given** routes and markers are drawn, **When** the user switches day/night mode (NAV-002 AC 27), **Then** the lines and markers stay, in that mode's colours, the selection is kept, and **0** route requests are sent.
21. **Given** a 200 response where `waypoints[0].distance` or `waypoints[1].distance` is **> 500 m**, **When** it renders, **Then** the route is shown and the panel also shows «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» with the larger snap distance formatted per AC 23 (for example a mocked 1,416 m → «1,4 км»). With both ≤ 500 m, no such notice is shown.

### D. Summary: distance, duration and «Хүрэх цаг»
22. **Given** a selected route, **When** the summary renders, **Then** it shows the route's distance (`route.distance`), duration (`route.duration`) and «Хүрэх цаг HH:MM» ("Arrive at HH:MM" in the English UI).
23. **Given** a distance *d* in metres, **When** it is shown on screen (summary, route options, step rows, AC 21), **Then**:
    - *d* < 995 m: rounded to the nearest 10 m, minimum 10, «850 м» ("850 m");
    - 995 m ≤ *d* < 99,950 m: kilometres with one decimal, a **comma** decimal separator in the Mongolian UI and a **point** in the English UI, and a trailing «,0» / ".0" dropped: «1,4 км», «12 км», «12,4 км» ("1.4 km", "12 km", "12.4 km");
    - *d* ≥ 99,950 m: whole kilometres, «245 км».
    Unit tests cover 0, 4, 5, 994, 995, 1,000, 1,449, 1,450, 99,949, 99,950 and 245,300 m.
24. **Given** a duration *t* in seconds, **When** it is shown, **Then** it is rounded to the nearest minute (minimum 1 min): *t* < 3,570 s → «25 мин» ("25 min"); otherwise «1 ц 25 мин» ("1 h 25 min"), with «0 мин» omitted («2 ц», "2 h"). Unit tests cover 0, 29, 30, 89, 3,569, 3,570, 7,200 and 108,000 s.
25. **Given** a selected route, **When** the response arrives, **Then** «Хүрэх цаг» = the device's local time at arrival of the response + `route.duration`, rounded to the nearest minute, 24-hour «HH:MM» (C6). If the arrival falls on a later calendar day, «+{days} өдөр» follows the time (for example response at 23:30:00, duration 3,600 s → «Хүрэх цаг 00:30 +1 өдөр»; English "Arrive at 00:30 +1 day"). With a fake clock, response at 13:50:00 and duration 2,700 s → «Хүрэх цаг 14:35». While the panel stays open the time is recomputed every **60 s** (±5 s) from the current clock with **0** new route requests.

### E. Turn-by-turn list
26. **Given** a selected route, **When** the list renders, **Then** an ordered list named «Маршрутын заавар» has one row per step of `legs[0].steps`, in order, the first row for `depart` and the last for `arrive`. Each row shows a manoeuvre icon (decorative, `aria-hidden`), the instruction text (AC 27), the step's street name (`step.name` after removing zero-width characters, only if non-empty; names are shown as returned, glossary C5/C8) and the step distance (AC 23), except the `arrive` row, which has no distance.
27. **Given** a step, **When** its instruction text is produced in the Mongolian UI, **Then** it is exactly the text in this table (glossary wording, first letter upper case), whatever the text source chosen by the ADR:

    | OSRM `maneuver.type` | `modifier` / field | `mn` | `en` |
    |---|---|---|---|
    | `depart` | `bearing_after` in 8 sectors of 45° centred on 0° (N), 45°, 90°, … | «Хойд зүг рүү явна уу», «Зүүн хойд зүг рүү явна уу», «Зүүн зүг рүү явна уу», «Зүүн өмнө зүг рүү явна уу», «Өмнө зүг рүү явна уу», «Баруун өмнө зүг рүү явна уу», «Баруун зүг рүү явна уу», «Баруун хойд зүг рүү явна уу» | "Head north", "Head northeast", … "Head northwest" |
    | `turn`, `end of road`, any other type with a turning modifier | `left` / `right` | «Зүүн тийш эргэнэ үү» / «Баруун тийш эргэнэ үү» | "Turn left" / "Turn right" |
    | same | `slight left` / `slight right` | «Бага зэрэг зүүн тийш эргэнэ үү» / «Бага зэрэг баруун тийш эргэнэ үү» | "Turn slightly left" / "Turn slightly right" |
    | same | `sharp left` / `sharp right` | «Огцом зүүн тийш эргэнэ үү» / «Огцом баруун тийш эргэнэ үү» | "Turn sharp left" / "Turn sharp right" |
    | any | `uturn` | «Буцаж эргэнэ үү» | "Make a U-turn" |
    | `continue`, `new name`, `notification`, any type with `straight` or no modifier (unless listed below) | — | «Чигээрээ явна уу» | "Continue straight" |
    | `fork` | left side (`left`, `slight left`, `sharp left`) / right side / `straight` | «Зүүн талаа барина уу» / «Баруун талаа барина уу» / «Чигээрээ явна уу» | "Keep left" / "Keep right" / "Continue straight" |
    | `merge` | none / left side / right side | «Замд нийлнэ үү» / «Зүүн талаас замд нийлнэ үү» / «Баруун талаас замд нийлнэ үү» | "Merge" / "Merge from the left" / "Merge from the right" |
    | `on ramp` | none / left side / right side | «Орох замаар орно уу» / «Зүүн талын орох замаар орно уу» / «Баруун талын орох замаар орно уу» | "Take the ramp" / "… on the left" / "… on the right" |
    | `off ramp` | none / left side / right side | «Гарах замаар гарна уу» / «Зүүн талын гарах замаар гарна уу» / «Баруун талын гарах замаар гарна уу» | "Take the exit" / "… on the left" / "… on the right" |
    | `roundabout`, `rotary` | `exit` = *n* | «Тойрог: *n*-р гарц» (banner ordinal, C4), e.g. «Тойрог: 2-р гарц» | "Roundabout: exit *n*" |
    | `roundabout`, `rotary` | no `exit` | «Тойрогт орно уу» | "Enter the roundabout" |
    | `exit roundabout`, `exit rotary` | — | «Тойргоос гарна уу» | "Exit the roundabout" |
    | `arrive` | none or `straight` / left side / right side | «Та очих газартаа ирлээ» / «Таны очих газар зүүн талд байна» / «Таны очих газар баруун талд байна» | "You have arrived" / "Your destination is on the left" / "… on the right" |

    Unit tests cover every row with fixture steps (including a 2nd-exit roundabout, a roundabout without `exit`, an on/off ramp on each side, a fork, a merge, a U-turn, and each depart sector boundary: 22.4°, 22.5°, 337.5°).
28. **Given** the turn lists of RS1–RS6 and the AC 27 fixture route in the Mongolian UI, **When** every instruction text (excluding the street-name line) is scanned, **Then** it contains:
    - **0** Latin letters;
    - **0** `<`, `>` or placeholder tokens (for example `<ORDINAL VALUE>`, `{n}`);
    - **0** occurrences of «зүүн» or «баруун» that are not followed by «тийш», «талаа», «талаас», «талын», «талд», «зүг», «хойд», «өмнө» or «эгнээ» (glossary C2);
    - **0** zero-width characters (U+200B, U+200C, U+200D, U+FEFF);
    - **0** glossary Avoid terms («навигаци», regex `км/ц(?!аг)`, «хоёр дахь» / «<number> дахь/дэх», «зорьсон газар», «налуу зам»);
    - every text is one of the AC 27 texts (C1 polite form, or the glossary banner noun for roundabouts and the arrival row).
29. **Given** the English UI, **When** RS1 and RS3 are shown, **Then** every instruction text is the `en` text of AC 27 (or Valhalla `en-US` text, if the ADR chooses that for English) and contains **0** Cyrillic letters outside the street-name line.
30. **Given** a turn list, **When** the user clicks a row or presses Enter/Space on it, **Then** within **1 s** the camera centres on that step's `maneuver.location` (±5 px) at zoom **17** (or the current zoom if higher); with reduced motion, it jumps within 500 ms. The route and selection stay.
31. **Given** the ADR on the instruction text source, **When** it is accepted, **Then** its ID is recorded in this story's Traceability, and the same mapping (AC 27) is available to NAV-005 without copying strings outside the resource files.

### F. States
32. **Given** a route request is pending for more than **300 ms**, **When** it is still pending, **Then** the panel shows «Ачаалж байна…» with `aria-busy="true"`; the indicator is gone within **200 ms** of the result. At the start of every request the previous route lines, summary and turn list are removed, so a stale route is never shown next to new inputs.
33. **Given** a 400 with `code` `NoRoute`, **When** it arrives, **Then** the panel shows «Маршрут олдсонгүй»; if the avoid toggle is on, it also shows «Шороон замаас зайлсхийх тохиргоог унтрааж дахин оролдоно уу». No «Дахин оролдох» is offered (the same request would fail again). RS5 shows either a route or this state, never an error state or a spinner (NAV-001 AC 19).
34. **Given** a 400 with `code` `NoSegment` or `ValhallaError` `error_code` 171, **When** it arrives (RS7: within **4 s** of the triggering action), **Then** the panel shows «Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна», no route line is drawn, and the markers stay.
35. **Given** a 400 with `code` `DistanceExceeded` on «Явган» or «Дугуй» (RS8 if the dev server limits it; otherwise injected), **When** it arrives, **Then** the panel shows «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна». On «Машин» the same code shows «Маршрут олдсонгүй».
36. **Given** the route request fails with a network error, a CORS failure, **502**, **503**, **504**, or no response within **12 s** (client timeout; the gateway's route read timeout is 10 s) while `navigator.onLine` is true, **When** the failure is known, **Then** within **12 s** of the request start the panel shows «Маршрутын үйлчилгээ түр ажиллахгүй байна» with «Дахин оролдох». There is no endless spinner. «Дахин оролдох» re-sends the current request **once**. Nothing retries automatically.
37. **Given** the route request returns **429** with `Retry-After: N`, **When** it arrives, **Then**:
    - the panel shows «Түр хүлээгээд дахин оролдоно уу» with «Дахин оролдох» disabled (`aria-disabled="true"`) for **N** seconds;
    - **no** route request is sent for **N** seconds, whatever the user does (fields, tabs, swap and toggle still update the inputs);
    - after N seconds nothing is sent automatically; «Дахин оролдох» or the next triggering action sends **one** request with the current inputs.
    QA checks with `Retry-After: 3`: zero route requests in the 3 s window while switching tabs, then one request on «Дахин оролдох». If `Retry-After` is missing, not a positive integer, or unreadable, the client waits **5 s**.
38. **Given** the browser is offline (`navigator.onLine` false or the `offline` event), **When** a triggering action happens, **Then** **no** route request is sent and the panel shows «Интернэт холболт алга» within **500 ms**. **When** the browser comes back online with both points set and no route shown, **Then** **one** route request is sent within **2 s**, with no user action.
39. **Given** a 400 with any other code (`InvalidOptions`, `InvalidValue`, `TooBig`, unknown), a `ValhallaError` other than 171, a 413, or a 200 body that cannot be parsed, **When** it arrives, **Then** the panel shows «Алдаа гарлаа» and the same request is not retried.
40. **Given** a state message, **When** the UI language is switched, **Then** the message switches language within **500 ms** (NAV-002 AC 31). Route messages appear only inside the route panel, never cover the attribution, and the NAV-002 precedence (offline > tiles unavailable > loading) is unchanged. Routing still works while the tiles-unavailable state is shown, and tiles still load while routing is unavailable.

### G. Closing the preview and coexistence with NAV-002/NAV-003
41. **Given** the panel is open, **When** the user activates «Хаах», or presses Escape with focus in the panel and no field list open, **Then** within **200 ms** the panel, route lines and origin marker are removed, any in-flight route request is aborted, **0** new requests are sent, and focus returns to the «Маршрут гаргах» button that opened it (the place card is still open), or to the search input if that card no longer exists.
42. **Given** the panel has never been opened in a session, **When** the NAV-002 and NAV-003 suites under `tests/e2e/nav002/` and `tests/e2e/nav003/` run, **Then** they pass unchanged (any test QA must adapt because of the new card button is listed in the QA handoff with the reason), the search input is still the first Tab stop, and **0** route requests are sent.
43. **Given** the panel is open with a route and the turn list, **When** inspected at viewport widths **320, 360, 768, 1366 and 1920 px**, both themes and both languages, **Then** the panel, lists and markers do not cover «© OpenStreetMap contributors» (or the ESA credit when shown), the scale bar or any NAV-002 control, no two controls overlap, the panel content scrolls inside the panel when it does not fit, and every NAV-004 touch target is at least **44×44 CSS px**.

### H. Keyboard and screen reader
44. **Given** the panel open in each state of section F, with a route, with the turn list, in both themes and languages, **When** checked with axe-core, **Then** there are **0** violations of serious or critical impact, all NAV-004 text meets WCAG 2.1 AA contrast (≥ 4.5:1), and non-text indicators (selected tab, selected route option, focus ring) meet ≥ 3:1.
45. **Given** keyboard-only use, **When** the panel is open, **Then**:
    - the mode tabs follow the WAI-ARIA tabs pattern (`role="tablist"` named «Зорчих хэлбэр», `role="tab"`, `aria-selected`; ArrowLeft/ArrowRight move and select with wrapping, Home/End go to the first/last tab; request timing per AC 11);
    - the route options follow the radio group pattern (`role="radiogroup"` named «Маршрут сонгох»; arrow keys change the selection, 0 requests);
    - each turn-list row is reachable and activatable (AC 30), and its accessible name is instruction, street name and distance joined with ", ";
    - every control is reachable with Tab in the order given by the NAV-004 screen spec, with a visible focus indicator.
46. **Given** the panel opens, **When** focus moves, **Then** it goes to the origin field if the origin is empty (AC 4), otherwise to the selected mode tab.
47. **Given** a route result or a state, **When** it renders, **Then** within **1 s** a polite live region announces once: «{count} маршрут олдлоо» followed by the selected route's distance, duration and «Хүрэх цаг HH:MM» (for example «2 маршрут олдлоо, 4,3 км, 12 мин, Хүрэх цаг 14:35»), or the state message. Selecting another route announces its summary once. The minute update of AC 25 is **not** announced.

### I. Strings and localisation
48. **Given** the `web/` resource files after NAV-004, **When** the NAV-002 checks run (AC 32 hard-coded text scan and identical key sets; AC 33 `npm run check:glossary`), **Then** both pass, including every new NAV-004 key. Every `mn` value is a glossary term from the tables above (placeholders kept literally). Manoeuvre texts may be stored with a lower-case first letter as in the glossary and capitalised by code, or stored capitalised; the check must accept either (request to QA/mobile in the handoff).
49. **Given** the English UI, **When** the panel is shown, **Then** every NAV-004 label and message is its `en` value, «Миний байршил» reads "My location", street names and map labels stay as returned / Cyrillic (D11), and the English decimal point is used (AC 23).
50. **Given** a route is shown, **When** the UI language is switched, **Then** labels switch within **500 ms**, instruction texts switch within **1,500 ms**, at most **1** route request is sent (0 when the text is generated on the client), and the selected route index is kept if the new result still has that many routes (otherwise route 1).

### J. Network hygiene and privacy
51. **Given** a full NAV-004 session (RS1–RS8, all tabs, avoid on/off, swap, language switch), **When** all network requests are logged, **Then** every route request goes to the gateway base URL, and **0** requests go to any public routing host (OSRM, Valhalla, GraphHopper, Hamuga or other).
52. **Given** any NAV-004 session, **When** storage and the console are inspected, **Then** route request bodies, origin/destination coordinates and the device position are **not** written to `localStorage`, `sessionStorage`, cookies, the console or the page URL. The device position leaves the browser only as the origin of a route request whose origin is «Миний байршил» (not rounded, because routing needs it; D9 applies before outside testers use routing on staging, NAV-008 AC 24).

### K. Static public build (D44, NAV-002 AC 53–54)
53. **Given** a build with the static-build setting that NAV-002 AC 53 documents (the same single setting that turns search and reverse off), **When** the user activates «Маршрут гаргах» on a coordinate card and then presses «Дахин оролдох», switches tabs, swaps and toggles «Шороон замаас зайлсхийх», **Then** within **1 s** the panel shows «Маршрутын үйлчилгээ түр ажиллахгүй байна» (no endless spinner), the map, attribution and other controls keep working, and **0** `/v1/route` requests are sent to **any** host, the page origin included. `web/README.md` states that the setting also turns routing off. With the setting off (dev), routing works as in AC 1–52.
54. **Given** the route client code, **When** unit tests run with the static-build setting on, **Then** the route client never builds or sends a request (unit test in `web/`). NAV-002 AC 54 applies unchanged: the public site keeps routing off until NAV-008 AC 24 records a go.

## Edge cases
- **GPS lost / stale:** the origin is fixed when the request is sent. Location updates or loss while the preview is open do **not** re-request (no request storms); the preview is a plan, not guidance (reroute is NAV-005). A fix older than 60 s at opening is treated as "location not on" (AC 4).
- **Location permission denied:** AC 4–5; the user sets the start by search, map or typed coordinates.
- **No network:** AC 38. **Weak signal (countryside):** loading after 300 ms (AC 32), unavailable state at 12 s (AC 36), never an endless spinner.
- **Gateway down / data rebuild** (Valhalla graph rebuild; the gateway may return 502): AC 36.
- **Rate-limited (429):** AC 37 (staging only; local dev normally never returns 429, so tests inject it).
- **Off-route:** not applicable to a preview (NAV-005).
- **No route found:** AC 33, including the avoid-unpaved hint for ger districts (RS5).
- **Out of coverage:** far outside → 400 `NoSegment` (AC 34, RS7); just outside or near the border → Valhalla snaps up to 35 km and returns 200, caught by the snap notice (AC 21).
- **Point far from any road inside Mongolia** (steppe, ger plot without mapped tracks): snap notice (AC 21); the route ends at the nearest mapped road.
- **Too far on foot or by bike:** AC 35 (RS8).
- **Origin = destination:** AC 14 (for example the user picks the same search result twice, or taps the map at their own position).
- **Fast switching and out-of-order responses:** AC 11, 13.
- **Language switch / theme switch with a route shown:** AC 50, AC 20.
- **Cyrillic/Latin search** in the origin/destination fields: inherited from NAV-003 (AC 6), including "Sukhbaatar" vs «Сүхбаатар».
- **Unpaved roads:** the avoid toggle (AC 12) depends on `surface` tagging (R2); a route may still use untagged dirt roads. The preview does not show which segments are unpaved (out of scope).
- **Winter conditions:** Valhalla has no seasonal closures or snow data; winter-only roads (ice roads on lakes, `seasonal=*`) and snow-closed passes are routed as in the OSM data (R9). Durations are summer defaults. Touch targets ≥ 44×44 px (AC 43) help users in gloves.
- **Very long intercity routes** (hundreds of steps): the turn list scrolls inside the panel (AC 43); rendering stays within AC 15 for RS6.
- **ETA across midnight:** AC 25 («+1 өдөр»). **Device clock wrong:** the ETA is wrong by the same amount; accepted.
- **Roundabout without an exit number, unknown manoeuvre types:** AC 27 fallbacks; never English or placeholder text in the Mongolian UI (AC 28).
- **Street names with zero-width characters or traditional script:** zero-width characters removed (AC 26); traditional Mongolian script handled as NAV-003 AC 18.

## Data dependencies & risks
| # | Risk | Impact on NAV-004 | Mitigation / owner |
|---|---|---|---|
| R1 | **`maxspeed` coverage is low and there is no traffic data** (NAV-001 R8; Phase 3) | Durations and «Хүрэх цаг» are optimistic in UB rush hours (can be off by a factor of 2 or more) | Accept for Phase 0. Traffic comes in Phase 3. The PO may want a disclaimer later (not in scope) |
| R2 | **`surface` coverage** in ger districts and the countryside | «Шороон замаас зайлсхийх» may still route over untagged dirt roads, or return `NoRoute` when tagged dirt is the only access | AC 33 hint; `osm-data` lane for mapping (human mappers only) |
| R3 | **Turn restrictions, one-ways and access tags** in OSM are incomplete | Wrong or illegal manoeuvres in routes | Reported via triage lane `osm-data`; not a client bug |
| R4 | **Street names:** Valhalla returns `name` (mostly Cyrillic), rarely `name:mn`/`name:en`; many ger-district streets are unnamed | Turn rows without a street name; tourists see Cyrillic names (D11) | AC 26 omits empty names; tourist story later |
| R5 | **Valhalla `mn-MN` narrative defects** (NAV-007 F1–F10) | If the ADR picks option (a), AC 27–28 fail | Architect ADR (b or c); NAV-007 fixes upstream later |
| R6 | **Pedestrian network** (sidewalks, crossings, underpasses on Peace Avenue) is incomplete | Walking routes may follow roads or detour | Recorded; `osm-data` lane |
| R7 | **Bicycle network** is sparse; bicycle costing uses roads | «Дугуй» routes are road routes; winter cycling unrealistic | Recorded; no product promise of cycleways |
| R8 | **Snapping near the coverage edge** (35 km search cutoff) | A route may end far from the chosen point | AC 21 notice (threshold 500 m, Open question 1) |
| R9 | **Seasonal roads and winter closures** are not modelled | Routes over ice roads in summer or closed passes in winter | Recorded; later story if needed |
| R10 | **Index dates differ** between Photon (weekly dump, ADR-0003) and the Valhalla graph | A place found by search may snap to an older road network | Accept for Phase 0; NAV-006 daily rebuild |
| R11 | **Countryside speed defaults** on `highway=track` / unpaved roads | Intercity ETAs for the countryside persona may be unrealistic | Recorded; NAV-007/NAV-005 field feedback |
| R12 | **Route coordinates are personal data** (D9) | Sending the device position to a host abroad (staging, Singapore) needs the legal review | Local dev only now; public site 0 requests (AC 53–54); NAV-008 AC 24 before outside testers |
| R13 | **Rate limits on shared staging** (carrier NAT, NAV-008) | 429 on normal use if limits are tight | AC 11 settle delay, AC 13 one request in flight, AC 37 |
| R14 | **Toll points** exist at UB city exits (glossary "Avoid tolls") but are not offered as an avoid option | Drivers cannot avoid them; ETA ignores stops | Out of scope per the request; Open question 2 |

## Out of scope
- Active navigation, voice guidance, banners while driving, off-route and reroute (NAV-005).
- Waypoints «дайрах цэг» (more than 2 locations), drag-to-change route, departure/arrival time selection, traffic-aware ETA.
- Toll, highway and ferry avoid options (Open question 2).
- Showing which parts of a route are unpaved, elevation, route saving or sharing, deep links, printing.
- Transit routing (Phase 4), taxi costing (the glossary leaves `auto` vs `taxi` to the architect; this story uses `auto`).
- Route preview in native apps (Android/iOS): later stories reuse these AC.
- Any change to the gateway rate-limit / `real_ip` / header configuration or the shared dev stack; any `openapi.yaml` change unless the architect decides one is needed.
- Safari/iOS (D14).

## Test approach (for QA, binding)
- Tests live under `tests/e2e/nav004/`. Tests of other stories are not changed (AC 42: any unavoidable adaptation is listed in the QA handoff).
- Run live-gateway tests only when `http://localhost:8080/health` returns 200. If not, wait and retry. Never stop, restart or rebuild the stack, and never run docker, docker compose or make in `backend/`.
- **Modest request rates:** at most **2** route requests per second from the whole test run against the live gateway, serial execution for live routing tests; search requests in NAV-004 tests follow the NAV-003 limit (≤ 2 per second).
- Simulate 400 (`NoRoute`, `NoSegment`, `DistanceExceeded`, `InvalidOptions`, `ValhallaError` 171), 413, 429 with and without `Retry-After`, 502/503/504, timeouts, delayed and out-of-order responses, and snap distances (AC 21) with Playwright request interception. Offline with `context.setOffline`. Location with Playwright geolocation. Reduced motion with `emulateMedia`. Fake clock for AC 25.
- A fixture OSRM response set covers every AC 27 row (roundabout with and without `exit`, ramps on both sides, fork, merge, U-turn, sharp/slight turns, depart sector boundaries, arrive left/right).
- Unit tests (mobile-engineer, `web/`): distance and duration formatting (AC 23–24), ETA (AC 25), the AC 27 mapping, and the static-build guard (AC 54).

## Open questions
None blocks UX or architecture work. Each has a BA default already written into the AC.
1. **Snap notice threshold (AC 21).** Options: 200 m, **500 m**, 1,000 m. *Recommendation: 500 m*: long enough to ignore normal snapping to the road in front of a building, short enough to warn when a ger-district or steppe point is far from any mapped road.
2. **Toll option.** The request says Mongolia has no toll roads, so no toll option. The glossary ("Avoid tolls", PO-approved 2026-09-29) notes that toll points exist at UB city exits and depend on OSM `toll=*`. Options: (a) no toll option in NAV-004, revisit with NAV-005; (b) add «Төлбөртэй замаас зайлсхийх» to the «Машин» tab now. *Recommendation: (a)*; the UB exit fees are small road-use fees that drivers cannot avoid on most intercity roads.
3. **Glossary rows N1–N23** are BA proposals (`needs native review`). Options: (a) implement now and let the NAV-007 panel review them later (as D27 for NAV-003); (b) PO pre-review first. *Recommendation: (a)*.
4. **Avoid unpaved on «Явган» / «Дугуй»** (AC 12): technical feasibility is the architect's call; the product default is "car only". No PO decision needed unless the architect finds a contract-backed option and UX wants it visible.

## Traceability
Paths (to be created by their owners): screen spec `docs/design/screens/NAV-004-route-preview.md`, flow `docs/design/flows/NAV-004-route-preview.md`, map-style section "Route line and alternatives" in `docs/design/map-style.md`, test plan `docs/qa/test-plans/NAV-004.md`, ADR on the instruction text source (`docs/architecture/adr/0008-*.md`, number assigned by the architect). API `docs/architecture/api/openapi.yaml` 0.4.1 `postRoute`, `preflightRoute`. Code under `web/src/` (NAV-004 modules, names by the mobile engineer).

| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–9 | Screen spec › Entry points, Fields, Markers; flow F1 (TBD) | `search`, `reverse` (fields, inherited from NAV-003), `postRoute` | ADR-0006 (search behaviour) | TBD | `tests/e2e/nav004/` (TBD) | Glossary N2–N4, N17–N18; NAV-003 place card slot |
| AC10–15 | Screen spec › Mode tabs, Avoid toggle | `postRoute` | TBD (costing options for walk/bike) | TBD | TBD | Glossary N1, N10, N19; D37 (R1); Open questions 2, 4 |
| AC16–21 | Map style › Route line and alternatives; screen spec › Route options | `postRoute` (`alternates`, `waypoints[].distance`) | — | TBD | TBD | Glossary N6, N13, N20; R8; Open question 1 |
| AC22–25 | Screen spec › Summary | `postRoute` (`distance`, `duration`) | — | TBD | TBD (unit + e2e, fake clock) | Glossary N16, N21, N22; C3, C6 |
| AC26–31 | Screen spec › Turn list | `postRoute` (`legs[].steps[].maneuver`, `name`) | ADR-0008 (instruction text source, architect) | TBD | TBD (fixture route set) | NAV-007 F1–F10; glossary section 3, N5, N14, N15, N23; reuse in NAV-005 |
| AC32–40 | Screen spec › States | `postRoute` (400, 413, 429 + `Retry-After`, 502/503/504) | — | TBD | TBD | Glossary N8, N9, N11, N12, T5 |
| AC41–43 | Screen spec › Layout, Close | — | — | TBD | TBD; NAV-002/NAV-003 suites | NAV-002 AC 34, 49; NAV-003 AC 23 |
| AC44–47 | Screen spec › Accessibility | — | — | TBD | TBD | Glossary N7, N19, N20 |
| AC48–50 | Screen spec › Copy | `postRoute` (`language`) | ADR-0008 | `web/src/i18n/mn.json`, `en.json` | TBD; NAV-002 `check:glossary`, `check:i18n` | Glossary N1–N23 |
| AC51–52 | — | `postRoute` | — | TBD | TBD | D9, R12 |
| AC53–54 | — | — (0 requests) | — | static-build setting (shared with the NAV-003 follow-up) | TBD | D44; NAV-002 AC 53–54; `decisions.md` Items to confirm 7 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-30 | NAV-004 feature request (orchestrator, feature-delivery) | Created from the backlog draft row (team design §5) and the feature request. 54 AC in sections A–K (entry points and origin/destination incl. «Миний байршил» default, request body and mode tabs, avoid unpaved, alternatives on the map, distance/duration/«Хүрэх цаг» formatting, turn list with a glossary mapping table and source-agnostic text-quality checks, states incl. 429 `Retry-After`, out of coverage, origin = destination and `DistanceExceeded`, closing and coexistence with NAV-002/NAV-003, keyboard and screen reader, strings, privacy, static public build with 0 route requests). Design question for the architect (instruction text source, ADR). Glossary rows N1–N23 added (`needs native review`). Size **L** (backlog row said M). `needs_backend` false unless the ADR picks gateway post-processing. Four non-blocking open questions | Refine NAV-004 to `ready` for UX and the architect. Runs in parallel with a NAV-003 follow-up in `web/`, hence the coordination rules in Context and Test approach |
