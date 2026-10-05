# Flow NAV-018: Android route preview, choosing the start point (search, map point, swap) and the turn list «Маршрутын заавар»

- **Story:** [NAV-018](../../requirements/stories/NAV-018-android-origin-choice-turn-list.md) (Phase 1 follow-up of NAV-011, priority should / P2, D136). AC referenced per step.
- **Screens:** [`screens/NAV-018-android-origin-turn-list.md`](../screens/NAV-018-android-origin-turn-list.md). It is a **delta** on [`screens/NAV-011-android-route-preview-search-parity.md`](../screens/NAV-011-android-route-preview-search-parity.md) (S3 sheet, coordinate card, typing lock), which is itself a delta on NAV-005.
- **Base flows:** [`flows/NAV-011-android-route-preview-search-parity.md`](NAV-011-android-route-preview-search-parity.md) F3 (preview) is **extended** by F1–F6 below: the start is no longer always «Миний байршил». NAV-011 F1 (assisted search), F2 (coordinate card) and F4 (typing lock) are **reused** inside the point editor and the preview-time card. NAV-005 F3 (location access) is reused unchanged. Guidance (NAV-005 S5, NAV-012) is unchanged.
- **Web reference:** [`flows/NAV-004-route-preview.md`](NAV-004-route-preview.md) F2 (set and change points) and F4 (turn list). Android differences are named in the screen spec (Alternatives considered).
- **Prototype:** [`prototypes/NAV-018-android-points.html`](../prototypes/NAV-018-android-points.html).
- **API:** `openapi.yaml` `search`, `reverse`, `postRoute`. No contract change for the design.

Copy is quoted by its `mn` value in «»; every string is a glossary term (O1, K1–K3 and B2 are `needs native review`). Keys are in the screen spec › Copy.

## F1. Opening the preview: which start? (AC 1, 2, 10, 32)

```mermaid
flowchart TD
    IN(["From a search result or the coordinate card «Маршрут гаргах»<br/>(NAV-005 AC 3–4)"]) --> P{"Precise permission and a good fix<br/>≤ 60 s old, or one within 10 s?<br/>(NAV-005 F3 checks, unchanged)"}
    P -- yes --> DEV["Start field «Миний байршил» (my_location icon, primary colour)<br/>destination field = result name or «Сонгосон цэг»<br/>1 POST /v1/route exactly as today — AC 1"]
    P -- "no: never asked, denied, approximate,<br/>services off, no fix in 10 s" --> EMPTY["Start field EMPTY: placeholder «Эхлэх цэг сонгох»<br/>NAV-005 location message in the summary region (Open question 2 (a))<br/>swap disabled, «Эхлэх» disabled, 0 route requests,<br/>no OS dialog unless the user acts on the message — AC 2"]
    EMPTY -- "user acts on the message<br/>(«Үргэлжлүүлэх» / «Тохиргоо нээх»)" --> LOCF(["NAV-005 F3 permission flow"])
    LOCF -- "good fix" --> DEV
    EMPTY -- "user sets a chosen start<br/>(F2 editor or F3 map point)" --> CH["Message removed ≤ 500 ms<br/>1 route request — AC 2"]
    DEV --> R(["F4 request lifecycle"])
    CH --> R
```

- The start is fixed when it is set: later fixes, GPS loss or permission changes never change it and send **0** requests (AC 10). They only affect «Эхлэх» (F5).
- A chosen start is **never kept**: every new preview starts with F1 again (AC 32, Open question 3 (a)). Nothing is stored.

## F2. Changing a point in the point editor (AC 3–5, 7, 9, 34)

The field in the sheet is a read-only "button field". A tap opens the **point editor**: the sheet is hidden (not closed, state kept) and the field opens at the top of the screen in the S1 search-bar position, with the NAV-011 search underneath (same code, same states, same strings). One field is edited at a time.

