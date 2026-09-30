---
id: NAV-003
title: Search a place with Cyrillic/Latin input and autocomplete in the web demo (results list, fly-to, place card, reverse for coordinates)
phase: 0
priority: must
size: L
needs_design: true
needs_backend: false
needs_mobile: true
status: ready
---

# NAV-003: Search a place with Cyrillic/Latin input and autocomplete (web demo)

## Story
As a **UB commuter by car**, I want **to type a place, street, khoroo or district name into a search box on the map in Cyrillic or in Latin letters, see matching places as I type, and pick one to see it on the map**, so that **I can find where something is in Ulaanbaatar in my own language, even when I type it in Latin letters, with a typo, or on a phone without a Mongolian keyboard layout**.

Secondary personas:
- **Taxi / delivery driver**: searches landmarks and khoroo names given by customers ("БЗД 4-р хороо", «Их дэлгүүр»), often typed quickly with typos or with Russian-layout letters (у/о instead of ү/ө).
- **Tourist (English UI)**: types Latin names ("Sukhbaatar", "Gandan", "Zaisan") and reads the result types and UI in English. Map labels stay Cyrillic (PO decision D11).
- **Pedestrian**: searches a nearby place, and a result near the map centre or their own position ranks first.
- **Intercity / countryside driver**: searches towns and soum centres outside UB ("Erdenet", «Дархан») on a weak connection, and gets a clear message instead of an endless spinner when the network fails.

## Context
- Research `docs/osm-navigation-research.md` §7 Phase 0 PoC ("web demo … with search"), §2 rows 2–3 (search with autocomplete, reverse geocoding "what is here?"), §4.6 (Photon for search-as-you-type), §8 item 3 (irregular Mongolian addressing, landmark search). Team design `docs/team/agent-architecture.md` §5 lists NAV-003.
- **Client:** the NAV-002 web demo in `web/` (plain TypeScript + Vite + MapLibre GL JS). NAV-003 adds a search box, a results list, a pin and a place card to the NAV-002 map screen. The NAV-002 screen spec already reserves the left of the top bar (R1) for the NAV-003 search box (`docs/design/screens/NAV-002-web-map.md`).
- **API (no contract change expected):** `docs/architecture/api/openapi.yaml` **0.4.0**:
  - `search`: `GET /v1/search` (Photon 1.3.0 `/api` pass-through). Prefix matching; empty result = 200 with empty `features`; client rules "always send `lang`", "send `lat`/`lon` for bias", "debounce keystrokes". `limit` is capped at 20 by the server. `q` has `maxLength: 200`.
  - `reverse`: `GET /v1/reverse` (Photon `/reverse`). Nothing within `radius` = 200 with empty `features`.
  - Errors: 400 (Photon), **429 `RateLimited` with `Retry-After`** (integer seconds ≥ 1, exposed to browsers via `Access-Control-Expose-Headers`), 502/503 `UpstreamUnavailable`, 504 `UpstreamTimeout` (gateway read timeout for search/reverse 5 s, connect timeout 2 s).
  - **Rate-limit client rules (0.4.0):** do not repeat a request before `Retry-After` seconds have passed; never retry in a tight loop; **for search-as-you-type, drop the pending request and let the next keystroke (after the delay) search again**.
- **Index (ADR-0003):** GraphHopper Mongolia Photon dump, all of Mongolia, about 44k records: `name` about 39k, `name:mn` about 11.8k, `name:en` about 7.3k, `name:ru` about 1.3k. Indexed languages `mn`, `en`, `ru`. Photon does **not** transliterate between Cyrillic and Latin. A Latin query only matches places that have a Latin name (mostly `name:en`), so most Latin queries need help from the client (see section C and R2).
- **No Nominatim** in the stack (ADR-0003 / NAV-001 Open question 1): reverse geocoding uses Photon `/reverse` only.
- **Decisions already made (not open):**
  - Mongolian first: Mongolian is the default UI language, English second (glossary language policy). The English UI keeps Cyrillic map labels (D11).
  - Supported browsers: current desktop Chrome, Edge and Firefox, plus Android Chrome. Safari/iOS later. Automated tests on desktop Chromium only (D14).
  - The demo is **local dev only** until staging exists (NAV-002 Context, NAV-008).
  - NAV-003 is expected to need **no backend change** and **no `openapi.yaml` change**. Transliteration help and district-abbreviation aliases (section C) are expected to be done in `web/`. If the architect decides that a server-side change is truly needed, it is limited to `backend/` files **other than** the gateway rate-limit, `real_ip` and header configuration (owned by the parallel NAV-008 work) and is described in the handoff. The shared dev stack at `http://localhost:8080` is never stopped, restarted or rebuilt.
- **Reference points and environment:** P1–P6, X1, X2 from NAV-001 ("Reference test locations"): P1 Sükhbaatar Square (47.9189, 106.9176), P2 State Department Store (47.9139, 106.9044), P3 Zaisan (47.8858, 106.9173), P4 Gandan (47.9215, 106.8950), P5 railway station (47.9095, 106.8835), X1 Erdenet (49.0270, 104.0440), X2 Beijing (39.9042, 116.4074). Reference environment for timings = NAV-002 reference environment (NAV-001 machine, stack healthy and warm, desktop Chromium, viewport 1366×768, Vite dev server `http://localhost:5173`).
- **UX inputs this story depends on** (owner: ux-designer): a NAV-003 screen spec (search box, results list, place card, pin, states, narrow and wide layouts) and updates to the NAV-002 screen spec where the top bar changes.

### Terms used in this story
- **Settled query:** the input text after the user has stopped typing for the debounce delay, normalised as follows: Unicode NFC, leading/trailing whitespace trimmed, internal whitespace runs collapsed to one space. A settled query must have **≥ 2 characters** after normalisation to be searched.
- **Result option:** one row in the results list.
- **Type label:** the localised place-type word shown on a result option and on the place card (section D, table "Type labels").
- **Context line:** the area text shown under the name (section D, AC 17).
- **Coordinate card:** a place card for a coordinate rather than a search result (section F). Its nearest place comes from `reverse`.
- **Search bias point:** the `lat`/`lon` sent with `search` (AC 9).

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. Terms that NAV-003 needed and that were missing are **T1–T31** below. **They were added to the glossary on 2026-09-30** (section 4 for T1–T7 and new section 4.1 "Search: place type labels" for T8–T31), all with status `needs native review`. Implementation uses exactly these strings. Mobile and UX do not invent their own. If another string turns out to be needed, request it from the business-analyst first.

