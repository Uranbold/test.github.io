---
id: NAV-011
title: Android route preview and search parity with the web demo (alternatives, «Дугуй», Cyrillic/Latin query assistance, reverse geocoding on the coordinate card, typing lock while moving with a passenger override)
phase: 1
priority: must         # Phase 1 beta; proposed by the orchestrator, PO "go ahead" 2026-10-03, D106; re-confirmed 2026-10-04 (D124)
size: L                # was M in the backlog draft; L because the typing lock and «Дугуй» guidance were added at refinement
needs_design: true
needs_backend: false   # true only if the architect's ADR (ADR-0006 R11) picks server-side query assistance (contract change, gateway/Photon work)
needs_mobile: true     # Android only (mobile/android); iOS is NAV-015
status: done           # accepted by the PO 2026-10-04 in light-QA mode (D141): code 1c2bf4f, light QA 0 defects; real-device checks (AC 47) stay not verified, full QA before any public release (D108). The D140 typed-coordinate change (AC 7, 7a, 8, 9, 12, 13, 20, 33, 39, 40) is delivered in change-request run 2 and verified by its own light QA. Change 7b (2026-10-05, D194, section G, AC 48–55) documents the offline search and reverse states that NAV-023 built; status stays done
---

# NAV-011: Android route preview and search parity

## Story
As a **UB commuter by car**, I want **the Android app to find a place whether I type it in Cyrillic, in Latin letters or with a district abbreviation, to show me up to two other routes next to the main one, and to stop me from typing while the car is moving unless I say I am a passenger**, so that **I find my destination on the first try, pick the route I prefer, and am not tempted to type while driving**.

Secondary personas:
- **Taxi / delivery driver**: long-presses a ger-district point and sees the nearest named place («Ойролцоох газар») on the card, so the pin can be checked against what the customer said; compares alternatives and their «Хүрэх цаг»; is blocked from typing while driving, but a passenger can unlock search with one tap.
- **Pedestrian**: uses «Явган» as today; the lock never engages at walking speed.
- **Tourist (English UI)**: types "Sukhbaatar" or "Gandan" in Latin letters and gets the same places as the Cyrillic query; can choose «Дугуй» to cycle in the city in summer.
- **Intercity / countryside driver**: sees alternatives on long routes where Valhalla offers them; reverse and search show clear offline and failure states on weak signal, and the long-press card still offers «Маршрут гаргах» without waiting for reverse.

## Context
- **Origin:** backlog draft NAV-011 (2026-10-01), created as a NAV-005 follow-up. Scope confirmed by [D58](../decisions.md) (alternatives, «Дугуй» and the ADR-0006 search assistance stay out of the NAV-005 first slice), typing lock added by [D65](../decisions.md) (NAV-005 limitation L1, NAV-005 screen spec › Known limitations 1). Research `docs/osm-navigation-research.md` §2 row 5 (alternatives), §6 screen flow (place card → route preview with alternatives and mode tabs), §7 Phase 1.
- **Started 2026-10-03:** feature request relayed by the orchestrator; the PO said "go ahead". **Priority must (Phase 1 beta), [D106](../decisions.md).**
- **Parity means the web behaviour, already specified and verified, on Android:**
  - alternatives and «Дугуй»: [NAV-004](NAV-004-route-preview-web.md) AC 10–13, 16–21, 35 (web);
  - query assistance: [NAV-003](NAV-003-search-cyrillic-latin-autocomplete.md) AC 14–16 and ADR-0006 §2–§6 (web);
  - reverse on the coordinate card: NAV-003 AC 27–30, 36–38 (web);
  - typed coordinates open the coordinate card: NAV-003 AC 26 (web), added to this story by the D140 change (2026-10-04).
  Where Android needs a different rule (touch targets, sheet, TalkBack, foreground location), this story states it.
- **Architect decision first (ADR-0006 R11):** before native search assistance is built, a **new ADR** decides between (a) porting the ADR-0006 pure functions to Kotlin, tested against the same vectors, and (b) moving assistance server-side (Photon synonyms/analyser or a gateway endpoint). ADR-0006 R11 says a spike on Photon 1.3.0 synonym support should come first, raised through triage. That is the architect's call. **Section A is written so that it holds for either option.** Option (b) is a contract change (`openapi.yaml`, owner architect) plus backend work, and it must not change NAV-003 web behaviour unless that is its own change request.
- **Relation to NAV-005 (first slice, D58).** When NAV-011 ships, these NAV-005 AC are replaced on Android. NAV-005 itself is **not edited now** (mid-flight rule, the D104 pattern). The QA handoff lists every NAV-005 test that is adapted for this reason:

  | NAV-005 AC | First-slice behaviour | After NAV-011 |
  |---|---|---|
  | AC 3 | query sent **as typed** | assistance per section A; a recognised coordinate pair sends **0** `search` requests and shows the «Сонгосон цэг» option (AC 7, [D140](../decisions.md); NAV-005 sent it as typed) |
  | AC 4 | coordinate card sends **0** `reverse` requests | exactly 1 `reverse` per card (section B) |
  | AC 5 | preview request `alternates: 0`; tabs «Машин» / «Явган» | preview request `alternates: 2` (section C); tabs «Машин» / «Явган» / «Дугуй» (section D). **Reroutes keep `alternates: 0`** (NAV-005 AC 43 unchanged) |
  | AC 6 | one route line | 1–3 route lines with selection (section C) |
  | Limitation L1 | no typing lock | typing lock with a passenger override (section E) |

- **PO decisions that apply:**
  - [D11](../decisions.md): Cyrillic map labels in the English UI.
  - [D28](../decisions.md): English UI result names come from `lang=en`.
  - [D30](../decisions.md): the search bias rule.
  - [D33](../decisions.md): the 80 % tier B threshold is fixed.
  - [D34](../decisions.md) / [D45](../decisions.md): «Сум» / «Аймаг» in both UIs (Android already has `place_type_*`).
  - [D42](../decisions.md) / [D48](../decisions.md): golden rows A8 / B8.
  - [D51](../decisions.md): snap notice above 500 m.
  - [D55](../decisions.md): native apps get a **draggable sheet** for the route preview.
  - [D58](../decisions.md), [D65](../decisions.md): scope.
  - [D60](../decisions.md): no toll option.
  - [D62](../decisions.md): no Google Play services, so platform `LocationManager` only, also for the typing lock.
  - [D9](../decisions.md) / [D26](../decisions.md): team-only use of staging.
  - [D38](../decisions.md): no Hamuga search here.
  - **2026-10-04, PO "All as recommended":** [D110](../decisions.md) passenger override for the rest of the app session, never stored (AC 34); [D111](../decisions.md) «Эхлэх» works on «Дугуй» with a UX «Дугуй» prompt column (AC 25–26); [D112](../decisions.md) origin choice and the turn list are a separate follow-up (Out of scope); [D113](../decisions.md) the lock reads location whenever permission is already granted, on the device only (AC 27–28); [D114](../decisions.md) «Би зорчигч» stays the K2 label for now, and the NAV-007 panel decides later (alternative «Зорчигчоор үргэлжлүүлэх»); [D115](../decisions.md) typed coordinates are sent as typed, and the coordinate-card web parity is a follow-up (AC 7, AC 39; **superseded in part by D140**, see below); [D124](../decisions.md) priority must re-confirmed.
  - **2026-10-04, typed-coordinate change (triage [D137](../decisions.md); impact approved by the PO "All as recommended"):** [D140](../decisions.md) typed coordinates show the «Сонгосон цэг» option and open the coordinate card, as on the web; it **supersedes the "sent as typed / no card" part of D115** (AC 7, 7a, 8, 39); [D141](../decisions.md) NAV-011 accepted in light-QA mode, so the D137 "after acceptance" gate is met; [D142](../decisions.md) selecting the option centres the camera at zoom 16, or the current zoom if higher, with the point clear of the card and the top bar (AC 7a); [D143](../decisions.md) the shared ADR-0006 rule is kept, so two plain integers such as "47 106" count as a coordinate pair (AC 7, web parity); [D144](../decisions.md) this change lands before the parallel NAV-018 build, and ADR-0012 §3 defines the coordinate state of the search controller so NAV-018 can reuse it; [D145](../decisions.md) the NAV-018 start and destination fields get the same option (NAV-018 AC 5, built by NAV-018); [D146](../decisions.md) after selection the search field closes as after a normal result, and the existing crosshair icon is used (AC 7a).