```mermaid
flowchart TD
    TAP["Tap the start or destination field in the sheet"] --> LK{"NAV-011 typing lock engaged?"}
    LK -- "no (released, no fixes, overridden)" --> ED["Point editor ≤ 300 ms: field focused, keyboard open,<br/>current text fully selected (one keystroke replaces it)<br/>START editor only: option card «Миний байршил» first<br/>(not shown when the start already is «Миний байршил»)<br/>0 requests on open"]
    LK -- engaged --> EDL["Point editor WITHOUT keyboard, field not focused ≤ 500 ms:<br/>START: option card «Миний байршил», then the lock card<br/>DESTINATION: the lock card<br/>lock card = K1, K3, «Хаах», «Би зорчигч» — AC 34"]
    EDL -- "«Би зорчигч»" --> ED
    EDL -- "«Хаах» / Back" --> BACK
    EDL -- "«Миний байршил»" --> ME
    ED --> TY{"User action"}
    TY -- "types (any script)" --> S["NAV-011 F1 assisted search:<br/>debounce 250 ms, ≤ 2 requests per settled query, D30 bias,<br/>states «Ачаалж байна…», «Илэрц олдсонгүй», «Интернэт холболт алга»,<br/>«Хайлт түр ажиллахгүй байна», «Түр хүлээгээд дахин оролдоно уу» — AC 4"]
    S --> TY
    TY -- "typed coordinates" --> CO["Whatever NAV-011 S2 shows at verification time:<br/>before D137 = 1 search as text (AC 7 of NAV-011);<br/>after D137 = its coordinate option → point «Сонгосон цэг», 0 searches — AC 5"]
    CO --> TY
    TY -- "selects a result" --> SET["Point = feature coordinate (≥ 5 decimals)<br/>field text = result name"]
    TY -- "selects «Миний байршил»" --> ME{"Precise permission and<br/>good fix ≤ 60 s old?"}
    ME -- yes --> SETME["Start = that fix, field «Миний байршил»"]
    ME -- no --> NF(["NAV-005 F3 flow: rationale → «Үргэлжлүүлэх» → OS dialog,<br/>approximate, services off, 10 s → «Байршил тодорхойлж чадсангүй»"])
    NF -- "good fix ≤ 10 s" --> SETME
    NF -- "any failure" --> KEEP["Start unchanged, 0 route requests;<br/>the NAV-005 message shows in the editor's option card area"]
    TY -- "Back / tap the map / clear text and leave" --> BACK["Editor closes; the field shows its previous text;<br/>point unchanged; 0 requests — AC 7"]
    SET --> CL["Editor closes ≤ 500 ms, sheet back in its previous state<br/>(collapsed or expanded) — AC 4"]
    SETME --> CL
    CL --> SAME{"Start and destination<br/>within 10 m?"}
    SAME -- yes --> N10["«Эхлэх цэг, очих газар ижил байна» ≤ 500 ms<br/>0 route requests — AC 9"]
    SAME -- "no, both points set" --> R(["F4: 1 route request"])
```

- The keyboard is never opened by the lock releasing (NAV-011 Design note 6). A response for a query that is no longer the editor's text is ignored (NAV-011 AC 7 rule; only one editor exists at a time, so it can never fill the other field's list — AC 4).
- «Хайлтыг арилгах» (clear) empties the text only. Leaving after clearing restores the previous text; the point is never cleared by the user (AC 7).

## F3. A map point as start or destination (AC 6)

```mermaid
stateDiagram-v2
    direction TB
    [*] --> Preview
    Preview --> Card: long-press 600 ms on the map<br/>(also on a route line)
    state Card {
        direction TB
        [*] --> Reverse
        Reverse: NAV-011 F2 nearest-place area<br/>(1 GET /v1/reverse, all 7 states)
    }
    Card: Coordinate card «Сонгосон цэг» replaces the sheet<br/>(sheet hidden, not closed)<br/>candidate pin (outline) at the point<br/>route lines and markers stay on the map<br/>buttons «Эхлэх цэг болгох» / «Очих газар болгох»<br/>(stacked, usable at once, never wait for reverse)
    Card --> Card: another long-press → new card,<br/>old reverse cancelled or ignored
    Card --> SetStart: «Эхлэх цэг болгох»
    Card --> SetDest: «Очих газар болгох»
    Card --> Preview: «Хаах» / Back → unchanged, 0 route requests
    SetStart: start = point, field «Сонгосон цэг»<br/>(never the nearest-place name)
    SetDest: destination = point, field «Сонгосон цэг»
    SetStart --> Back2: card closes ≤ 500 ms
    SetDest --> Back2
    Back2: Sheet shown again in its previous state<br/>markers updated at once
    Back2 --> [*]: F4 (1 request when both points are set, after the AC 9 same-point check)
```