**Existing glossary terms used**

| Use in NAV-003 | Glossary row | `mn` | `en` |
|---|---|---|---|
| Search input accessible name, search button | Search | «Хайх» | "Search" |
| Search input placeholder | Search | «Газар, хаяг хайх» | "Search for a place or address" |
| No results | No results | «Илэрц олдсонгүй» | "No results found" |
| Loading (search pending > 300 ms, reverse pending) | Loading (NAV-002 G1) | «Ачаалж байна…» | "Loading…" |
| Offline | No connection | «Интернэт холболт алга» | "No internet connection" |
| Retry | Try again | «Дахин оролдох» | "Try again" |
| Close the place card | Close (dismiss) (NAV-002 G6) | «Хаах» | "Close" |
| Unexpected failure (for example a 400) | Generic error | «Алдаа гарлаа» | "Something went wrong" |
| Type labels reused from existing rows | Street / avenue / road; Address; Düüreg; Khoroo; Aimag; Soum; Petrol station; Place (POI) | «Зам», «Хаяг», «Дүүрэг», «Хороо», «Аймаг», «Сум», «ШТС», «Газар» | "Road", "Address", "District", "Khoroo", "Aimag", "Soum", "Petrol station", "Place" |
| Map, attribution | Map; OSM attribution | as in NAV-002 | as in NAV-002 |

**Glossary additions T1–T31 (added to `glossary.md` 2026-09-30, all `needs native review`)**

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| T1 | Clear search | «Хайлтыг арилгах» | "Clear search" | Accessible name and tooltip of the clear (×) button |
| T2 | Search results (list name) | «Хайлтын илэрц» | "Search results" | Accessible name of the results listbox |
| T3 | Results count | «{count} илэрц олдлоо» | "{count} results" (`count` = 1: "1 result") | Screen-reader announcement after results render |
| T4 | Search unavailable | «Хайлт түр ажиллахгүй байна» | "Search is temporarily unavailable" | Gateway down / 5xx / timeout / CORS failure, for `search` and `reverse` |
| T5 | Too many requests | «Түр хүлээгээд дахин оролдоно уу» | "Too many searches. Wait a moment and try again" | 429 `RateLimited` on `search` or `reverse` |
| T6 | Selected point | «Сонгосон цэг» | "Selected point" | Title and pin name of a coordinate card; coordinate option in the list |
| T7 | Nearest place | «Ойролцоох газар» | "Nearest place" | Label of the `reverse` result on a coordinate card |
| T8–T31 | Place type labels | see section D, table "Type labels" | see the same table | Result options and place card |

`{count}` is a placeholder that the client replaces with the number (no plural form in Mongolian). Resource files keep the placeholder text exactly as in the glossary.

## Acceptance criteria

### A. Search box and request rules
1. **Given** the NAV-002 map screen in either language and either theme, **When** the page loads, **Then** a search input is visible in the top bar with accessible name «Хайх» ("Search") and placeholder «Газар, хаяг хайх» ("Search for a place or address"), it is the **first** Tab stop on the page, and **no** `search` or `reverse` request is sent until the user types or acts (right-click / long-press, section F).
2. **Given** the search input, **When** the user types, **Then** the input accepts at most **200** characters (the `q` limit), and pasting a longer text keeps the first 200 characters with no error.
3. **Given** the user types a query, **When** they stop typing, **Then** the `search` request for the settled query (one request, or two when AC 15 transliteration help applies) starts **200–350 ms** after the last keystroke (target 250 ms debounce), and no request is sent for the intermediate texts. Typing «Сүхбаатар» at one character every **100 ms** and then stopping sends exactly **1** `search` request (for the full word) when the query needs no transliteration help (AC 12), and at most **2** when it does.
4. **Given** a settled query with **fewer than 2** characters (for example «С», or only spaces), **When** the debounce fires, **Then** no request is sent and the results list is closed.
5. **Given** a request is in flight, **When** the query changes (new settled query, cleared input, or result selected), **Then** the in-flight request is aborted or its response is ignored. A response for an older query **never** replaces the list for a newer query, even when the older response arrives later (QA delays the first response by 1 s with request interception).
6. **Given** a settled query identical (after normalisation) to the query whose results are already shown, **When** the debounce fires (for example the user typed a trailing space and deleted it), **Then** no new request is sent.
7. **Given** the Mongolian UI, **When** a `search` request is sent, **Then** it carries `lang=mn`. **Given** the English UI, **Then** it carries `lang=en`. It always carries `limit` between **5 and 10** (inclusive). The results list shows at most **10** options.
8. **Given** results where some features have `countrycode` other than `MN` (injected fixture), **When** the list renders, **Then** every `MN` result is listed before every non-`MN` result, and the upstream order is otherwise kept (Mongolian results first).
9. **Given** the search bias point, **When** a `search` request is sent, **Then** it carries `lat` and `lon`:
   - (a) the **device position**, when the user has activated my location (NAV-002 AC 19), the camera is following, and the last fix is **≤ 60 s** old;
   - (b) otherwise the **current map centre**.
   Both values are **rounded to 3 decimal places** (about 100 m, privacy, R10). NAV-003 **never** calls the Geolocation API itself: if the user has not activated my location, the map centre is used and the browser shows no permission prompt (NAV-002 AC 18 stays true).
10. **Given** the same query «Их дэлгүүр» sent once with the map centred on P2 and once centred on X1 (Erdenet), **When** results are compared, **Then** with the P2 bias a result within **300 m** of P2 is in the top **3**. (With the X1 bias the order may differ; this is recorded, not asserted.)
11. **Given** any NAV-003 session, **When** all network requests are logged, **Then** every request goes to the page origin or the gateway base URL (NAV-002 AC 46 still holds). No request goes to a public Photon, Nominatim or other geocoder host.