- **Light-QA mode ([D108](../decisions.md), PO 2026-10-03), as set for this run:**
  - QA writes the test plan. It runs only the JVM/Robolectric unit tests and the replay tests for the changed areas, plus the NAV-005 suite as regression (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`). No long browser suites.
  - Each QA and review step does the minimum needed to show each AC.
  - The live golden-set run (AC 6) runs if the shared dev stack answers `/health` 200. Otherwise it is reported as not verified.
  - **Full QA runs before any public release.**
  - The PO's own phone is an **iPhone**, so every real-Android-device check is reported as **not verified** until a test phone exists (AC 47).
- **Coordination (binding for this delivery):**
  - **NAV-012** (Android background/lock-screen polish) is built **in parallel** in `mobile/android`.
  - Before editing a shared file (`MainActivity`, `strings.xml` in `values/` and `values-en/`, settings, the navigation service), read its latest version. Make small, targeted edits and **never revert the other story's changes**.
  - Put NAV-011 code in its own packages where possible (for example `…/search/assist`, `…/search/reverse`, `…/route/alternatives`, `…/typinglock`; the names are the mobile engineer's choice).
  - Never stop, restart or rebuild the shared dev stack at `http://127.0.0.1:8080`. If `/health` is not 200, wait and retry.
  - Live requests: **at most 2 per second** from the whole test run.
  - No secrets, hostnames or IPs in the repo (loopback for local dev is allowed).
- **API:** `docs/architecture/api/openapi.yaml` (0.5.2 when this story was written): `search`, `reverse`, `postRoute` (`alternates` 0–2, `costing` `bicycle`, `exclude_unpaved` for `auto` only). No contract change is needed unless the ADR picks option (b).
- **Offline files (change 7b, 2026-10-05, [D194](../decisions.md), P1):** [NAV-023](NAV-023-offline-search-reverse.md) is built. With an installed `search` file (NAV-022) the on-device engine answers `search` and `reverse` (online first with a fallback after 3.0 s, or at once without a validated network, [D199](../decisions.md)) and results carry the «Офлайн» indicator ([D201](../decisions.md), [D208](../decisions.md)). **Section G (AC 48–55)** states these offline states. The offline rows of AC 7 and AC 11, the Edge cases line "No network" and the Out of scope line "offline search" are changed accordingly. **With no `search` file installed every AC and test of this story is unchanged** (AC 54).

### Terms used in this story
- **Settled query, tier A/B/C, golden set:** as NAV-003. The golden set file is the one NAV-003 QA maintains (`tests/e2e/nav003/fixtures/golden-set.json`). The Android test reads it and does not copy it.
- **Good fix:** horizontal accuracy ≤ **25 m** and ≤ **10 s** old (as NAV-005).
- **Fix speed:** `Location.getSpeed()` when `hasSpeed()` is true; otherwise the distance between this good fix and the previous good fix divided by the time between them (only when they are ≥ **1 s** and ≤ **10 s** apart).
- **Moving (lock engaged)** and **stopped (lock released):** section E, AC 30 and 32.
- **Text-entry surfaces:** the map-screen search field «Газар, хаяг хайх» and any other text field on the map or route-preview screens (S1, S3 in the NAV-005 screen spec). The guidance screen has no text input (NAV-005).
- **Reference points and routes:** P1–P6, X1, X2 (NAV-001); RS1–RS8 (NAV-004). **G10 (new GPX track, QA):** P4 → P5 by bicycle at 4.5 m/s (about 16 km/h), 1 Hz, accuracy 5 m, with its recorded `postRoute` response.

### User-facing strings
Every Mongolian string comes from the glossary. **Reused, no new wording:**

| Use | Glossary row | `mn` |
|---|---|---|
| Bicycle tab, tab row name | N1, N19 | «Дугуй», «Зорчих хэлбэр» |
| Route options group, option, alternative description | N20, N6, "Alternative route" | «Маршрут сонгох», «Маршрут {n}», «Өөр маршрут» |
| Too far on foot or by bike | N11 | «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» |
| Reverse result label, empty, pending | T7, "No results", G1 | «Ойролцоох газар», «Илэрц олдсонгүй», «Ачаалж байна…» |
| Typed-coordinate option (AC 7, D140) and card heading | T6 | «Сонгосон цэг» (existing `place_selected_point`; line 2 is the coordinates, not text) |
| Reverse/search failures | T4, T5, No connection, Generic error, Try again | «Хайлт түр ажиллахгүй байна», «Түр хүлээгээд дахин оролдоно уу», «Интернэт холболт алга», «Алдаа гарлаа», «Дахин оролдох» |
| Type labels | section 4.1 | as the Android `place_type_*` resources (already glossary-matched) |

**New rows K1–K3, added 2026-10-03 to glossary section 2.4, all `needs native review`** (BA proposals. The PO may pre-review them, as in D69. The NAV-007 panel rates them):

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| K1 | Typing locked while moving (message) | «Хөдөлж байх үед бичих боломжгүй» | "You can't type while moving" | AC 31 |
| K2 | Passenger override (button) | «Би зорчигч» | "I'm a passenger" | AC 31, 34. Kept for now ([D114](../decisions.md)); the NAV-007 panel decides later (glossary alternative «Зорчигчоор үргэлжлүүлэх») |
| K3 | Driver hint under K1 | «Жолооч бол зогсоод хайна уу» | "If you are driving, stop before you search" | AC 31 |

## Acceptance criteria

### A. Search: Cyrillic/Latin query assistance (ADR-0006 port or server-side)
1. **Given** the architect's ADR on native query assistance (ADR-0006 R11 follow-up), **When** it is accepted, **Then**:
   - its ID and the chosen option ((a) Kotlin port or (b) server-side) are recorded in this story's Traceability before section A is built;
   - with (b), the `openapi.yaml` change is made by the architect first (contract first), and NAV-003 web behaviour and tests stay unchanged unless that is a separate change request.
2. **Given** a settled Latin query: every letter is Latin script (diacritics such as ö, ü included) and there are ≥ 2 letters (for example "Sukhbaatar", "suhbaatar", "Ikh delguur"). **When** it is searched, **Then**:
   - **at most 2** `search` requests are sent;
   - with option (a), these are the query **as typed** and, in parallel, **one** Cyrillic transliteration per ADR-0006 §2.4;
   - the results are merged with no duplicates (same `osm_type` + `osm_id`), `MN` results first, at most **10** options;
   - with option (b), the request count is the one the ADR specifies (≤ 2), and the result conditions of AC 6 apply unchanged.
3. **Given** a settled all-Cyrillic query that contains у, У, о or О (Russian keyboard layout, for example «Сухбаатар»), **When** it is searched, **Then** (option (a)):
   - the query is sent as typed first;
   - **only if** that response is 200 with **0** features, **one** more request is sent with у → ү, У → Ү, о → ө, О → Ө, and the list shows that second response;
   - «Улаанбаатар» sends exactly **1** request;
   - if the second request would equal the first after normalisation, only 1 is sent.
4. **Given** a settled query with «СБД», «БЗД», «ХУД», «БГД», «ЧД» or «СХД» as a separate, whitespace-delimited word (any letter case), **When** it is searched, **Then** (option (a)):
   - **2** requests are sent in parallel: the query with each abbreviation expanded to the full district name (for example «БЗД 4-р хороо» → «Баянзүрх дүүрэг 4-р хороо»), and the query as typed;
   - the results are merged as in AC 2, with no third request;
   - «БЗД-ийн» (hyphen-joined) is not expanded;
   - **no** request carries a `q` longer than **200** characters (unit test, D33 F4);
   - the field keeps showing what the user typed.
5. **Given** the ADR-0006 §6 test vectors as **one shared fixture** that the web and Android tests both read (location chosen by the architect, as ADR-0008 §3 did for manoeuvres), **When** the Android JVM tests run, **Then** **100 %** of the vectors give the expected output (option (a)), and `npm test` in `web/` still passes unchanged. With option (b), the ADR names the equivalent server-side test.
6. **Given** the NAV-003 golden set, run from a JVM test through the Android search client logic against the live dev gateway when `/health` is 200 (≤ 2 requests per second, stated bias), **When** QA runs it, **Then**:
   - every **tier A** row passes;
   - tier B passes when `passed × 5 ≥ counted × 4`, with the NAV-003 AC 14 counting rules (D33: the threshold is fixed);
   - tier C is recorded;
   - "Sukhbaatar" with the map centred on P1 returns an option ≤ **500 m** from P1 in the top **5** (this closes the item NAV-005 AC 3 recorded for NAV-011).
   - In light-QA mode ([D108](../decisions.md)), if the stack is not healthy during the run, this AC is reported as **not verified** and runs in the full QA before any public release.
7. **Given** any search on Android, **When** requests are sent, **Then** the rest of the NAV-005 AC 3 request profile is unchanged:
   - debounce 250 ms;
   - `lang` from the UI language;
   - D30 bias rounded to 3 decimals;
   - 429 `Retry-After` handling;
   - the states «Илэрц олдсонгүй», «Хайлт түр ажиллахгүй байна» with «Дахин оролдох», and «Интернэт холболт алга»;
   - the client timeout is **8 s** (NAV-003 AC 33);
   - when both requests of a pair fail, the failure state is shown; when one fails and the other returns, the list shows the returned results;
   - an older response never replaces the list of a newer settled query;
   - *Note (2026-10-05, change 7b, D194): the offline state «Интернэт холболт алга» with **0** requests, and the failure and 429 states, hold when no `search` file is installed. With one, AC 48–50 apply: the device answers, and those states show only when the device fails too.*

   **Given** a settled query that matches the ADR-0006 §2.2 coordinate rule (shared fixture `web/src/search/queryPlan.vectors.json`, unchanged; web parity with NAV-003 AC 26, [D140](../decisions.md)): `<lat>, <lon>`, `<lat>,<lon>` or `<lat> <lon>`, lat −90…90, lon −180…180, for example "47.9189, 106.9176", "47.9189,106.9176", "47.9189 106.9176" and "-45.5 -170"; two plain integers such as "47 106" also match ([D143](../decisions.md), same rule as the web), **When** it settles, **Then**:
   - **0** `search` requests are sent, and no loading row «Ачаалж байна…» is shown;
   - the list shows exactly **one** option: line 1 «Сонгосон цэг» (glossary T6), line 2 the normalised coordinates with **5** decimals and a point separator (for "47.9189, 106.9176": «47.91890, 106.91760»), with the existing crosshair icon `ic_location_searching` (no new icon, [D146](../decisions.md));
   - the option row is ≥ **48 dp** tall, and its TalkBack description is built from «Сонгосон цэг» and the coordinates (existing strings only, no new glossary row);
   - the option also shows **offline** and while a `search` 429 `Retry-After` cooldown is running, still with **0** requests: the coordinate check runs before the offline and cooldown checks (web order, ADR-0012 §3). «Дахин оролдох» and a network resume send **0** requests for it;
   - the generation rule applies: a response to an older text query never replaces the option, and a newer settled text query replaces it;
   - a pair the rule rejects is user text and is searched under the rules above: an out-of-range pair ("106.9176, 47.9189") or a decimal-comma pair ("47,9189, 106,9176") sends exactly **1** `search` request as typed, with no assistance variant. Other forms the rule does not recognise (DMS such as 47°55'08"N, a full-width comma, a pair with other words) follow section A like any other text.
7a. **Given** the coordinate option of AC 7 is shown, **When** the user selects it, **Then** ([D140](../decisions.md), [D142](../decisions.md), [D146](../decisions.md)):
   - within **500 ms** the list closes, the keyboard is hidden and the search field closes as after selecting a normal result (web parity);
   - the camera centres on the point at zoom **16**, or the current zoom if it is higher, so that the point lies inside the map area **not** covered by the card or the top bar; if the camera was following the device position, following stops, so the camera does not jump back to the device (the recenter button «Байршил руу буцах» brings it back);
   - the pin and the card «Сонгосон цэг» with the coordinates open exactly as for a long-press (AC 8–13), including the one `reverse` request of AC 8;
   - «Маршрут гаргах» on that card opens the route preview with the destination text «Сонгосон цэг» (AC 9);
   - **0** `search` requests are sent by the selection.
   The centring applies only to this entry point: a long-press still does not move the camera (NAV-005 AC 4, AC 9).

### B. Reverse geocoding on the coordinate card
8. **Given** the user long-presses the map (NAV-005 AC 4) **or selects the typed-coordinate option (AC 7a)** and the card «Сонгосон цэг» opens, **When** it opens, **Then**:
   - exactly **one** `GET {gateway}/v1/reverse` request is sent with the point's `lat`/`lon` (≥ 5 decimals, **not** rounded: the user chose the point; for a typed pair, the typed values, so "47.9189, 106.9176" gives `lat` 47.9189 and `lon` 106.9176, not 47.919 / 106.918), `lang` = UI language, `limit=1` and `radius=0.5`;
   - while it is pending for more than **300 ms**, the nearest-place area shows «Ачаалж байна…»;
   - «Маршрут гаргах» is usable at once and does **not** wait for the reverse response.
9. **Given** the `reverse` response has a feature, **When** it arrives, **Then**:
   - the card shows «Ойролцоох газар» followed by the feature's name, type label and context line, using the same Android display rules as the search results list (D34/D45 labels included);
   - the pin, heading «Сонгосон цэг» and coordinates do not move. For a typed coordinate, the one AC 7a centring move runs at the selection, after the card's first layout; after that the camera does not move. For a long-press, the camera does not move at all;
   - at P1 the nearest place is ≤ **300 m** from P1;
   - the route-preview destination text stays «Сонгосон цэг» (the nearest place is not the address of the point, NAV-001 R9).
10. **Given** the `reverse` response is 200 with **0** features (X2, an empty steppe point), **When** it arrives, **Then** the nearest-place area shows «Илэрц олдсонгүй». No error state is shown, and the rest of the card is unchanged.
11. **Given** `reverse` cannot be answered, **When** the card is open, **Then**:
    - offline: **0** requests are sent and «Интернэт холболт алга» is shown within **500 ms**; when the network returns while the card is open, **one** request is sent within **2 s**;
    - network error, 502/503/504 or no response within **8 s**: «Хайлт түр ажиллахгүй байна» with «Дахин оролдох», which re-sends **once**; nothing retries automatically;
    - **429** `Retry-After: N` (5 s if missing or invalid): «Түр хүлээгээд дахин оролдоно уу», «Дахин оролдох» is disabled for N s, and **0** `reverse` requests are sent in that time; after N s nothing is sent until the user acts;
    - **400**: «Алдаа гарлаа», no retry.
    In every case the heading, coordinates, pin and «Маршрут гаргах» stay.
    *Note (2026-10-05, change 7b, D194): the offline row (including "one request within 2 s when the network returns"), the 429 row and the network-error row hold when no `search` file is installed. With one, AC 49–51 apply: a card opened without a validated network gets one on-device answer at once, and a network that returns later sends no request because the card already holds an answer.*
12. **Given** a `reverse` request is pending, **When** the user long-presses another point or closes the card, **Then**:
    - the older request is cancelled or its response ignored, and it never fills a newer card;
    - at most **1** `reverse` request is in flight;
    - **0** `reverse` requests are sent for map pans, zooms, the device position or search results. The typed-coordinate option (AC 7) is not a search result: selecting it opens a coordinate card, and that card sends its one `reverse` (AC 8). Showing the option sends **0** `reverse` requests.
13. **Given** an open card with a nearest place, **When** the UI language is switched, **Then** the labels switch within **1 s**, the place name already shown is kept, and **0** new `reverse` requests are sent (NAV-003 AC 38). **Given** the typed-coordinate option of AC 7 is shown, **When** the UI language is switched or the phone is rotated, **Then** the option label changes («Сонгосон цэг» / "Selected point") within **1 s**, the option and coordinates stay, and **0** `search` and **0** `reverse` requests are sent.

### C. Alternatives on the map
14. **Given** a route-preview request (first request, a mode change, an avoid-toggle change or a destination change), **When** it is sent, **Then**:
    - its body is the NAV-005 AC 5 body with **`alternates: 2`**;
    - reroute requests during guidance keep **`alternates: 0`** (NAV-005 AC 43 unchanged, unit test);
    - at most **1** preview request is in flight, and an older response never replaces a newer one (QA delays the first response by 1 s).
15. **Given** a 200 response with *k* routes (1–3), **When** it renders, **Then**:
    - *k* route lines are drawn, and route 1 (Valhalla's first) is selected;
    - the selected line is drawn above the others with the map-style selected token, with contrast **≥ 3:1** against `earth` and major-road colours in both themes;
    - alternatives use a different token and a line ≥ **2 dp** narrower;
    - no route line covers the attribution, scale bar or controls (NAV-005 AC 2 holds);
    - for RS3, QA records *k* (fewer alternatives than requested is normal).
16. **Given** the first render of a response, **When** routes are drawn, **Then** within **1 s** the camera fits all drawn routes and both markers into the map area not covered by the sheet or the top bar, with ≥ **40 dp** padding, at zoom ≤ **17**.
17. **Given** *k* ≥ 2, **When** the user taps an alternative line (touch hit area ≥ **24 dp** on each side of the line centre, so the target is ≥ 48 dp) or its option in «Маршрут сонгох», **Then** within **200 ms**:
    - it becomes the selected, emphasised line;
    - the summary (distance, duration, «Хүрэх цаг», snap notice per D51) shows that route;
    - **0** route requests are sent, and the camera does not move.
    When two lines overlap at the tap point, the line that is **not** selected is chosen.
18. **Given** *k* ≥ 2, **When** the draggable sheet (D55) renders, **Then**:
    - a group named «Маршрут сонгох» has one option per route: «Маршрут {n}» with its distance and duration (NAV-004 AC 23–24 formats);
    - the selected option is exposed to TalkBack as selected;
    - each non-selected option's description includes «Өөр маршрут»;
    - with *k* = 1 the group is not shown;
    - every option is ≥ **48 dp** tall;
    - the sheet can be dragged between a collapsed state, where the summary and «Эхлэх» stay visible, and an expanded state;
    - at 360×640 dp and font scale 100 %, the summary and «Эхлэх» are visible without dragging.
19. **Given** a selected route, **When** «Эхлэх» is activated, **Then**:
    - guidance (NAV-005 sections C–J) follows **the selected route** with **0** additional route requests;
    - the unselected lines are removed within **1 s**;
    - a later reroute produces one route (`alternates: 0`).
    Replay check: with a recorded 3-route response, selecting route 2 and starting guidance gives the route 2 banner sequence.
20. **Given** routes are drawn, **When** the theme or language changes, or the phone rotates, **Then** the lines, markers and selection stay (new colours or texts within **1 s**), and **0** route requests are sent.
21. **Given** a mode, avoid-toggle or destination change, **When** the new response renders, **Then** route 1 of the new response is selected. The earlier selection is not carried over.

### D. «Дугуй» (bicycle) mode
22. **Given** the route preview, **When** it opens, **Then**:
    - a tab row named «Зорчих хэлбэр» shows «Машин», «Явган», «Дугуй», with «Машин» selected by default;
    - the tabs map to `costing` `auto`, `pedestrian`, `bicycle`;
    - a tab that stays selected for **300 ms** sends one request; two tab changes within 300 ms send **1** request, for the final tab;
    - the selected mode is kept for the rest of the app session.
23. **Given** «Дугуй» or «Явган», **When** the sheet renders, **Then**:
    - «Шороон замаас зайлсхийх» is hidden, and requests carry **no** `costing_options` (contract: `exclude_unpaved` is for `auto` only);
    - back on «Машин», the toggle shows its kept state, and the request carries `exclude_unpaved: true` if the toggle is on.
24. **Given** a 400 `DistanceExceeded` on «Дугуй» or «Явган» (injected if the dev server does not limit it), **When** it arrives, **Then** «Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» is shown. On «Машин» the same code shows «Маршрут олдсонгүй». Every other preview state follows NAV-005 AC 7.
25. **Given** a «Дугуй» route, **When** «Эхлэх» is activated ([D111](../decisions.md)), **Then** guidance runs as NAV-005 (banner, voice, progress, off-route, GPS loss, arrival), and every reroute carries `costing: "bicycle"` and no `costing_options` (NAV-005 AC 43: same costing).
26. **Given** «Дугуй» guidance, **When** prompts are scheduled, **Then**:
    - the prompt distances, chaining distance and camera zoom come from a **«Дугуй» column** that UX adds to `docs/design/navigation-ux.md` §4.2 / §4.4 / camera table;
    - replay **G10** checks them with the NAV-005 AC 34 rules (one utterance per trigger, start within **1 s**, distance error ≤ max(**30 m**, **20 %**), drop after **3 s**);
    - for every manoeuvre except `depart` (and the D68 `arrive` exemption), at least one prompt is spoken in the band UX defines for «Дугуй»;
    - until UX defines the column, the walk («Явган») column applies, and G10 checks against it.

### E. Typing lock while moving, with a passenger override (D65)
27. **Given** location permission (precise) is already granted and location services are on ([D113](../decisions.md): no other precondition, such as having used my location first), **When** the map screen (S1) or the route preview (S3) is in the foreground, **Then**:
    - the app reads platform location fixes (`LocationManager`, D62) at **1 Hz** for the lock;
    - these updates stop within **2 s** after neither screen is in the foreground;
    - the lock never causes a permission or settings prompt;
    - the lock reads no location in the background.
28. **Given** fixes for the lock, **When** they are used, **Then**:
    - they stay **on the device**: the lock adds **0** network requests and stores nothing;
    - the NAV-005 AC 67 log and storage scan finds **0** coordinates and **0** speeds after a lock session (Robolectric).
29. **Given** the fix speed (Terms), **When** it is evaluated, **Then** the rule is a pure function, unit-tested with a fake clock.
30. **Given** the lock is released, **When** **3 consecutive good fixes**, spanning ≥ **2 s**, each have a speed ≥ **4.2 m/s (15 km/h)**, **Then** the lock engages within **1 s** of the third fix. Fixes with accuracy > 25 m are ignored and never engage it. Only a good fix **with a fix speed** (Terms) counts towards the 3. A fix without `hasSpeed()` gets a derived speed only from a previous good fix 1–10 s earlier, so the first fix of such a sequence has no fix speed and does not count (AC 36 ix). *Confirmed 2026-10-04 (BA): this is the intended rule, "3 consecutive fixes, each ≥ 15 km/h, spanning ≥ 2 s".*
31. **Given** the lock is engaged, **When** the user taps a text-entry surface, **Then**:
    - the keyboard does **not** open;
    - within **500 ms** the field shows «Хөдөлж байх үед бичих боломжгүй» (K1), «Жолооч бол зогсоод хайна уу» (K3) and the button «Би зорчигч» (K2).
    **When** the lock engages while the keyboard is open, **Then**:
    - the keyboard is hidden within **1 s**;
    - the text typed so far is kept, any in-flight search completes, and its results list stays visible and selectable;
    - K1 appears. TalkBack announces K1 politely, once per engagement.
32. **Given** the lock is engaged, **When** every good fix in the last **10 s** has a speed < **1.4 m/s (5 km/h)** and there are ≥ **5** such fixes, **Then** the lock releases within **1 s**. Between 5 and 15 km/h the state does not change (hysteresis). It also releases within **1 s** when:
    - no good fix has arrived for **30 s** (tunnel, garage, GPS loss);
    - location permission is revoked;
    - location services are turned off.
33. **Given** the lock is engaged, **When** the user does anything other than typing, **Then** it is allowed:
    - selecting a result already shown, **including the typed-coordinate option «Сонгосон цэг»** (AC 7, 7a: the card, its `reverse` and the camera centring work as when unlocked);
    - the clear button «Хайлтыг арилгах»;
    - long-press and the coordinate card;
    - «Маршрут гаргах», the mode tabs, route options, the avoid toggle and «Эхлэх»;
    - recenter, zoom, pan, theme and language.
    The lock only blocks the keyboard.
34. **Given** the lock is engaged, **When** «Би зорчигч» is activated, **Then** ([D110](../decisions.md)):
    - within **500 ms** the field takes focus and the keyboard opens;
    - the lock stays overridden for the **rest of the app session** (until the process ends or the app is removed from Recents);
    - the override is **never** stored across app restarts and never switched on automatically;
    - a fresh app start has no override (Robolectric: process recreation → no override).
35. **Given** no usable fixes, **When** typing, **Then** the lock is never engaged. This covers:
    - permission not granted, or approximate only (Android 12+, fixes usually > 25 m);
    - location services off;
    - no fix yet.
    This is accepted: the lock is a safety aid, not a guarantee (R7).
36. **Given** synthetic 1 Hz fix sequences in a JVM test, **When** the lock rule runs, **Then**:
    - **(i)** 14 km/h steady for 120 s → never engaged;
    - **(ii)** 16 km/h for 3 fixes with `hasSpeed()` → engaged after fix 3;
    - **(iii)** walking 1.4 m/s for 600 s → never engaged;
    - **(iv)** 20 km/h with 40 m accuracy for 60 s → never engaged;
    - **(v)** engaged, then 4 km/h for 10 s with ≥ 5 good fixes → released;
    - **(vi)** engaged, then 8 km/h for 120 s → stays engaged;
    - **(vii)** engaged, then no fix for 30 s → released;
    - **(viii)** one outlier fix at 60 km/h between fixes at 0 km/h → never engaged;
    - **(ix)** fixes without `hasSpeed()`, 50 m apart at 1 s intervals → engaged after the **4th fix** (3 derived speeds; the rule stays AC 30), not earlier.
37. **Given** guidance is active, **When** the guidance screen is shown, **Then** the lock is irrelevant, because there is no text input (NAV-005). Returning to the map screen after «Дуусгах» applies the rule from the current fixes.

### F. Strings, privacy, regression and verification
38. **Given** the Android resources, **When** the NAV-005 AC 61 checks run, **Then**:
    - every new user-facing string comes from resources, with identical `mn` and `en` key sets;
    - **0** hard-coded literals;
    - every `mn` value matches a glossary term exactly. K1–K3 match section 2.4. N1, N6, N11, N19, N20, "Alternative route", T7 and the reused rows match their rows.
39. **Given** a full session (assisted search, reverse on 3 cards, preview in all three modes with alternatives, guidance on «Дугуй», the typing lock and its override), **When** all requests are captured, **Then**:
    - every request goes to the configured gateway, with **0** to other hosts (NAV-005 AC 65);
    - coordinates appear only in route POST bodies, the D30-rounded search bias and the user-chosen `reverse` point (NAV-005 AC 68 extended to `reverse`). The device position never appears in a `search` or `reverse` request;
    - a coordinate pair the AC 7 rule recognises **never** appears in a `search` `q` ([D140](../decisions.md); the D115 exception is removed). A typed point leaves the device only as the user-chosen `reverse` point and, after «Маршрут гаргах», in the route POST body;
    - text the AC 7 rule does **not** recognise as a coordinate pair (decimal comma, DMS, out of range such as "106.9176, 47.9189", a pair with other words) is user query text and may appear in `q` as typed. The privacy scan treats it as user text, not as a coordinate leak.
40. **Given** the NAV-005 regression suite, **When** `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required` runs in `mobile/android`, **Then**:
    - it passes;
    - the only NAV-005 tests changed are those asserting behaviour this story replaces (Context table: NAV-005 AC 3, 4, 5, 6), each listed in the QA handoff with the reason;
    - no other NAV-005 assertion is weakened;
    - for the D140 change (2026-10-04), the only existing test whose assertion is inverted is the NAV-011 test `AssistedSearchTest.typedCoordinateIsStillSentAsTyped` (AC 7: it asserted 1 request as typed, now 0 requests and the coordinate option); this is listed in the QA handoff with the reason. No NAV-005 test asserts typed coordinates, so no NAV-005 test changes for it.
41. **Given** the shared fixtures (manoeuvres, ADR-0006 vectors), **When** NAV-011 is done, **Then** `npm test` in `web/` passes unchanged, and NAV-003/NAV-004 web behaviour is unchanged.
42. **Given** the repository after this story, **When** it is inspected, **Then**:
    - NAV-011 code is in its own packages where possible;
    - shared files show only additive or targeted NAV-011 edits;
    - **no line added by NAV-012 is removed or reverted** (reviewer checks the diff of shared files);
    - there are no secrets, hostnames or IPs other than loopback;
    - the shared dev stack was not stopped, restarted or rebuilt.
43. **Given** RS1–RS4 against the live dev gateway when `/health` is 200 (20 samples, ≤ 2 requests per second), **When** measured in a JVM test from the triggering action to the UI state holding the parsed routes, **Then** ≥ **19 of 20** take ≤ **1,500 ms**. On-device render time is a real-device check (AC 47).
44. **Given** the TalkBack and size rules, **When** the new controls render (tabs, route options, K2, reverse states), **Then**:
    - touch targets are ≥ **48 × 48 dp** and text contrast ≥ **4.5:1** in both themes;
    - at font scale 200 % no new text is cut mid-word;
    - the NAV-005 attribution rule (AC 2) still holds;
    - D66 applies unchanged.
45. **Given** light-QA mode ([D108](../decisions.md)), **When** QA reports, **Then**:
    - the test plan maps **every** AC to a JVM/Robolectric/replay test, or to "not verified" with the reason;
    - the runs are those listed in Context, plus G10 and the AC 19 replay;
    - AC 6 and AC 43 run live only if `/health` is 200;
    - everything not run is listed for the full QA before any public release.
46. **Given** the debug APK, **When** `./gradlew :app:assembleDebug` runs, **Then** it builds, and `mobile/android/README.md` notes any new build property (none expected).
47. **Given** the checks that need a real Android phone, **When** QA reports, **Then** these are listed as **not verified** (no Android test phone; the PO's phone is an iPhone):
    - the typing lock in a moving car with real GPS (speed noise, urban canyon, red-light stops), and in a bus as a passenger;
    - keyboard hiding with several OEM keyboards;
    - the alternative-line hit area with gloves;
    - sheet dragging;
    - on-device render times (AC 43);
    - «Дугуй» guidance audibility while cycling;
    - TalkBack on a device;
    - battery cost of 1 Hz fixes on S1/S3.

### G. Search and reverse with an installed `search` file (change 7b, D194), added 2026-10-05
*These AC state what NAV-023 built on the NAV-011 screens. "Validated network" and "installed file" are as in NAV-005 Terms. The device answers **at once** without a validated network, or **3.0 s after the online request was sent** with one ([D199](../decisions.md); NAV-023 AC 11–15). Device timings are measured on the PO's Redmi Note 8 Pro; the low-end column is not verified ([D204](../decisions.md)).*

48. **Search without a validated network.** **Given** an installed `search` file and **no** validated network, **When** a query settles (AC 2–4), **Then**:
    - the on-device engine answers at once, **0** `search` requests are sent, and «Интернэт холболт алга» is **not** shown;
    - the engine runs the unchanged ADR-0012 query plan (coordinate rule first, abbreviation expansion, Latin and vowel variants, merge), so "Sukhbaatar" returns the same place as «Сүхбаатар» (NAV-023 AC 3, 17);
    - the list uses the same result model, type labels, 10-option cap, row height (≥ **48 dp**) and place card as an online list (NAV-023 AC 7), with the D30 / D174 bias of AC 7 (NAV-023 AC 16);
    - the list shows the indicator OF24 «Офлайн» **once**, with the accessible name OF25 «Офлайн газрын зургаас»; after the results render, the screen-reader announcement is «{count} илэрц олдлоо» followed by OF25; «Илэрц олдсонгүй» from the device shows OF24 too; a place card opened from a device result carries OF24 (NAV-023 AC 21–22);
    - the indicator never covers the search field, a result name, «Маршрут гаргах» or the attribution, and has contrast ≥ **4.5:1** in both themes (NAV-023 AC 24);
    - from query dispatch to the list ready, p95 is **≤ 150 ms** on the mid-range phone (NAV-023 AC 9; the low-end figure is not verified);
    - an older answer, from the device or the gateway, never replaces the list of a newer settled query (AC 7).
49. **Search with a validated network: online first, then the device.** **Given** an installed `search` file and a validated network, **When** a `search` request is sent (each request of an assisted pair, AC 2–4), **Then** the device answers **in the same attempt** when the connection fails, the gateway answers 502/503/504 or 429, or no response headers arrive within **3.0 s** (JVM fake clock: 3.0 s; on the phone ≤ **3.2 s**). Once headers arrived, the AC 7 rules apply (8 s body timeout). An authoritative answer, a 200 with **0** results («Илэрц олдсонгүй») or a 400, is shown as before with **0** on-device queries. Within **60 s** after a fallback caused by the 3.0 s budget or a connection failure, searches **and** reverses go straight to the device (**0** online requests) until 60 s have passed or a newly validated network is reported, so each settled query answers within the AC 48 time and not after 3 s. During a 429 `Retry-After` window (5 s if missing or invalid) the device answers, **0** online requests are sent, and «Түр хүлээгээд дахин оролдоно уу» is not shown while the device answers.
50. **The device cannot answer.** **Given** an installed `search` file that fails to open or answer (for example damaged after install), **When** a search or a reverse falls back to it, **Then** «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» is shown (the existing state; «Дахин оролдох» re-sends once), **0** crashes occur, and the failure is logged without the query text or coordinates (NAV-023 AC 25).
51. **Reverse on the coordinate card.** **Given** an installed `search` file, **When** the card «Сонгосон цэг» opens (long-press, or the typed-coordinate option, AC 7a, 8) with **no** validated network, **Then** exactly **one** on-device reverse runs at once with the card's point (not rounded, AC 8), **0** `reverse` requests are sent, and «Интернэт холболт алга» (the AC 11 offline row) is not shown. The nearest-place area shows «Ойролцоох газар» followed by OF24 (NAV-023 AC 23, [D208](../decisions.md)) and the name, type label and context line as AC 9; «Илэрц олдсонгүй» from the device also carries OF24; OF25 is read after the label. At P1 the nearest place is within **50 m** of the online result (or the same object) and ≤ **300 m** from P1 (AC 9; NAV-023 AC 20); the on-device reverse takes **≤ 100 ms** p95 on the mid-range phone (NAV-023 AC 9). With a validated network the reverse goes online first under AC 49. «Маршрут гаргах» is usable at once and the destination text stays «Сонгосон цэг» (AC 8, 9). A network that returns while the card is open sends **no** request, because the card already holds an answer.
52. **Typed coordinates and language switch.** **Given** a recognised coordinate pair (AC 7), **When** it settles, with or without a `search` file and with or without a network, **Then** **0** `search` requests and **0** search-file queries occur, exactly one option «Сонгосон цэг» is shown (NAV-023 AC 8), and selecting it opens the card whose reverse follows AC 51. **When** the UI language is switched or the phone is rotated with a device list or card open, **Then** AC 13 holds (labels within **1 s**, **0** requests, **0** new device queries for an open card).
53. **Privacy.** **Given** a session of online, fallback and offline searches and reverses, **When** Logcat, files and preferences are scanned (NAV-005 AC 67 method), **Then** **0** query texts and **0** coordinates (regex `-?\d{1,3}\.\d{4,}`) are found (NAV-023 AC 28), **0** `search` or `reverse` requests are sent while answers come from the device without a network, and AC 39 holds unchanged: the device position never appears in a `search` or `reverse` request.
54. **Without a `search` file nothing changes.** **Given** no installed `search` file (no pack, before the first install, after delete, or a file refused by its schema check), **When** the NAV-011 tests and the NAV-005 suite run (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`), **Then** AC 7 and AC 11 and every test of this story pass **unchanged** (NAV-023 AC 26), the typing lock (AC 27–37) is unaffected, and the web `npm test` and NAV-003 behaviour are unchanged (NAV-023 AC 27).
55. **Verification (light process, D108, D155).** **Given** change 7b, **When** QA reports, **Then** JVM tests with a fake clock and a mock server cover AC 48–54 (NAV-023 AC 30). The D168 offline gate and the D198 held-out report belong to NAV-023, not to this story. Device timings (AC 48, 51) are measured on the PO's phone; the low-end column is **not verified**.

## Edge cases
- **GPS lost / tunnel:** lock releases after 30 s without a good fix (AC 32); guidance follows NAV-005 AC 51–53 on every mode, «Дугуй» included.
- **Noisy GPS in the dense centre:** a single outlier never engages the lock (AC 36 viii); 3 consecutive fast fixes are required.
- **UB traffic jam:** stop-and-go below 15 km/h does not engage the lock; once engaged, it stays until 10 s below 5 km/h (AC 32), so a red light longer than 10 s releases it. Accepted.
- **Passenger in a taxi or bus:** one tap on «Би зорчигч» for the session (AC 34).
- **No location permission or approximate only:** no lock (AC 35); search works as before.
- **No network:** search and reverse show «Интернэт холболт алга» with 0 requests (AC 7, 11); the card still offers «Маршрут гаргах»; the preview shows NAV-005 AC 7 offline state. *With an installed `search` file (2026-10-05, AC 48–51):* search and the card's «Ойролцоох газар» are answered on the device at once, with 0 requests and the «Офлайн» indicator; on a slow connection the device answers 3.0 s after the request was sent; the preview follows NAV-005 AC 81.
- **Offline search quality (2026-10-05):** the device ranks with its own code, so order and recall can differ from Photon, especially for category queries («Галт тэрэгний буудал» may fail, D168), countryside soum and bag names, and ger-district addresses. Data can be up to about 7 days old (search dump weekly). The indicator tells the user the list came from the phone (NAV-023 R1–R4).
- **Rate limits (429):** search, reverse and route each respect `Retry-After`; nothing auto-retries in search/reverse (AC 11).
- **No result:** search «Илэрц олдсонгүй»; reverse «Илэрц олдсонгүй» (AC 10); no route «Маршрут олдсонгүй»; «Дугуй»/«Явган» too far → N11 (AC 24).
- **Cyrillic/Latin:** "Sukhbaatar" vs «Сүхбаатар», "suhbaatar", Russian-layout «Сухбаатар», «БЗД 4-р хороо» (AC 2–6). Mixed-script or digits-only queries are sent as typed, except a recognised coordinate pair (next item).
- **Typed coordinates (D140):** a recognised pair shows one option «Сонгосон цэг» with **0** `search` requests; selecting it centres the camera and opens the coordinate card with 1 `reverse` (AC 7, 7a, 8). The common case is a **taxi / delivery driver** pasting a pair a customer sent in a messenger: settle trims the text and normalises no-break spaces (U+00A0, U+202F), so "47.9189, 106.9176" pasted with those spaces still matches; a full-width comma or a pair inside other words is not recognised and is searched as text (AC 7, accepted).
- **Typed coordinates offline or rate-limited:** the option still shows, with 0 requests; the card then shows «Интернэт холболт алга» or the 429 state in its nearest-place area (AC 7, AC 11), and «Маршрут гаргах» still works.
- **Typed coordinates swapped or out of range** ("106.9176, 47.9189"): searched as text in 1 request, usually «Илэрц олдсонгүй». Decimal comma ("47,9189, 106,9176") the same (AC 7). Two plain integers such as "47 106" become the option, not a search (D143, as on the web): a user who meant a number-only search sees «Сонгосон цэг» instead (accepted).
- **Typed coordinates outside Mongolia** (for example X2 Beijing): the card opens with «Илэрц олдсонгүй» or a far place, and the preview then shows the NAV-005 outside-area state. Expected, not a defect.
- **Only one route returned** (common on UB's grid or intercity): no route options group (AC 18), behaviour as NAV-005.
- **Overlapping alternative lines:** tap picks the unselected one (AC 17).
- **Unpaved roads:** avoid toggle on «Машин» only; «Дугуй» routes may use unpaved roads (R6).
- **Off-route on «Дугуй»:** reroute with `bicycle` and `alternates: 0` (AC 25, 19).
- **Destination far from any road:** snap notice per D51 for the selected route (AC 17).
- **Winter:** cycling routes are still offered (no seasonal rule; R6); gloves: 48 dp targets and the 24 dp line hit margin (AC 17, 44); cold GPS starts can delay good fixes, so the lock may engage late (accepted).
- **Language switch** with an open card, list or coordinate option (AC 13, AC 7); **rotation** with the coordinate option shown (AC 13) or in preview (AC 20).
- **App restarted:** passenger override is gone (AC 34); mode returns to «Машин» (AC 22).

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **`name:en` / Latin name coverage** (NAV-001 R6) | Latin queries depend on transliteration; tier B rows can fail on data gaps | Counted only when the Cyrillic control passes (AC 6); tier C baseline |
| R2 | **Address coverage** (`addr:*`, khoroo, ger plots; NAV-001 R9) | Reverse often returns a POI or area, not the point's address | Label «Ойролцоох газар», never «Хаяг»; destination stays «Сонгосон цэг» (AC 9) |
| R3 | **Port drift between Kotlin and TypeScript** (ADR-0006 R11) | Android and web assist differently | Shared vectors (AC 5), or server-side (option (b)) |
| R4 | **Few alternatives in Valhalla output** for UB and intercity | Users rarely see a choice | Recorded *k* (AC 15); no promise of alternatives |
| R5 | **`maxspeed` coverage, no traffic** (NAV-001 R8) | Durations of alternatives compare poorly in rush hour | Accept for Phase 1; Phase 3 traffic |
| R6 | **Bicycle network and `surface` coverage sparse** (NAV-004 R7) | «Дугуй» routes are road routes, may include dirt roads; winter cycling unrealistic | No cycleway promise; N11 for long distances |
| R7 | **GPS speed noise and approximate-only users** | Lock false positives (urban canyon) or no lock at all | 3-fix rule, accuracy gate, hysteresis (AC 30–32, 36); a safety aid, not a guarantee (AC 35); real-car check (AC 47) |
| R8 | **Battery cost** of 1 Hz location on S1/S3 for the lock | Drain while browsing the map | Only in foreground, stops within 2 s (AC 27); device check (AC 47) |
| R9 | **Personal data:** the `reverse` point and search bias go to the backend (D9, D30) | Legal gate before outside testers on Singapore staging | Team-only staging (D9/D26); NAV-008 AC 24 |
| R10 | **Parallel NAV-012 edits in shared files** | Merge conflicts or lost changes | AC 42; own packages; latest-file reads |
| R11 | **Server-side option (b) changes the contract** | Backend work and a web change during NAV-011 | Architect ADR first (AC 1); web unchanged unless its own change request |
| R12 | **Offline search differs from Photon** (2026-10-05; NAV-023 R1–R4): own ranking, a weekly search dump, thin OSM names in the countryside | Different order, missing newest places, category queries fail offline | Online first (D163); NAV-023 gate D168 and held-out report D198; the «Офлайн» indicator (AC 48); data dates in «Тохиргоо» (NAV-022 AC 39). OSM name coverage is a data dependency (NAV-001 R6) |

## Out of scope
- iOS (NAV-015).
- Choosing an origin other than «Миний байршил» on Android (search or map as origin, the swap button), and the turn list «Маршрутын заавар» in the Android preview (web NAV-004 has both). Separate follow-up through triage ([D112](../decisions.md); backlog draft row).
- Coordinate formats other than the ADR-0006 §2.2 rule: degrees-minutes-seconds (47°55'08"N), `geo:` URIs, Google Plus codes, decimal commas. They are searched as text (AC 7). Any change to the shared rule goes to both clients together through a NAV-003 change request (ADR-0012 §2, [D143](../decisions.md)).
- The typed-coordinate option in the NAV-018 start and destination fields: NAV-018 AC 5 builds it on the ADR-0012 §3 coordinate state ([D145](../decisions.md)).
- Switching to an alternative route during guidance.
- Search history, favourites, voice search. (Offline search was listed here until 2026-10-05; the whole-Mongolia on-device search is built by NAV-023 and described in section G, D194. A category browser and recent searches stay out of scope, NAV-023.)
- Hamuga search (D38).
- Reverse geocoding of the device position.
- A toll option (D60).
- Any change to NAV-003/NAV-004 web behaviour.
- Any gateway or `openapi.yaml` change unless the ADR picks option (b).

## Open questions
**None open.** All four were decided on 2026-10-04 (PO "All as recommended"), each as recommended. The AC already matched them.
1. **How long the passenger override lasts** (AC 34). Options: (a) rest of the app session (BA default); (b) the current moving episode only, so the user is asked again after each stop; (c) 30 minutes. *Recommendation: (a).* (b) would ask a bus passenger at every red light. **Decided: (a), [D110](../decisions.md).**
2. **«Дугуй» guidance or preview only** (AC 25–26). Options: (a) «Эхлэх» works on «Дугуй», with a UX «Дугуй» prompt column (BA default); (b) preview only, «Эхлэх» disabled on «Дугуй». *Recommendation: (a).* Parity with the other tabs, and the NAV-005 engine already supports any costing. **Decided: (a), [D111](../decisions.md).**
3. **Origin choice and turn list in the Android preview** (Out of scope). Options: (a) separate follow-up through triage; (b) add to NAV-011 (+M). *Recommendation: (a).* **Decided: (a), [D112](../decisions.md).** Backlog draft row added.
4. **Location for the lock without "my location"** (AC 27). Options: (a) read fixes on S1/S3 whenever permission is already granted, on-device only (BA default); (b) only after the user has activated my location or the preview origin, so the lock is inactive before that. *Recommendation: (a).* Otherwise the lock rarely works on the screen where people type. **Decided: (a), [D113](../decisions.md).**

Also decided on 2026-10-04 (not story open questions): the K2 label «Би зорчигч» stays for now ([D114](../decisions.md)), and typed coordinates are sent as typed, with web parity as a follow-up ([D115](../decisions.md), AC 7). **Later on 2026-10-04** the follow-up was approved as a change on this story: typed coordinates open the card ([D140](../decisions.md), superseding that part of D115), with the change-run questions decided as recommended ([D141–D146](../decisions.md)).

## Traceability
| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–7 | `docs/design/screens/NAV-011-android-route-preview-search-parity.md` (S2 search; "Coordinate typed" state added by the D140 change, UX); flow F1 coordinate branch (UX); NAV-003 screen spec (reference) | `search` (0 calls for a recognised coordinate pair) | ADR-0006 §2.2; ADR-0012 (accepted 2026-10-03, option (a) Kotlin port; §3 coordinate input revised by the architect for D140: coordinate state before the offline and cooldown checks, reusable by NAV-018) | `mobile/android/…/search/SearchController.kt`, `…/search/assist/QueryPlan.kt` (1c2bf4f; D140 change in run 2) | `QueryPlanVectorsTest` (shared fixture), `AssistedSearchTest` (`typedCoordinateIsStillSentAsTyped` inverted for D140, plus offline, 429, rejected-pair and generation cases), golden-set JVM test; test plan rows 1–7 | D28, D30, D33, D42, D48, D115 (superseded in part), D140, D143, D146; NAV-003 AC 26 (reference); NAV-005 AC 3 replaced |
| AC7a | S2 coordinate option selection and camera rule (UX); flow F1 → F2 | `reverse` (via AC 8) | ADR-0012 §3–§4 | `…/ui/screens/BrowseOverlay.kt`, `…/ui/AppViewModel.kt`, `…/ui/NavRoot.kt` (D140 change, run 2) | `Nav011ActivityTest` / `QaNav011ActivityTest` TC-A12 (option, 0 `search`, card, 1 `reverse`, camera zoom ≥ 16), `Nav011OverlayTest` (option row mn/en, ≥ 48 dp) | D140, D142, D146; NAV-003 AC 26 |
| AC8–13 | coordinate card (S2 coordinate card; entry from long-press or the typed-coordinate option) | `reverse` | ADR-0012 §4 | `…/search/reverse/` (1c2bf4f) | `ReverseTest`, `Nav011ActivityTest.coordinateCardSendsExactlyOneReverseAndNoneOnRotation`, TC-A12 (typed entry) | T6, T7; NAV-001 R9; NAV-005 AC 4 replaced; D140 (AC 8, 9, 12, 13 wording) |
| AC14–21 | route preview sheet, map-style route section (TBD) | `postRoute` (`alternates`) | ADR-0008, ADR-0009 | TBD | JVM, replay AC 19 (TBD) | D51, D55; NAV-005 AC 5, 6 replaced |
| AC22–26 | tabs; navigation-ux «Дугуй» column (UX) | `postRoute` (`bicycle`) | ADR-0009 | TBD | replay G10 (TBD) | N1, N11, N19; D60, D111 |
| AC27–37 | typing lock states (NAV-011 screen spec and flow) | — | ADR-0012 (typing lock) | TBD | JVM sequences, Robolectric (TBD) | D62, D65, D110, D113, D114; K1–K3; NAV-005 L1 |
| AC33 (typed-coordinate option while locked) | typing lock states (NAV-011 screen spec) | `reverse` (via AC 8) | ADR-0012 | `…/ui/screens/BrowseOverlay.kt` (D140 change) | TC-A12 locked case, `Nav011OverlayTest` | D140 |
| AC38–47 | — | all | — | `mobile/android/**` | NAV-005 suite, test plan `docs/qa/test-plans/NAV-011.md` (row 39 / TC-P39: recognised pair never in `q`; row 40: changed `AssistedSearchTest` case) | D108, D140 (AC 39 exception removed, AC 40 test list), D141 (accepted); NAV-012 and NAV-018 coordination |
| AC48–55 (2026-10-05, change 7b; notes on AC 7, 11) | NAV-022 screen spec `docs/design/screens/android-offline-pack.md` (O7 indicator: S2 list `ind-list`, place card, coordinate card `ind-card`); NAV-011 screen spec S2 (states unchanged) | `search`, `reverse` (online first, then on-device) | ADR-0017 §3, §5; ADR-0012 (query plan, shared by the device engine) | `…/search/offline/` (`SearchSources.kt`, `OfflineSearch.kt`, `OfflineSearchEngine.kt`), `…/search/SearchController.kt`, `…/search/reverse/Reverse.kt`, `…/ui/screens/BrowseOverlay.kt`, `…/ui/screens/RoutePreviewSheet.kt` (built by NAV-023) | the NAV-023 tests (shared normalisation vectors, fake clock and mock server for the fallback); the existing NAV-011 tests unchanged (AC 54); device timings on the PO's phone | D163, D168, D194 (change 7b), D198, D199, D201, D204, D208; NAV-023 AC 3, 7–9, 11–17, 20–26, 28; glossary OF24, OF25 |
| Out of scope | — | — | — | — | — | D112 (now NAV-018), D143 (coordinate formats), D145 (NAV-018 fields) |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-03 | NAV-011 feature request (orchestrator, feature-delivery; PO "go ahead" 2026-10-03) | Created from the backlog draft row: 47 AC in sections A–F (query assistance written for the port or the server-side option, reverse on the coordinate card, alternatives with a draggable sheet, «Дугуй» including guidance with a UX prompt column, typing lock speed rule with the passenger override, strings, privacy, regression, light-QA verification). Priority **must** (Phase 1 beta), D106. Size M → L. Glossary rows K1–K3 added (`needs native review`). GPX track G10 added. Relation to NAV-005 AC 3–6 recorded without editing NAV-005 (mid-flight rule). Four non-blocking open questions | Refine NAV-011 to `ready` for UX and the architect |
| 2026-10-04 | PO answer "All as recommended" in chat to the NAV-011 questions, items 1–7 ([D110–D116](../decisions.md)); item 15 re-confirms priority must (D124); QA open wording items | Open questions 1–4 marked decided (D110–D113), and the AC already matched them (AC 25, 27 and 34 now cite them). K2 note: «Би зорчигч» kept, the panel decides later (D114). **AC 7:** new bullet, a typed coordinate pair is sent as typed in 1 request with no coordinate card (D115). **AC 39:** a coordinate the user typed may appear as text in `q` (D115). Out of scope: origin choice / turn list and the coordinate-card web parity are follow-ups through triage (D112, D115; backlog draft rows). **QA wording:** AC 30 states that only good fixes with a fix speed count, so the first fix without `hasSpeed()` does not count, and the rule "3 consecutive fixes, each ≥ 15 km/h, spanning ≥ 2 s" is confirmed as intended; AC 36 (ii) says "with `hasSpeed()`", and **AC 36 (ix)** now says "engaged after the 4th fix (3 derived speeds)". Context lists the new decisions; Traceability names ADR-0012, the screen spec and the test plan. D116 (NAV-017, D17 scope) does not change this story | Record the PO decisions and close the QA wording items without changing the agreed behaviour. (ix) said "after 3 fixes", which contradicted AC 30, because a derived speed needs a previous fix |
| 2026-10-04 | Change request "typed coordinates open the «Сонгосон цэг» coordinate card on Android" (D115 follow-up; triage P2 / standard, [D137](../decisions.md)); run 1 impact approved by the PO in chat, "All as recommended" ([D140–D146](../decisions.md)) | **Status:** NAV-011 accepted in light-QA mode (code 1c2bf4f, light QA 0 defects), `ready` → `done` ([D141](../decisions.md)); the D137 "after acceptance" gate is met. **AC 7:** the D115 "sent as typed, no card" bullet is replaced by a Given/When/Then for a recognised ADR-0006 §2.2 pair: 0 `search` requests, no loading row, one option «Сонгосон цэг» plus 5-decimal coordinates with the existing `ic_location_searching` icon, ≥ 48 dp, also offline and during a 429 cooldown (coordinate check before those checks), «Дахин оролдох» and network resume send 0, generation rule; integer pairs count ([D143](../decisions.md)); rejected pairs (out of range, decimal comma) are 1 request as typed. **New AC 7a:** selection closes the list, keyboard and search field within 500 ms ([D146](../decisions.md)), centres the camera at zoom 16 or the current zoom if higher with the point clear of the card and top bar, stops following the device ([D142](../decisions.md)), and opens the long-press card. **AC 8:** the card also opens from the typed option; `reverse` uses the typed values, not rounded. **AC 9:** the camera does not move *after the card has opened*. **AC 12:** the option is not a search result; its card sends its one `reverse`. **AC 13:** language switch or rotation with the option shown: label within 1 s, 0 `search` / 0 `reverse` (AC 20 unchanged: it covers drawn routes only). **AC 33:** the option is selectable while the lock is engaged. **AC 39:** the D115 exception is removed; a recognised pair never appears in `q`; unrecognised text stays user text. **AC 40:** `AssistedSearchTest.typedCoordinateIsStillSentAsTyped` is the one inverted test; no NAV-005 test changes for it. Context (parity list, NAV-005 AC 3 row, decision list), Edge cases (typed coordinates, messenger paste, offline, 429, swapped/out of range, integer pairs, outside Mongolia), Out of scope (coordinate-card item removed; DMS, `geo:` URIs, Plus codes and decimal commas stay out; NAV-018 fields built by NAV-018, [D145](../decisions.md)) and Traceability (AC 1–7, new AC 7a and AC 33 rows, AC 8–13, AC 38–47) updated. Sequencing: this change lands before the parallel NAV-018 build, and ADR-0012 §3 defines the reusable coordinate state ([D144](../decisions.md)). No new Mongolian text, no glossary or contract change | Web parity with NAV-003 AC 26 on the keyboard path. Taxi and delivery drivers get coordinates from customers in messengers. Typed coordinates also stop reaching the gateway in `q`, so AC 39 is tighter again |
| 2026-10-04 | Review finding on the D140 change (AC 9 camera wording), relayed by the orchestrator with the NAV-018 decisions | **AC 9** bullet 2 reworded: the pin, heading and coordinates do not move; for a typed coordinate the one AC 7a centring move runs at the selection, **after the card's first layout**, and after that the camera does not move; for a long-press the camera does not move at all. Replaces "before the card opens", which contradicted the AC 7a inset rule (the centring needs the card's measured height). Wording only: no behaviour, string, contract or decision change (D142 unchanged) | Make AC 9 match AC 7a and the build, so QA tests one camera move at the right moment |
| 2026-10-05 | Change 7b ([D194](../decisions.md), P1, "offline search and reverse states"), run after NAV-023 was written and built; PO chose "finishing touches" 2026-10-05 | **New section G, AC 48–55** (AC 1–47 keep their numbers): search without a validated network answered on the device at once, 0 requests, no «Интернэт холболт алга», unchanged ADR-0012 query plan, same result model, OF24 once per list with OF25 and the «{count} илэрц олдлоо» announcement, also on «Илэрц олдсонгүй» and on a place card, p95 ≤ 150 ms on the mid-range phone (AC 48); online first with the device answering after 3.0 s or on a connection failure, 502/503/504 or 429, authoritative 200-with-0-results and 400 answers kept, 60 s stickiness shared with reverse, 429 line hidden while the device answers (AC 49, D199); a damaged search file shows «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» (AC 50); reverse on the card answered on the device at once with OF24 next to «Ойролцоох газар», within 50 m of the online result at P1, ≤ 100 ms p95, no request when the network returns later (AC 51, D208); typed coordinates unchanged, 0 search-file queries, language switch (AC 52); privacy scan (AC 53); no installed file = every AC and test unchanged (AC 54); light-process verification (AC 55). **Notes** on AC 7 and AC 11 (their offline, network-error and 429 rows hold only without a `search` file). **Context** bullet, **Edge cases** ("No network" extended, "Offline search quality" added), **Out of scope** (the "offline search" item is replaced; category browser and recent searches stay out), **R12**, **Traceability** row AC48–55. Status stays `done`; no behaviour of AC 1–47 changes; no contract change, no new Mongolian text (OF24 and OF25 exist), no D row | NAV-011 said that offline means «Интернэт холболт алга» with 0 requests and that offline search is out of scope. With NAV-023 built, a phone with an installed search file behaves differently, so the story, its tests and QA need the actual states. Documents built behaviour; the D168 gate and the D198 report stay in NAV-023 |