- A long-press that lands on the device position or on the other point leads to F2's same-point rule (N10, 0 requests) once a button is chosen.
- In wide windows the card takes the side sheet's place (same column).

## F4. Swap and the route request lifecycle (AC 11–14, 17)

```mermaid
flowchart TD
    SW["Swap «Эхлэх цэг, очих газрыг солих»<br/>(enabled only when both points are set)"] --> X["≤ 200 ms: points, field texts, field icons and markers swap<br/>«Миний байршил» keeps the fix it was set from — AC 11"]
    X --> TRIG
    PT["Point set (F2, F3)"] --> TRIG{"Triggering action"}
    TAB["Mode tab settles 300 ms / avoid switch /<br/>«Дахин оролдох» / network back"] --> TRIG
    TRIG --> SP{"Same point (≤ 10 m)?"}
    SP -- yes --> N10["N10, 0 requests"]
    SP -- no --> RL{"429 Retry-After wait running?"}
    RL -- yes --> WAIT["Fields and markers already updated;<br/>«Түр хүлээгээд дахин оролдоно уу», retry disabled;<br/>0 requests until the wait ends — AC 12"]
    RL -- no --> NET{"Online?"}
    NET -- no --> OFF["«Интернэт холболт алга»; 1 request by itself<br/>≤ 2 s after the network returns — AC 17"]
    NET -- yes --> REQ["Cancel/ignore the in-flight request (≤ 1 in flight)<br/>1 POST /v1/route: locations [start, destination] unrounded,<br/>alternates 2, costing per tab, costing_options per NAV-011 AC 23 — AC 12, 13<br/>old lines, summary, options and turn list removed at request start;<br/>markers stay at the new points"]
    REQ --> RES{"Response"}
    RES -- "200" --> ROUTE["Route state: k lines, route 1 selected, camera fit (NAV-011 AC 16)<br/>snap notice for the larger of waypoints[0/1].distance > 500 m — AC 14"]
    RES -- "errors" --> ST["NAV-005 / NAV-011 states, same precedence:<br/>N9 outside area (either point), N11 too far (Явган/Дугуй), «Маршрут олдсонгүй» (+ N12 hint),<br/>429, unavailable + «Дахин оролдох», «Алдаа гарлаа» — AC 17"]
    ROUTE --> F5(["F5 «Эхлэх» gating"])
```

- Swapping twice restores the original points and sends 2 requests in total (AC 11). An older response never replaces a newer one (NAV-011 AC 14).
- The camera moves only on the first render of a new response, never on a swap or a point change by itself (NAV-011 P3).

## F5. «Эхлэх»: only from the device position (AC 15, 16; Open question 1 (a))

```mermaid
flowchart TD
    RT["Route state renders"] --> K{"Start kind"}
    K -- "device start + precise permission" --> EN["«Эхлэх» enabled (NAV-005 AC 7, 10; NAV-011 AC 19)"]
    K -- "chosen start (search result, map point,<br/>or «Миний байршил» swapped to the destination)" --> DIS["«Эхлэх» disabled; ≤ 200 ms the hint<br/>ⓘ «Замчлал зөвхөн таны байршлаас эхэлнэ» (O1)<br/>appears above it in the pinned footer<br/>TalkBack: «Эхлэх», disabled, then O1 — AC 15"]
    DIS -- "tap on the disabled button" --> NOP["Nothing: no guidance, 0 requests, no dialog"]
    DIS -- "start set to «Миний байршил» (F2) or swap back (F4)" --> REQ2["1 route request → O1 removed,<br/>«Эхлэх» enabled with the new route"]
    EN -- "«Эхлэх»" --> GO(["NAV-005 S5 guidance on the selected route of the latest response,<br/>0 extra requests — AC 16 (NAV-012 unchanged)"])
```

- O1 appears only together with a route. In states without a route «Эхлэх» is disabled anyway (NAV-005 S3) and O1 is not shown.
- With the NAV-012 battery hint as well: collapsed = summary → battery entry row → O1 → «Эхлэх»; expanded = the full battery hint first in the lower part, O1 stays pinned above «Эхлэх» (AC 30).