### B. Autocomplete results and timing
12. **Given** the stack is healthy, **When** each of these settled queries is typed with the map centred on P1: «Сүхбаатар», «Сүхб», «Гандан», «Зайсан», «Их дэлгүүр», «Энхтайвны өргөн чөлөө» (20 samples in total, repeated as needed, at most 2 search requests per second), **Then** for **≥ 19 of 20** samples the results list (or «Илэрц олдсонгүй») is rendered within **1,000 ms** of the last keystroke.
13. **Given** a pending request that takes longer than **300 ms**, **When** it is still pending, **Then** the list area shows «Ачаалж байна…» and has `aria-busy="true"`. When results arrive, the indicator is gone within **200 ms**.
14. **Given** the golden query set in the section "Golden query set", **When** QA runs it against the live gateway with the stated bias, **Then**:
    - every **tier A** row passes;
    - at least **80 %** of the **tier B** rows whose Cyrillic control row passes also pass (a tier B row whose control fails is a data gap and is recorded, not counted);
    - **tier C** rows are recorded as a data-quality baseline and never fail the story.
    "Pass" means the expected place appears within the stated top-N options with the stated distance or name condition.

### C. Cyrillic, Latin, typos and abbreviations
15. **Given** a settled query written only in Latin letters, digits, spaces and punctuation (for example "Sukhbaatar", "suhbaatar", "Sükhbaatar", "Ikh delguur"), **When** it is searched, **Then** the client sends the query as typed **and**, if needed, **at most one** additional request with a Cyrillic transliteration of it (common conventions: kh/h → х, ts → ц, ch → ч, sh → ш, j → ж, ya → я, yu → ю, yo → ё, ö → ө, ü → ү, and u/o ambiguous between у/ү and о/ө). The two result sets are merged into one list with no duplicate (same `osm_type` + `osm_id`), and the golden-set tier B targets for Latin rows are met (AC 14). The algorithm is the architect's and mobile-engineer's choice. **At most 2** `search` requests are sent per settled query.
16. **Given** a settled query that contains one of the district abbreviations «СБД», «БЗД», «ХУД», «БГД», «ЧД», «СХД» as a separate word (any letter case), **When** it is searched, **Then** the abbreviation is expanded to the full district name before sending (СБД → «Сүхбаатар дүүрэг», БЗД → «Баянзүрх дүүрэг», ХУД → «Хан-Уул дүүрэг», БГД → «Баянгол дүүрэг», ЧД → «Чингэлтэй дүүрэг», СХД → «Сонгинохайрхан дүүрэг»; glossary "Düüreg" row), and the rest of the query is kept (for example «БЗД 4-р хороо» → «Баянзүрх дүүрэг 4-р хороо»). The input still shows what the user typed. Golden rows A9–A11 pass.

### D. Results list content
17. **Given** a result feature (fixture set F1–F12 injected by request interception), **When** its option is rendered, **Then** it shows three parts:
    - **Name**: `name`; if absent, `street` + " " + `housenumber`; if both are absent, the type label.
    - **Type label**: from the table "Type labels" below, localised to the UI language.
    - **Context line**: the first **2** distinct, non-empty values of `district`, `locality`, `city`, `county`, `state` (in that order), skipping any value equal to the name, joined with ", ". If none remain, the context line is omitted (no empty row, no "undefined").
18. **Given** a property value that contains traditional Mongolian script (characters U+1800–U+18AF, for example "Монгол улс ᠮᠤᠩᠭᠤᠯ ᠤᠯᠤᠰ"), **When** it is displayed anywhere in NAV-003, **Then** the traditional-script part and the whitespace around it are removed ("Монгол улс"). If nothing is left, the next field in the AC 17 order is used. No box glyphs ("tofu") appear. *(openapi PhotonProperties: "NAV-003 UX decides"; this is the BA default, UX may change it in the screen spec.)*
19. **Given** each fixture feature F1–F12, **When** rendered, **Then** its type label is exactly the one in the table "Type labels" (rules are applied top to bottom and the first match wins). A feature matching no rule shows «Газар» / "Place".

**Type labels** (T8–T31 are new glossary rows; the others are existing rows)

