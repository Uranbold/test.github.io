# Flow NAV-011: Android route preview and search parity (assisted search, reverse on the card, alternatives, «Дугуй», typing lock)

- **Story:** [NAV-011](../../requirements/stories/NAV-011-android-route-preview-search-parity.md) (Phase 1 beta, priority must, D106). AC referenced per step.
- **Screens:** [`screens/NAV-011-android-route-preview-search-parity.md`](../screens/NAV-011-android-route-preview-search-parity.md). It is a **delta** on [`screens/NAV-005-android-navigation.md`](../screens/NAV-005-android-navigation.md) (S1 map, S2 search and coordinate card, S3 route preview, S5 guidance). Everything not named here stays as NAV-005.
- **Base flows:** [`flows/NAV-005-android-navigation.md`](NAV-005-android-navigation.md) F2a, F2b and F4 are **replaced on Android** by F1, F2 and F3 below. F3 (location access) and F5–F11 of NAV-005 are unchanged.
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) §4.2, §4.4 and §8 (the new «Дугуй» column, AC 26). Map layers: [`map-style.md` §7.6](../map-style.md) (Android alternatives).
- **Prototype:** [`prototypes/NAV-011-android-preview.html`](../prototypes/NAV-011-android-preview.html).
- **API:** `openapi.yaml` 0.5.2 `search`, `reverse`, `postRoute` (`alternates` 0–2, `costing` `bicycle`). No contract change for the design. Query assistance (A) is the same on screen for either ADR option (Kotlin port or server-side).

Copy is quoted by its `mn` value in «»; every string is a glossary term (K1–K3 are `needs native review`); keys are in the screen spec › Copy.

## F1. Assisted search (AC 1–7)