## F6. Turn list «Маршрутын заавар» (AC 18–26)

```mermaid
flowchart TD
    RS["Route state, any mode, any start"] --> EXP{"Sheet"}
    EXP -- collapsed --> NOL["No list (AC 18). The drag handle and the expanded part<br/>are the way in; TalkBack users get the sheet expanded"]
    EXP -- "expanded (drag, fling, handle tap)<br/>or wide-window side sheet" --> L["Lower part, last item: heading «Маршрутын заавар»<br/>one row per legs[0].steps of the SELECTED route:<br/>icon · instruction (ADR-0008 Android rules) · street name · distance<br/>(no distance on the arrive row) — AC 18, 19"]
    L --> A{"User action"}
    A -- "selects another route<br/>(line tap or «Маршрут сонгох»)" --> SEL["≤ 200 ms: the list shows that route's steps;<br/>if the user had scrolled into the list, the body scrolls<br/>so the heading is at the top; 0 requests — AC 21"]
    SEL --> L
    A -- "taps a row" --> ROW{"TalkBack on or wide window?"}
    ROW -- "no (portrait, touch)" --> COL["Sheet collapses (250 ms; reduced motion: jump)<br/>camera eases to maneuver.location, zoom max(17, current),<br/>centred in the map band above the collapsed sheet, ≥ 40 dp padding<br/>manoeuvre point drawn; row marked as activated — ≤ 1 s, AC 22"]
    ROW -- "yes" --> STAY["Sheet stays expanded; camera centres the step in the<br/>uncovered map area (≥ 40 dp padding); focus stays on the row"]
    COL -- "drag the sheet up again" --> BACKL["List at the same scroll position,<br/>activated row visible — AC 22"]
    BACKL --> L
    A -- "language / theme / rotation" --> KEEP["Labels and instruction texts switch ≤ 1 s, street names unchanged,<br/>selection, scroll position and sheet state kept, 0 requests — AC 24, 33"]
    KEEP --> L
    A -- "new response (point, mode, avoid, swap)" --> NEW["List rebuilt for route 1 of the new response;<br/>activated row and manoeuvre point cleared — NAV-011 AC 21"]
```

- The list is lazy: only visible rows plus a small buffer are composed (AC 25). The expanded body (fields, tabs, summary, options, switch, list) is **one** lazy scroll above the pinned «Эхлэх», so a 500-step list never nests a scroll inside a scroll.

## F7. Close, rotation, language, process death (AC 24, 32, 33)

```mermaid
flowchart LR
    PV["Preview with any start"] -- "«Хаах» (✕ in the points block) / Back (collapsed)" --> S1(["S1 or the coordinate card that opened it<br/>chosen start forgotten — AC 32"])
    PV -- "Back (expanded)" --> COLL["Sheet collapses first (M3)"]
    PV -- "rotation / theme / language" --> KEEP["Points, swap state, list, selection, sheet state kept, 0 requests<br/>«Миний байршил» ↔ My location, «Сонгосон цэг» ↔ Selected point;<br/>result names unchanged — AC 33"]
    PV -- "process death" --> NONE(["No preview restored (NAV-012 restores guidance only)"])
```

- System Back order: point editor → closes the editor (restores the field); coordinate card → back to the preview; expanded sheet → collapses; collapsed sheet → closes the preview (NAV-011 Interactions).

## AC coverage
| AC | Flow step |
|---|---|
| 1, 2 | F1 |
| 3–5, 7 | F2 |
| 6 | F3 |
| 8 | Screen spec › Map markers; map-style §7.7 |
| 9 | F2, F4 (same point) |
| 10 | F1 (start fixed when set) |
| 11–14, 17 | F4 |
| 15, 16 | F5 |
| 18–26 | F6 |
| 27–30 | No flow (layout, accessibility, strings); screen spec › Layout rules Q1–Q8, Accessibility, Copy |
| 31 | No UI (privacy); F1 note (nothing stored) |
| 32, 33 | F1 note, F7 |
| 34 | F2 (locked branch) |
| 35–38 | No UI (regression, coordination, verification) |