| Order | Rule (Photon properties) | `mn` | `en` | Glossary |
|---|---|---|---|---|
| 1 | `name` ends with « дүүрэг» | «Дүүрэг» | "District" | existing "Düüreg" |
| 2 | `name` ends with « хороо» | «Хороо» | "Khoroo" | existing "Khoroo" |
| 3 | `name` ends with « аймаг» | «Аймаг» | "Aimag" | existing "Aimag" |
| 4 | `name` ends with « сум» and `osm_key` = `boundary` or `place` | «Сум» | "Soum" | existing "Soum" |
| 5 | `osm_key`=`place`, `osm_value` in `city`, `town` | «Хот» | "City or town" | T8 |
| 6 | `osm_key`=`place`, `osm_value` in `village`, `hamlet`, `isolated_dwelling`, `locality` | «Суурин» | "Settlement" | T9 |
| 7 | `osm_key`=`place`, `osm_value` in `suburb`, `neighbourhood`, `quarter` | «Хороолол» | "Neighbourhood" | T10 |
| 8 | `osm_key`=`place`, `osm_value`=`square` | «Талбай» | "Square" | T11 |
| 9 | `amenity` = `fuel` | «ШТС» | "Petrol station" | existing "Petrol station" |
| 10 | `amenity` in `hospital`, `clinic`, `doctors` | «Эмнэлэг» | "Hospital or clinic" | T12 |
| 11 | `amenity` = `pharmacy` | «Эмийн сан» | "Pharmacy" | T13 |
| 12 | `amenity` = `school` | «Сургууль» | "School" | T14 |
| 13 | `amenity` in `university`, `college` | «Их сургууль» | "University or college" | T15 |
| 14 | `amenity` in `restaurant`, `cafe`, `fast_food` | «Хоолны газар» | "Restaurant or café" | T16 |
| 15 | `tourism` in `hotel`, `hostel`, `guest_house`, `motel` | «Зочид буудал» | "Hotel" | T17 |
| 16 | `shop` in `mall`, `department_store` | «Худалдааны төв» | "Shopping centre" | T19 |
| 17 | `shop` = any other value | «Дэлгүүр» | "Shop" | T18 |
| 18 | `amenity` = `marketplace` | «Зах» | "Market" | T20 |
| 19 | `amenity` in `bank`, `atm` | «Банк» | "Bank or ATM" | T21 |
| 20 | `highway` = `bus_stop`, or `public_transport` = `platform` / `stop_position` | «Автобусны буудал» | "Bus stop" | T22 |
| 21 | `railway` in `station`, `halt` | «Галт тэрэгний буудал» | "Railway station" | T23 |
| 22 | `aeroway` in `aerodrome`, `terminal` | «Нисэх онгоцны буудал» | "Airport" | T24 |
| 23 | `amenity` = `parking` | «Зогсоол» | "Parking" | T25 |
| 24 | `tourism` = `museum` | «Музей» | "Museum" | T26 |
| 25 | `historic` = any value, or `tourism` in `attraction`, `viewpoint` | «Дурсгалт газар» | "Monument or historic site" | T27 |
| 26 | `amenity` = `place_of_worship` | «Сүм хийд» | "Place of worship" | T28 |
| 27 | `leisure` in `park`, `garden` | «Цэцэрлэгт хүрээлэн» | "Park" | T29 |
| 28 | `office` = `government`, or `amenity` = `townhall` | «Төрийн байгууллага» | "Government office" | T30 |
| 29 | `office` = `diplomatic`, or `amenity` = `embassy` | «Элчин сайдын яам» | "Embassy" | T31 |
| 30 | `osm_key` = `highway` (any other value) | «Зам» | "Road" | existing "Street / avenue / road" |
| 31 | `housenumber` present and no rule above matched | «Хаяг» | "Address" | existing "Address" |
| 32 | none of the above | «Газар» | "Place" | existing "Place (POI)" |

"`amenity` = x" means `osm_key`=`amenity` and `osm_value`=x (likewise for the other keys). UX may add rows only after the BA has added the Mongolian term to the glossary.

### E. Selecting a result: fly-to, pin and place card
20. **Given** a results list, **When** the user clicks or taps an option, or highlights it with the arrow keys and presses Enter, **Then** within **2 s** the camera has moved to the result:
    - features with `extent`: the extent fits the viewport with at least **40 px** padding on every side, at zoom **≤ 17**;
    - features without `extent`: centred on the point (±5 px) at zoom **13** for type labels «Хот», «Суурин», «Дүүрэг», «Хороо», «Хороолол», «Аймаг», «Сум», and zoom **16** for all others.
    With `prefers-reduced-motion: reduce`, the camera jumps without animation, within **500 ms**.
21. **Given** a result was selected, **When** the camera arrives, **Then** one pin is shown at the feature point (±2 px) with the result name as its accessible name, and a place card shows the name (as a heading), type label, context line, and the coordinates as «lat, lon» with **5** decimals and a point as the decimal separator in both languages (for example «47.91881, 106.91690»). The list closes, and the input shows the selected name.
22. **Given** a pin and card are shown, **When** the user selects another result, **Then** the previous pin and card are replaced (never two pins). **When** the user activates «Хаах» or presses Escape with focus in the card, **Then** the pin and card disappear and focus returns to the search input. The query text stays.
23. **Given** a card is open, **When** inspected at viewport widths **320, 360, 768, 1366 and 1920 px**, both themes and both languages, **Then** the card, the pin, the search box and the results list do not cover «© OpenStreetMap contributors» (or the ESA credit when shown), the scale bar or any NAV-002 control, and every NAV-003 touch target is at least **44×44 CSS px** (NAV-002 AC 34, 49 stay true).
24. **Given** a result was selected, **When** the user pans or zooms the map, **Then** the pin stays at its geographic position and the card stays open until closed or replaced.

### F. Coordinate card (reverse geocoding)
25. **Given** the map, **When** the user right-clicks (desktop) or long-presses **≥ 600 ms** without moving more than **10 px** (touch) on a point, **Then** within **500 ms** a pin is placed at that point with accessible name «Сонгосон цэг», and a coordinate card opens with the heading «Сонгосон цэг» and the coordinates (format as AC 21). The camera does not move. A normal click, drag or pinch does **not** open a card.
26. **Given** the search input, **When** the settled query is a coordinate pair in the form `<lat>, <lon>`, `<lat>,<lon>` or `<lat> <lon>` with point decimals, lat in −90…90 and lon in −180…180 (for example "47.9189, 106.9176"), **Then** **no** `search` request is sent and the list shows one option, «Сонгосон цэг» with the normalised coordinates. Selecting it centres the map on the point (zoom 16, or the current zoom if higher) and opens the coordinate card as in AC 25. A pair that is out of range (for example "106.9176, 47.9189") is searched as normal text.
27. **Given** a coordinate card opened (AC 25 or 26), **When** it opens, **Then** exactly **one** `reverse` request is sent with the point's `lat`/`lon` (at least 5 decimals, not rounded to 3: the user chose this point), `lang` = UI language, `limit=1` and `radius=0.5`. While it is pending (> 300 ms) the card shows «Ачаалж байна…» in the nearest-place area.
28. **Given** the `reverse` response has a feature, **When** it arrives, **Then** the card shows the label «Ойролцоох газар» followed by that feature's name, type label and context line (rules of AC 17–19). The pin and heading stay at the chosen point; the camera does not move. At P1 the nearest place is **≤ 300 m** from P1 (NAV-001 AC 25).
29. **Given** the `reverse` response is empty (for example at X2 Beijing, or an empty steppe point), **When** it arrives, **Then** the nearest-place area shows «Илэрц олдсонгүй». The heading, coordinates and pin stay. No error state is shown.
30. **Given** `reverse` fails (see section G for 429, 5xx, timeout, offline), **When** the card is open, **Then** the card keeps the heading, coordinates and pin, and the nearest-place area shows the matching state message from section G. «Дахин оролдох» re-sends the `reverse` request once (subject to AC 36).