Nothing new is visible. The assistance happens behind the field (Tesler's law: the system absorbs the script and keyboard-layout problem). The field always shows what the user typed (AC 4). There is no "Did you mean" line and no "Showing results for" line.

```mermaid
flowchart TD
    T["User types in «Газар, хаяг хайх»<br/>(typing lock released or overridden, F4)"] --> D{"Online?"}
    D -- no --> OFF["Row «Интернэт холболт алга»<br/>0 requests — AC 7"]
    D -- yes --> DB["Debounce 250 ms → settled query"]
    DB --> K{"Query kind<br/>(option (a); option (b): server decides, ≤ 2 requests)"}
    K -- "Latin, ≥ 2 letters<br/>e.g. Sukhbaatar" --> LA["2 requests in parallel:<br/>as typed + 1 Cyrillic transliteration — AC 2"]
    K -- "district abbreviation word<br/>«БЗД 4-р хороо»" --> AB["2 requests in parallel:<br/>expanded + as typed — AC 4"]
    K -- "Cyrillic with у/о (Russian layout)<br/>«Сухбаатар»" --> RU["1 request as typed — AC 3"]
    K -- "other (mixed script, digits, «Улаанбаатар»)" --> ONE["1 request as typed"]
    RU --> R0{"200 with 0 features?"}
    R0 -- yes --> RU2["1 more request with ү/ө<br/>list shows that response"]
    R0 -- no --> LIST
    LA --> MG["Merge: no duplicates (osm_type + osm_id)<br/>MN first, ≤ 10 rows"]
    AB --> MG
    MG --> PAIR{"Pair outcome"}
    PAIR -- "both fail" --> FAIL["Failure state of the latest error:<br/>«Хайлт түр ажиллахгүй байна» + «Дахин оролдох»<br/>or «Түр хүлээгээд дахин оролдоно уу» (429)"]
    PAIR -- "one or both return" --> LIST["List «Хайлтын илэрц» (rows as NAV-005 S2)<br/>or «Илэрц олдсонгүй» if merged list is empty"]
    ONE --> LIST
    RU2 --> LIST
    LIST -- "tap a result" --> P(["F3 route preview within 500 ms"])
    LIST -. "newer settled query" .-> DB
    FAIL -- "«Дахин оролдох»" --> DB
```

- An older response never replaces the list of a newer settled query (AC 7). Loading row «Ачаалж байна…» only after 300 ms (NAV-005 S2).

## F2. Coordinate card with the nearest place (AC 8–13)

The card is bottom-anchored and «Маршрут гаргах» is its last row, so the nearest-place area can grow upwards without moving the button under the user's finger.

```mermaid
stateDiagram-v2
    direction TB
    [*] --> Open: long-press 600 ms (NAV-005 AC 4)
    state Open {
        direction TB
        [*] --> CheckNet
        CheckNet --> Offline: no network → 0 requests,<br/>«Интернэт холболт алга» ≤ 500 ms
        CheckNet --> Pending: 1 GET /v1/reverse<br/>lat/lon unrounded, lang, limit=1, radius=0.5
        Pending --> Loading: still pending after 300 ms<br/>«Ачаалж байна…»
        Pending --> Place: feature
        Loading --> Place: feature
        Pending --> Empty: 200, 0 features
        Loading --> Empty: 200, 0 features
        Pending --> Unavailable: network error / 502–504 / 8 s
        Loading --> Unavailable: network error / 502–504 / 8 s
        Pending --> RateLimited: 429 Retry-After N (5 s default)
        Loading --> RateLimited: 429
        Pending --> Error: 400
        Loading --> Error: 400
        Offline --> Pending: network back → 1 request ≤ 2 s
        Unavailable --> Pending: «Дахин оролдох» (once per tap)
        RateLimited --> RateLimitedReady: after N s (0 requests meanwhile)
        RateLimitedReady --> Pending: «Дахин оролдох»
        Place: «Ойролцоох газар» + name, type label, context
        Empty: «Илэрц олдсонгүй»
        Unavailable: «Хайлт түр ажиллахгүй байна» + «Дахин оролдох»
        RateLimited: «Түр хүлээгээд дахин оролдоно уу», «Дахин оролдох» disabled
        Error: «Алдаа гарлаа», no retry
    }
    Open --> Open: another long-press → new card,<br/>old request cancelled / ignored — AC 12
    Open --> Preview: «Маршрут гаргах» (usable at once,<br/>never waits for reverse) — AC 8
    Open --> [*]: «Хаах» / Back
    Preview --> [*]: F3, destination text stays «Сонгосон цэг» — AC 9
```

- In **every** state the heading «Сонгосон цэг», the coordinates, the pin and «Маршрут гаргах» stay; the camera never moves (AC 9, 11).
- Language switch with a place shown: labels switch ≤ 1 s, name kept, 0 new requests (AC 13).
- 0 `reverse` requests for pans, zooms, the device position or search results (AC 12).

## F3. Route preview with alternatives and three modes (AC 14–24)

```mermaid
flowchart TD
    IN(["From a search result (F1) or «Маршрут гаргах» (F2)"]) --> LOC["NAV-005 F3 location checks<br/>(origin «Миний байршил»)"]
    LOC --> REQ["1 POST /v1/route: NAV-005 body + alternates: 2<br/>costing by tab (Машин auto / Явган pedestrian / Дугуй bicycle)<br/>at most 1 in flight — AC 14, 22"]
    REQ --> ST{"Response"}
    ST -- "200, k = 1–3 routes" --> DRAW["k lines; route 1 selected (above, emphasised)<br/>camera fits all routes + markers into the map area<br/>above the collapsed sheet, 40 dp padding, zoom ≤ 17, ≤ 1 s — AC 15, 16"]
    ST -- "400 DistanceExceeded on Явган / Дугуй" --> TF["«Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» — AC 24"]
    ST -- "400 DistanceExceeded on Машин / NoRoute" --> NR["«Маршрут олдсонгүй»<br/>(+ avoid hint if the switch is on → sheet expands to show the switch)"]
    ST -- "other states" --> OS["NAV-005 S3 states and precedence<br/>(offline, 429, unavailable, outside area, same point, error)"]
    DRAW --> SH["Sheet collapsed: handle, «Маршрут харах» + destination,<br/>tabs «Машин» «Явган» «Дугуй», summary of the selected route, «Эхлэх»<br/>(TalkBack on → sheet opens expanded)"]
    SH --> U{"User action"}
    U -- "tap an alternative line (48 dp hit box)<br/>or an option in «Маршрут сонгох»" --> SEL["Selected within 200 ms: emphasis + summary follow<br/>0 requests, camera still — AC 17"]
    SEL --> SH
    U -- "drag the sheet up" --> EX["Expanded: + «Маршрут сонгох» (k ≥ 2), avoid switch (Машин only),<br/>points block «Миний байршил» / destination — AC 18"]
    EX --> U
    U -- "change tab (settles 300 ms)" --> MODE{"Tab"}
    MODE -- "Машин" --> REQ
    MODE -- "Явган / Дугуй (switch hidden, no costing_options)" --> REQ
    U -- "avoid switch (Машин)" --> REQ
    U -- "«Эхлэх»" --> GO(["NAV-005 S5 guidance on the SELECTED route<br/>0 extra requests; other lines removed ≤ 1 s — AC 19<br/>reroutes: alternates 0, same costing — AC 19, 25"])
    U -- "«Хаах» / Back" --> BACK(["S1 or the coordinate card"])
    U -- "theme / language / rotation" --> KEEP["Lines, markers, selection and sheet state kept<br/>0 requests — AC 20"]
    KEEP --> SH
```

- A new response (mode, avoid or destination change) always selects its route 1 (AC 21).
- With *k* = 1 there is no «Маршрут сонгох» group (AC 18); with *k* < 3 nothing tells the user that fewer routes came back (R4: no promise of alternatives).
- Tab memory: the chosen tab is kept for the app session; a fresh start opens on «Машин» (AC 22).

## F4. Typing lock with the passenger override (AC 27–37)

The lock only decides whether the **keyboard** may open. Everything else on S1/S2/S3 stays usable (AC 33).

```mermaid
stateDiagram-v2
    direction TB
    [*] --> NoFixes
    NoFixes: No usable fixes (no permission, approximate only,<br/>services off, no fix yet) → typing allowed — AC 35
    NoFixes --> Released: good fixes arrive (S1/S3 in foreground, 1 Hz, on device only — AC 27, 28)
    Released: Released → typing allowed
    Released --> Engaged: 3 consecutive good fixes ≥ 4.2 m/s (15 km/h)<br/>spanning ≥ 2 s → within 1 s — AC 30
    Engaged: Engaged → keyboard blocked
    Engaged --> Released: every good fix in the last 10 s < 1.4 m/s (5 km/h), ≥ 5 fixes — AC 32
    Engaged --> Released: no good fix for 30 s / permission revoked / services off
    Engaged --> Overridden: «Би зорчигч» — AC 34
    Overridden: Overridden for the rest of the app session<br/>(never stored, never automatic)
    Overridden --> [*]: process ends / removed from Recents
    Released --> NoFixes: fixes stop (screen left, permission revoked)
    Engaged --> NoFixes: screens left → updates stop ≤ 2 s
```

Between 5 and 15 km/h the state does not change (hysteresis). The lock never asks for a permission and never reads location in the background (AC 27).

### F4a. What the user sees

```mermaid
flowchart TD
    TAP["User taps «Газар, хаяг хайх» (S1/S2)"] --> L{"Lock state"}
    L -- "released / no fixes / overridden" --> KB["Keyboard opens (NAV-005 S2)"]
    L -- "engaged" --> LC["Keyboard does NOT open. Within 500 ms the lock card<br/>appears under the field:<br/>«Хөдөлж байх үед бичих боломжгүй» (K1)<br/>«Жолооч бол зогсоод хайна уу» (K3)<br/>[Хаах]  [Би зорчигч] (K2) — AC 31<br/>TalkBack: K1 politely, once per engagement"]
    LC -- "«Би зорчигч»" --> OV["Field focused, keyboard opens ≤ 500 ms<br/>override for the session — AC 34"]
    LC -- "«Хаах» / Back / tap the map" --> S1(["S1 / S2 as before (no keyboard)"])
    LC -- "lock releases (stopped ≥ 10 s)" --> GONE["Lock card removed ≤ 1 s<br/>keyboard does NOT open by itself"]
    LC -- "tap a result already shown,<br/>«Хайлтыг арилгах», long-press" --> OK["Allowed as usual — AC 33"]

    TYPING["Keyboard open, user typing"] --> ENG{"Lock engages"}
    ENG --> HIDE["Keyboard hidden ≤ 1 s; typed text kept;<br/>in-flight search completes; results stay selectable;<br/>lock card appears between the field and the results;<br/>TalkBack: K1 politely, once — AC 31"]
    HIDE --> LC
```

- The search bar does **not** change before the user taps it (no lock icon, no changed placeholder): a driver is not drawn to a screen change they did not ask for (Von Restorff, safety). Design notes 4 in the screen spec.
- S3 has no text field (NAV-005), so the lock is invisible there; S5 guidance has no text input (AC 37). After «Дуусгах», the rule applies from the current fixes.

## F5. «Дугуй» guidance (AC 25–26)

```mermaid
flowchart LR
    P["S3 on «Дугуй», route selected"] -- "«Эхлэх»" --> G["NAV-005 S5 guidance<br/>banner, voice, progress, off-route, GPS loss, arrival"]
    G --> V["Voice schedule: «Дугуй» column<br/>navigation-ux §4.2 (main clamp(v×15 s, 60, 150) m, now clamp(v×3 s, 15, 30) m,<br/>no early prompt, continue-on ≥ 2 km), chaining ≤ 60 m §4.4"]
    G --> C["Camera: zoom 17.5, pitch 45° heading-up (§8)"]
    G -- "off-route" --> RR["Reroute: costing bicycle, no costing_options,<br/>alternates 0 — AC 25"]
    RR --> G
```

QA's G10 replay (P4 → P5 at 4.5 m/s) checks the column with the NAV-005 AC 34 rules: every manoeuvre except `depart` (and the D68 `arrive` exemption) gets at least one prompt that starts while it is **15–150 m** ahead.

## AC coverage
| AC | Flow step |
|---|---|
| 1–7 | F1 |
| 8–13 | F2 |
| 14–24 | F3 |
| 25–26 | F5 (rules: navigation-ux §4.2, §4.4, §8) |
| 27–37 | F4, F4a |
| 38–47 | No flow (strings, privacy, regression, verification); screen spec › Copy and Evidence |