### G. States: empty, no results, gateway down, offline, rate-limited, bad request
31. **Given** the input is empty or cleared (via the clear button «Хайлтыг арилгах», or by deleting all text), **When** it is empty, **Then** the list is closed, any in-flight request is aborted, no request is sent, and no message is shown. The clear button is visible only when the input has text and moves focus back to the input. An open place card is **not** closed by clearing.
32. **Given** a settled query with **200** and an empty `features` array (for example "xqzjwvk", NAV-001 AC 23), **When** the list renders, **Then** it shows «Илэрц олдсонгүй» (not an error), announced via `aria-live="polite"`.
33. **Given** `search` fails with a network error, a CORS failure, **502**, **503**, **504**, or no response within **8 s** (client timeout; the gateway's own timeouts are 2 s connect and 5 s read), while `navigator.onLine` is true, **When** the failure is known, **Then** within **8 s** of the request start the list area shows «Хайлт түр ажиллахгүй байна» with «Дахин оролдох». There is no endless spinner. «Дахин оролдох» re-sends the current settled query **once**. A new keystroke also searches again after the debounce. Nothing retries automatically.
34. **Given** the browser is offline (`navigator.onLine` false or the `offline` event, for example Playwright `setOffline(true)`), **When** the user types, **Then** **no** request is sent and the list area shows «Интернэт холболт алга» within **500 ms** of the settled query. The NAV-002 offline banner behaves as in NAV-002 AC 43. **When** the browser comes back online and the input still holds a settled query of ≥ 2 characters, **Then** that query is searched **once** within **2 s**, with no further user action.
35. **Given** `search` returns **429** with `Retry-After: N`, **When** the response arrives, **Then**:
    - the response is dropped, and the list area shows «Түр хүлээгээд дахин оролдоно уу» with «Дахин оролдох»;
    - **no** `search` request is sent for the next **N** seconds, whatever the user types (the input still accepts text);
    - «Дахин оролдох» is shown disabled (`aria-disabled="true"`) until the N seconds have passed;
    - after N seconds, **nothing is sent automatically**. The next settled query (next keystroke after the debounce) or a press on «Дахин оролдох» sends one request.
    QA checks with `Retry-After: 3`: zero requests in the 3 s window while typing, one request after the first keystroke that settles after the window. If `Retry-After` is missing or not a positive integer, the client waits **5 s**.
36. **Given** `reverse` returns **429** with `Retry-After: N`, **When** the card is open, **Then** the nearest-place area shows «Түр хүлээгээд дахин оролдоно уу», «Дахин оролдох» is disabled for N seconds, and no `reverse` request is sent during that time. After N seconds nothing is sent until the user presses «Дахин оролдох» or opens another coordinate card.
37. **Given** `search` or `reverse` returns **400** (for example an unsupported `lang`, injected), **When** it arrives, **Then** the area shows «Алдаа гарлаа», and the client does not retry the same request.
38. **Given** a state message in the list or card, **When** the UI language is switched, **Then** the message switches language within **500 ms** (NAV-002 AC 31). An open results list is re-requested **once** with the new `lang`; an open card keeps the place name it has and switches its labels.
39. **Given** more than one NAV-002 or NAV-003 state applies, **When** messages are shown, **Then** NAV-003 messages appear only inside the results list or the place card, never cover the attribution, and the NAV-002 precedence (offline > tiles unavailable > loading) is unchanged. Search still works while the tiles-unavailable state is shown, and tiles still load while search is unavailable.

### H. Keyboard and screen reader
40. **Given** the search input implements the WAI-ARIA 1.2 combobox pattern (`role="combobox"`, `aria-expanded`, `aria-controls` pointing at a `role="listbox"` named «Хайлтын илэрц», `aria-activedescendant` for the highlighted option), **When** checked with axe-core in both themes and languages with the list open, the card open, and each state message shown, **Then** there are **0** violations of serious or critical impact, and all NAV-003 text meets WCAG 2.1 AA contrast (≥ 4.5:1).
41. **Given** the list is open with results, **When** the user presses ArrowDown / ArrowUp, **Then** the highlight moves one option (wrapping from last to first and back) and focus stays in the input. **Enter** selects the highlighted option. **Enter** with no highlighted option selects the **first** option of the list for the current settled query; if that list is not rendered yet, the query is searched at once (no debounce) and its first option is selected when it arrives, unless the result is empty or a state message. **Escape** closes the list; a second **Escape** clears the input. **Tab** leaves the input and closes the list without selecting.
42. **Given** results render, **When** a screen reader is running (checked through the live region's text), **Then** within **1 s** the live region (`aria-live="polite"`) announces «{count} илэрц олдлоо» with the number (for example «5 илэрц олдлоо»; English "5 results", "1 result"), or «Илэрц олдсонгүй», or the state message. It announces once per settled query, not on every keystroke.
43. **Given** a result or coordinate card was selected, **When** the card opens, **Then** focus moves to the card heading (the name or «Сонгосон цэг»), and every card control («Хаах», «Дахин оролдох» when shown) is reachable with Tab and has a visible focus indicator.

### I. Strings and localisation
44. **Given** the `web/` resource files after NAV-003, **When** the NAV-002 checks run (AC 32 hard-coded text scan and identical key sets; AC 33 `npm run check:glossary`), **Then** both pass, including every new NAV-003 key. Every `mn` value is a glossary term from the table "User-facing strings" or "Type labels", with `{count}` kept literally in the resource value.
45. **Given** the type labels and messages, **When** the UI is English, **Then** every NAV-003 label and message is the `en` value from the tables above, and result names come from the `lang=en` response (English name where OSM has one, otherwise Mongolian, per Photon fallback). Map labels stay Cyrillic (D11).

### J. Privacy
46. **Given** the user has activated my location, **When** all `search` requests are logged, **Then** the bias `lat`/`lon` have at most **3** decimal places, and no request other than `search` (bias) and `reverse` (user-chosen point) carries coordinates. Neither the query text nor coordinates are written to `localStorage`, `sessionStorage`, cookies or the console. *(Recent searches are out of scope.)*

## Golden query set
QA stores it as a fixture under `tests/e2e/nav003/` and runs it against the live gateway at **≤ 2 search requests per second** (at most about 80 requests per run). Bias = P1 unless stated. "Top N" = the expected place is among the first N options. Distances are from the option's point.

**Tier A (every row must pass, AC 14)**

| # | Query (as typed) | UI | Expected |
|---|---|---|---|
| A1 | «Сүхбаатарын талбай» | mn | an option ≤ **500 m** from P1 in top **1** |
| A2 | «Сүхбаатар» | mn | an option ≤ **1 km** from P1 in top **3** |
| A3 | «Сүхб» | mn | as A2, top **5** |
| A4 | "Sukhbaatar" | en | an option ≤ **1 km** from P1 in top **3** |
| A5 | "sukhbaatar square" | mn | an option ≤ **500 m** from P1 in top **3** |
| A6 | «Гандан» | mn | an option ≤ **500 m** from P4 in top **3** |
| A7 | «Зайсан» | mn | an option ≤ **1 km** from P3 in top **3** |
| A8 | «Их дэлгүүр» (bias P2) | mn | an option ≤ **300 m** from P2 in top **3** |
| A9 | «БЗД» | mn | an option whose name contains «Баянзүрх» with type label «Дүүрэг» in top **3** |
| A10 | «ЧД» | mn | an option whose name contains «Чингэлтэй» with type label «Дүүрэг» in top **3** |
| A11 | «худ» (lower case) | mn | an option whose name contains «Хан-Уул» with type label «Дүүрэг» in top **3** |
| A12 | «Баянзүрх дүүрэг» | mn | as A9 |
| A13 | «Энхтайвны өргөн чөлөө» | mn | an option with type label «Зам» ≤ **2 km** from P1 in top **3** |
| A14 | «Эрдэнэт» (bias X1) | mn | an option ≤ **5 km** from X1 in top **3** |
| A15 | "47.9189, 106.9176" | mn | one option «Сонгосон цэг», no `search` request (AC 26) |
| A16 | "xqzjwvk" | mn | «Илэрц олдсонгүй» (AC 32) |

**Tier B (≥ 80 % of rows whose control passes, AC 14)**. Control = the Cyrillic row it depends on.

| # | Query (as typed) | UI | Control | Expected |
|---|---|---|---|---|
| B1 | "suhbaatar" | mn | A2 | as A2, top **5** |
| B2 | "Sükhbaatar" | mn | A2 | as A2, top **5** |
| B3 | «Сухбаатар» (у instead of ү, Russian layout) | mn | A2 | as A2, top **5** |
| B4 | «Сүхбатар» (letter missing) | mn | A2 | as A2, top **5** |
| B5 | "Gandan" | en | A6 | as A6, top **5** |
| B6 | "Zaisan" | mn | A7 | as A7, top **5** |
| B7 | "Zaysan" | mn | A7 | as A7, top **5** |
| B8 | "Ikh delguur" (bias P2) | mn | A8 | as A8, top **5** |
| B9 | "Bayanzurkh" | mn | A12 | as A9, top **5** |
| B10 | "Chingeltei" | en | A10 | as A10, top **5** |
| B11 | "Enkhtaivan" | mn | A13 | as A13, top **5** |
| B12 | "Erdenet" (bias X1) | en | A14 | as A14, top **3** |
| B13 | «Галт тэрэгний буудал» (bias P5) | mn | — (no control; counted) | an option ≤ **500 m** from P5 in top **5** |
| B14 | «Сүхбаатар дүүргийн 1-р хороо» | mn | — (counted) | an option with type label «Хороо» in top **5** |
| B15 | «БЗД 4-р хороо» | mn | — (counted) | an option with type label «Хороо» whose context line or name contains «Баянзүрх» in top **5** |

**Tier C (baseline only, never fails)**
- C1 «Дархан» / "Darkhan": nearest option to (49.4867, 105.9228) and its rank.
- C2 «1-р хороо» with bias P1: number of «Хороо» options and their context lines (shows how many khoroos share a name).
- C3 A ger-district query near P6 (for example «Чингэлтэй 13-р хороо», bias P6): rank and distance.
- C4 For A2 and A6: the context line text shown, to record whether the düüreg appears (R1).
- C5 Share of tier A/B results whose name is Latin in the Mongolian UI (R2).

## Edge cases
- **Cyrillic vs Latin transliteration:** AC 15, tier B rows B1–B12. There is no single standard ("Sukhbaatar", "Suhbaatar", "Sükhbaatar"; glossary "Transliteration").
- **Russian keyboard layout:** users type у/о instead of ү/ө (B3). The client must not "correct" Cyrillic input in a way that breaks correct ү/ө queries (A1–A14 still pass).
- **Typos:** B4, B7. Depends on Photon fuzzy matching (R8).
- **District abbreviations:** AC 16, A9–A11, B15. Abbreviations are never shown or spoken in results; they are an input convenience only.
- **Khoroo names repeat across districts** («1-р хороо» exists in every düüreg): the context line (AC 17) is how users tell them apart. Bias (AC 9) ranks the nearby one first. C2 records it.
- **Landmark-based descriptions** ("near State Department Store") are not parsed. The user searches the landmark itself (A8).
- **No results:** AC 32. **Gateway down / data rebuild** (Photon import takes about 1 minute, the gateway may return 502): AC 33. **Offline:** AC 34. **Rate-limited (429):** AC 35–36 (staging only, where limits are enabled; local dev normally never returns 429, so tests inject it).
- **GPS / location lost:** when the fix is older than 60 s or lost (NAV-002 AC 23 stale state), the bias falls back to the map centre (AC 9 b). Search never fails because location is unavailable.
- **Location permission denied:** bias = map centre. No prompt is triggered by search (AC 9).
- **Weak signal (countryside):** slow responses show «Ачаалж байна…» after 300 ms (AC 13) and the unavailable state after 8 s (AC 33). No endless spinner.
- **Off-route:** not applicable (no routing in NAV-003; "Get directions" «Маршрут гаргах» comes with NAV-004).
- **Out-of-coverage points:** right-click at X2 gives «Илэрц олдсонгүй» in the card (AC 29). Search results outside Mongolia are not expected (Mongolia-only index); if any appear, they are listed after `MN` results (AC 8).
- **Unpaved roads and ger districts:** many ger-district streets are unnamed or have no `addr:*`, so they cannot be found by name (R5). Reverse near P6 may return a khoroo or a distant POI; the card labels it «Ойролцоох газар», never as the address of the point.
- **Winter conditions:** no search-specific behaviour. Touch targets ≥ 44×44 px (AC 23) help users in gloves. Winter-only roads (for example ice roads) are not searchable unless named in OSM.
- **Coordinate input with a decimal comma** ("47,9189, 106,9176") is ambiguous and is searched as text (AC 26 accepts point decimals only). **Swapped lon/lat** out of range is searched as text.
- **Traditional Mongolian script in names:** AC 18.
- **Very long queries or pasted paragraphs:** AC 2.
- **Fast typing and out-of-order responses:** AC 3, 5.
- **Language switch while the list or card is open:** AC 38.
- **Index date differs from tiles** (weekly Photon dump vs the tiles build, ADR-0003): a selected place may sit where the map has not drawn it yet, or vice versa (R6). Not a client bug.

## Data dependencies & risks
| # | Risk | Impact on NAV-003 | Mitigation / owner |
|---|---|---|---|
| R1 | **Photon address fields may not carry the UB düüreg.** The openapi example gives `district` = «Бага Тойрог» (a neighbourhood) for Sükhbaatar Square, not «Сүхбаатар дүүрэг» | The context line may show a neighbourhood and the city instead of the düüreg, which weakens khoroo disambiguation | AC 17 defines a data-driven rule; C4 records what appears. Architect: check which property carries the düüreg in the ADR-0003 dump and propose a rule change if a better field exists |
| R2 | **`name:en` coverage is low** (about 7.3k of 44k records) and Photon does not transliterate | Latin queries find only places with an English name unless the client helps | AC 15 (transliteration request), tier B. Mapping programme for `name:en` later |
| R3 | **No transliteration standard; у/ү and о/ө ambiguity** in both directions | A single transliteration may miss the right vowel | Tier B threshold 80 %, not 100 %. If the client cannot reach it without an index change (vowel folding in Photon), the architect raises it as a follow-up; no backend change in NAV-003 without the coordination in Context |
| R4 | **Khoroo boundaries and names in OSM are irregular** (missing, «1-р хороо» vs «Хороо 1», repeated across districts) | Khoroo search (B14, B15, C2) may fail even with a correct client | Tier B counts them; tier C baseline; OSM mapping tasks via triage lane `osm-data` (human mappers only) |
| R5 | **Ger-district addressing is largely absent** (`addr:*`, plot numbers) | Addresses there cannot be found; reverse returns an area or distant POI | Out of scope to fix. The card wording «Ойролцоох газар» avoids claiming an address |
| R6 | **Index date differs from tiles and routing** (weekly GraphHopper dump, ADR-0003) | A result may not be drawn on the map yet | Accept for Phase 0; NAV-006 daily rebuild |
| R7 | **Traditional Mongolian script in OSM values** | Tofu or broken text | AC 18 |
| R8 | **Photon typo tolerance is limited** (short words, more than one edit) | B4, B7 may fail | Tier B threshold; recorded |
| R9 | **Rate limits on shared staging** (many phones behind one carrier NAT, NAV-008) | 429 during normal typing if limits are tight | AC 3 (debounce), AC 15 (≤ 2 requests per settled query), AC 35. Local dev only for now |
| R10 | **Bias coordinates from the device are personal data** (Mongolian personal-data law, D9) | Sending precise positions to a host abroad (staging, Singapore) needs the legal review | AC 9 and 46 round to 3 decimals. Local dev only now; NAV-008 AC 24 / D9 apply before outside testers use search on staging |
| R11 | **District abbreviation list and type-label rules live in the client** | Mobile apps (NAV-005) must duplicate them, and they may drift | Architect decides whether to move them server-side later (follow-up, not NAV-003) |
| R12 | **Type-label rules cover only common tags** | Many POIs show «Газар» | Fallback rule 32; UX can request more rows via the BA |

## Out of scope
- Routing, "Get directions" «Маршрут гаргах», route preview (NAV-004). Active navigation (NAV-005).
- Recent searches «Сүүлд хайсан», saved places (Home/Work), category chips ("Nearby" «Ойролцоох» searches).
- Structured address search and Nominatim (not in the stack, ADR-0003).
- Any change to `openapi.yaml`, the gateway rate-limit / `real_ip` / header configuration, or the shared dev stack.
- Showing the Cyrillic name as a second line in the English UI (Open question 2), English/transliterated map labels (D11).
- Search on mobile native apps (Android/iOS): later stories reuse these AC.
- Offline search, search history sync, analytics.
- Safari/iOS (D14).

## Carried NAV-002 follow-ups (bundled into this delivery; they trace to NAV-002, not to NAV-003 AC)
These are small NAV-002 fixes that the orchestrator folded into the NAV-003 run. They change **no** NAV-002 AC and add no NAV-003 AC. Owners and checks:
1. **NAV-002 AC 37 loading-pill flake** (owner: mobile-engineer, `web/`): the pill is painted 1–2 frames after first paint (348–374 ms against the 350 ms test limit, about 40 % failures). Make it reliably visible by 350 ms, for example present in the initial HTML/CSS with a CSS `animation-delay` instead of a timer, while still hiding it within 500 ms of the first `idle` (so a fast map does not flash it). If this is impossible on the Vite dev server, report it in the handoff. **The test limit is not loosened**, and NAV-002 AC 37 is unchanged.
2. **NAV-002 screen spec** (owner: ux-designer, `docs/design/screens/NAV-002-web-map.md` around line 123): remove "BA to add" for G1–G7. They are in the glossary since 2026-09-29 (§7, §8).
3. **Map style spec** (owner: ux-designer, `docs/design/map-style.md` around line 20): state that the default archive max zoom is exactly **14** (PO decision D1), and that clients read `maxzoom` from the PMTiles header and never hard-code it (consistent with NAV-002 AC 9 overzoom).

## Test approach (for QA, binding)
- Tests live under `tests/e2e/nav003/`. NAV-001 and NAV-008 tests are not changed. NAV-002 tests change only if an assertion depends on the exact Tab order or top-bar layout that NAV-003 changes (for example NAV-002 AC 48 order); record any such change in the QA handoff.
- Run live-gateway tests only when `http://localhost:8080/health` returns 200. If not, wait and retry. Never stop, restart or rebuild the stack, and never run docker, docker compose or make in `backend/`.
- **Keep request rates modest:** at most **2** `search`/`reverse` requests per second from the whole test run against the live gateway, and serial execution for live search tests, so that shared rate limits (if enabled) are not tripped.
- Simulate 429 (with `Retry-After`), 400, 502/503/504, timeouts, delayed and out-of-order responses, and fixture features F1–F12 with Playwright request interception. Offline with `context.setOffline`. Location with Playwright geolocation permissions and mocked positions. Reduced motion with `emulateMedia({ reducedMotion: 'reduce' })`.
- Fixture set F1–F12 (QA defines the JSON): must cover rules 1, 2, 5, 7, 8, 9, 14, 17, 20, 30, 31 and 32 of the table "Type labels", one feature with traditional Mongolian script in `country` or `state` (AC 18), one without `name` but with `street`+`housenumber`, one with a non-`MN` `countrycode` (AC 8), and one whose `district` equals its `name` (AC 17).

## Open questions
None is blocking design. The AC follow the BA recommendation for each; the PO can change them through a change request.
1. **Glossary pre-review of T1–T31.** All 31 new Mongolian rows are `needs native review` (BA proposals, like NAV-002 G1–G6). Options: (a) implement with them now and let the NAV-007 panel review them later; (b) PO pre-reviews them first (about 10 minutes). *Recommendation: (a), with an optional PO pre-review in parallel. Wording changes later only touch resource files and test expectations.*
2. **English UI: show the Cyrillic name too?** With `lang=en` a tourist sees "Sukhbaatar Square" in the list while the map label says «Сүхбаатарын талбай» (D11). Options: (a) English name only (current AC 45); (b) add the Cyrillic `name` as a second line when it differs, which needs a second request or a server change. *Recommendation: (a) now; (b) in a later tourist story together with two-line map labels.*
3. **Coordinate card triggers.** The request says a coordinate-only card uses `reverse` but not how it opens. The AC use (a) right-click / long-press on the map (AC 25) and (b) typing coordinates into the search box (AC 26, the keyboard-accessible path). Options: both (current AC), only (a), only (b). *Recommendation: both; (b) is needed for keyboard users.*
4. **Device position as bias.** The request asks for bias to "the map centre or the user's location". AC 9 uses the device position only when the user activated my location and the camera is following, rounded to 3 decimals. This is personal data once search runs on the Singapore staging host (D9, NAV-008 AC 24). Options: (a) as in AC 9; (b) always the map centre (no device data sent). *Recommendation: (a) for local dev; revisit with the NAV-008 legal review before outside testers.*

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1–11 | NAV-003 screen spec (TBD), NAV-002 screen spec R1 top bar | `search`, `preflightSearch` | `web/src/**` (TBD) | `tests/e2e/nav003/` (TBD) | openapi 0.4.0 client rules |
| AC12–14 | NAV-003 screen spec, states (TBD) | `search` | TBD | TBD, golden set fixture | ADR-0003 index |
| AC15–16 | — | `search` | TBD | TBD | R2, R3, R11 |
| AC17–19 | NAV-003 screen spec, result option (TBD) | `search` (PhotonProperties) | TBD | TBD, fixtures F1–F12 | Glossary T8–T31 |
| AC20–24 | NAV-003 screen spec, pin and place card (TBD) | — | TBD | TBD | |
| AC25–30 | NAV-003 screen spec, coordinate card (TBD) | `reverse`, `preflightReverse` | TBD | TBD | Glossary T6–T7 |
| AC31–39 | NAV-003 screen spec, states (TBD) | `search`, `reverse` (200 empty, 400, 429 + `Retry-After`, 502/503/504) | TBD | TBD | NAV-008 AC 13 (429) |
| AC40–43 | NAV-003 screen spec, a11y (TBD) | — | TBD | TBD | |
| AC44–45 | — | `search` (`lang`) | `web/src/i18n/mn.json`, `en.json` | NAV-002 `check:glossary`, `check:i18n` | Glossary T1–T31 |
| AC46 | — | `search`, `reverse` | TBD | TBD | D9, R10 |
| Carried NAV-002 follow-ups 1–3 | `docs/design/screens/NAV-002-web-map.md`, `docs/design/map-style.md` | `getBasemapPmtiles` | `web/` (loading pill) | `tests/e2e/nav002/` AC 37 (unchanged limit) | NAV-002 AC 37, D1 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-30 | NAV-003 feature request (orchestrator, feature-delivery) | Created from the backlog draft row (team design §5) and the feature request. 46 AC in sections A–J (request rules and bias, autocomplete timing, Cyrillic/Latin/typo/abbreviation handling, results content and type labels, fly-to + pin + place card, coordinate card via `reverse`, states incl. 429 `Retry-After` per openapi 0.4.0, keyboard and screen reader, strings, privacy), a three-tier golden query set, 12 data risks, the carried NAV-002 follow-ups, and the QA test approach. Glossary rows T1–T31 added (`needs native review`). Size estimated **L** (backlog row said M). `needs_backend` false (no backend change expected). Four non-blocking open questions. | Refine NAV-003 to `ready` for UX and architect. Runs in parallel with NAV-008 staging work, hence the coordination limits in Context and Test approach. Backlog row not edited in this run (a parallel BA task is editing the requirements files); the orchestrator updates it afterwards. |
