# Flow NAV-004: Route preview A→B (alternatives, mode tabs, «Хүрэх цаг», turn list)

- **Story:** [NAV-004](../../requirements/stories/NAV-004-route-preview-web.md) (AC referenced per step)
- **Screen:** [`screens/NAV-004-route-preview.md`](../screens/NAV-004-route-preview.md). The route panel is part of the NAV-002 map screen; it replaces the NAV-003 search UI while it is open. Nothing here is a separate page.
- **Prototype:** [`prototypes/NAV-004-route-preview.html`](../prototypes/NAV-004-route-preview.html) (every state, day/night, mn/en, five widths)
- **Map layers and markers:** [`map-style.md` §7.2–7.3](../map-style.md)
- **API:** `openapi.yaml` 0.5.0 `postRoute` (`POST /v1/route`, Valhalla pass-through; web preview client profile and `bicycle` contracted, ADR-0008 §4). Field search uses NAV-003 `search`; the coordinate card uses `reverse`.
- **Instruction text:** ADR-0008 option (b): built on the client from `maneuver.type` / `modifier` / `exit` / `bearing_after` in both UI languages; Valhalla's narrative is never shown.

The diagrams quote copy by its `mn` value in «»; the resource keys are in the screen spec › Copy. Every string is a glossary term. "Location is on" = NAV-002 my location activated and the last fix ≤ 60 s old (story Terms).

## F1. Open the preview (entry points, origin, focus)

```mermaid
flowchart TD
    A1(["Place card from a search result<br/>NAV-003 AC 21"]) --> B
    A2(["Coordinate card: right-click, long-press<br/>or typed coordinates, NAV-003 AC 25–26"]) --> B
    B["User activates «Маршрут гаргах»<br/>click, tap, Enter, Space — AC 1, 2"] --> C["Within 500 ms: panel «Маршрут харах» opens<br/>search field + card hidden, not closed<br/>pin becomes the destination marker<br/>destination field = result name or «Сонгосон цэг» — AC 1, 2, 9"]
    C --> S{"Static build?<br/>features.routing false"}
    S -- yes --> F7(["F7: unavailable state within 1 s, 0 requests"])
    S -- no --> D{"Location is on?"}
    D -- yes --> E["Origin field «Миний байршил»<br/>origin = last fix, ≥ 5 decimals<br/>NAV-002 dot is the origin marker<br/>no Geolocation call — AC 3"]
    E --> E2["Focus → selected mode tab «Машин» — AC 46"]
    E2 --> REQ(["F3: request now — AC 3, 10"])
    D -- "no: never on, denied,<br/>unavailable, fix older than 60 s" --> G["Origin field empty, placeholder «Эхлэх цэг сонгох»<br/>no request, no browser prompt — AC 4"]
    G --> H["Focus → origin field — AC 46<br/>its list opens with «Миний байршил» — AC 5"]
    H --> F2(["F2: user sets the origin"])
```

Notes
- The camera does not move on opening. It moves when the first route renders (F4).
- The destination is the card's point (coordinates shown on the card), not the location of a `reverse` result (AC 2).

## F2. Set and change points

```mermaid
flowchart TD
    A([Panel open]) --> W{How?}
    W -- "origin field focused and empty" --> ML["List shows «Миний байршил» first<br/>no request — AC 5"]
    ML -- "user picks «Миний байршил»" --> LO{"Location is on?"}
    LO -- yes --> SET
    LO -- no --> LR["NAV-002 location request starts<br/>browser may prompt: user action — AC 5<br/>loading row after 300 ms"]
    LR --> LF{"Outcome within 10 s"}
    LF -- fix --> SET
    LF -- "denied" --> LD["NAV-002 AC 21 message in R2<br/>origin stays empty, no request<br/>focus → origin field"]
    LF -- "unavailable / timeout" --> LU["«Байршил тодорхойлж чадсангүй» in R2<br/>origin stays empty, no request<br/>focus → origin field"]
    W -- "user types in a field" --> T["NAV-003 search box behaviour:<br/>debounce, bias, states, strings — AC 6<br/>list in flow under the fields block<br/>the current point and route stay"]
    T -- "picks a result" --> SET
    T -- "typed coordinates, option «Сонгосон цэг»<br/>no search request" --> SET
    T -- "Esc / Tab / click away without a pick" --> RS["List closes, field text restored<br/>to the current point — no change"]
    W -- "right-click / long-press on the map — AC 7" --> CC["Coordinate card in the panel slot<br/>panel hidden, not closed<br/>candidate pin at the point<br/>reverse as NAV-003 for «Ойролцоох газар»"]
    CC -- "«Эхлэх цэг болгох» / «Очих газар болгох»" --> CS["Card closes, panel back<br/>that field = «Сонгосон цэг»<br/>focus → that field"]
    CS --> SET
    CC -- "«Хаах» / Esc" --> CB["Card closes, panel back unchanged<br/>focus → panel heading"]
    W -- "swap button — AC 8" --> SW{"Both points set?"}
    SW -- no --> SWD["aria-disabled, nothing happens"]
    SW -- yes --> SWP["Points, field texts and markers swap<br/>focus stays on swap"] --> REQ
    SET["Point set: field text = name / «Миний байршил» / «Сонгосон цэг»<br/>marker placed: NAV-002 dot, origin circle or pin — AC 9"] --> BOTH{"Both points set?"}
    BOTH -- no --> WAIT(["Wait for the other point, no request"])
    BOTH -- yes --> REQ(["F3: triggering action"])
```

Notes
- A point is never cleared by the user (no clear button). Emptying the origin field while focused shows «Миний байршил»; leaving the field without a pick restores the current point's text.
- The preview-time coordinate card does not replace the NAV-003 card that opened the panel, so closing the preview later returns to it (F6).

## F3. Route request lifecycle (every triggering action)

Triggering actions (story Terms): a point set or changed, a mode tab settled, swap, the avoid switch, «Дахин оролдох», or the online resume. A language switch is **not** one: with ADR-0008 the text is rebuilt on the client (AC 50, 0 requests).

```mermaid
flowchart TD
    A(["Triggering action"]) --> TAB{"Was it a tab change?"}
    TAB -- yes --> ST["Wait 300 ms settle<br/>another tab change restarts it — AC 11"] --> CLR
    TAB -- no --> CLR
    CLR["Remove old lines, summary, options, turn list<br/>abort or ignore any in-flight request — AC 13, 32"] --> SP{"Origin and destination<br/>≤ 10 m apart?"}
    SP -- yes --> SAME["«Эхлэх цэг, очих газар ижил байна»<br/>no request, within 500 ms — AC 14"]
    SP -- no --> STB{"Static build?"}
    STB -- yes --> F7(["F7"])
    STB -- no --> OFF{"Offline?<br/>navigator.onLine false"}
    OFF -- yes --> OFFS["«Интернэт холболт алга», no request<br/>— AC 38 → F5"]
    OFF -- no --> RL{"Inside a 429 wait?"}
    RL -- yes --> RLS["«Түр хүлээгээд дахин оролдоно уу» stays<br/>retry disabled, nothing sent — AC 37 → F5"]
    RL -- no --> POST["One POST /v1/route — AC 10<br/>locations = origin, destination; costing auto / pedestrian / bicycle<br/>alternates 2, format osrm, banner_instructions, units kilometers<br/>language mn-MN or en-US; no voice_instructions — ADR-0008<br/>costing_options only auto.exclude_unpaved with the switch on<br/>at most 1 in flight, client timeout 12 s — AC 13, 36"]
    POST --> L{"Answer within 300 ms?"}
    L -- no --> LOAD["«Ачаалж байна…», region aria-busy — AC 32"] --> O
    L -- yes --> O{Outcome}
    O -- "200, 1–3 routes" --> F4(["F4: render"])
    O -- "400 NoRoute,<br/>or DistanceExceeded on «Машин»" --> NR["«Маршрут олдсонгүй»<br/>+ avoid hint if the switch is on, no retry — AC 33, 35"]
    O -- "400 NoSegment / ValhallaError 171" --> OA["«Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна»<br/>no line, markers stay — AC 34"]
    O -- "400 DistanceExceeded<br/>on «Явган» / «Дугуй»" --> TF["«Энэ зай явганаар эсвэл дугуйгаар хэт хол байна» — AC 35<br/>limits are straight-line: walk 250 km, bike 500 km, so P1 → X2 on foot lands here"]
    O -- "network, CORS, 502, 503, 504,<br/>no answer in 12 s" --> UN["«Маршрутын үйлчилгээ түр ажиллахгүй байна»<br/>+ «Дахин оролдох» — AC 36 → F5"]
    O -- "429 Retry-After N" --> RLS
    O -- "other 400, other ValhallaError,<br/>413, unparsable 200" --> ER["«Алдаа гарлаа», no retry of the same request — AC 39"]

    classDef err fill:#F9DEDC,stroke:#B3261E,color:#410E0B
    classDef off fill:#303030,stroke:#303030,color:#F2F2F2
    class UN,ER,RLS err
    class OFFS off
```

Notes
- A response for an older request never replaces a newer result, even if it arrives later (AC 13). The client keeps a request counter or an `AbortController` per request.
- The state precedence in the result region is: same point > offline > rate-limited > unavailable/static > outside the area / too far / no route / error > loading > route (screen spec › States).
- The origin is fixed when the request is sent. Location updates while the panel is open never trigger a request (story edge case: no request storms).

## F4. Result: lines, camera, alternatives, turn list, ETA

```mermaid
flowchart TD
    A(["200 with k routes"]) --> R["Draw k lines: route 1 selected and emphasised,<br/>alternatives muted and 2 px narrower — AC 16<br/>summary: duration · distance, «Хүрэх цаг HH:MM» — AC 22–25<br/>route options if k ≥ 2 — AC 19<br/>turn list «Маршрутын заавар» — AC 26–27"]
    R --> SN{"A snap distance > 500 m?"}
    SN -- yes --> SNN["Also «Хамгийн ойрын зам сонгосон цэгээс {distance} зайтай» — AC 21"]
    SN -- no --> CAM
    SNN --> CAM["Camera fits all lines + both markers inside the uncovered map area<br/>40 px + panel / top bar / sheet, max z17, ≤ 1 s<br/>reduced motion: jump ≤ 500 ms — AC 17"]
    CAM --> LV["Live region once: «2 маршрут олдлоо, 4,3 км, 12 мин, Хүрэх цаг 14:35» — AC 47<br/>focus is not moved"]
    LV --> IDLE([Result shown])
    IDLE -- "click an alternative line (±10 px)<br/>or a route option / arrow keys" --> SEL["Within 200 ms: that line emphasised,<br/>summary + turn list follow<br/>0 requests, camera still — AC 18<br/>announce «Маршрут 2, …»"]
    SEL --> IDLE
    IDLE -- "turn row: click, Enter, Space" --> STEP["Camera to maneuver.location, z17 or higher, ≤ 1 s<br/>manoeuvre point shown — AC 30"]
    STEP --> IDLE
    IDLE -- "every 60 s ± 5 s" --> ETA["«Хүрэх цаг» recomputed from the clock<br/>0 requests, not announced — AC 25"]
    ETA --> IDLE
    IDLE -- "day/night switch" --> TH["Lines and markers re-added in the new colours<br/>selection kept, 0 requests — AC 20"]
    TH --> IDLE
```

## F5. Error and recovery paths

```mermaid
flowchart TD
    UN(["Routing unavailable + «Дахин оролдох»"]) -- "user presses «Дахин оролдох»" --> RE["Same request sent once — AC 36<br/>focus → selected mode tab when the button disappears"]
    RE --> F3(["F3 outcome"])
    UN -- "user changes an input" --> F3
    RL(["429: «Түр хүлээгээд дахин оролдоно уу»<br/>«Дахин оролдох» disabled N s<br/>N = Retry-After, else 5 s"]) -- "user changes fields, tabs, swap, switch<br/>during the N s" --> RLI["Inputs update, 0 requests — AC 37"]
    RLI --> RL
    RL -- "N s pass" --> RLA["Button active; nothing sent by itself"]
    RLA -- "«Дахин оролдох» or next triggering action" --> ONE["One request with the current inputs"] --> F3
    OFF(["Offline: «Интернэт холболт алга»"]) -- "online event, both points set, no route shown" --> AUTO["One request within 2 s, no user action — AC 38"] --> F3
    NR(["No route / outside the area / too far / error"]) -- "user changes tab, point, switch" --> F3
    SAME(["Same start and destination"]) -- "user changes a point" --> F3
```

## F6. Close the preview, language switch, coexistence

```mermaid
flowchart TD
    A([Panel open]) -- "«Хаах», or Esc with focus in the panel<br/>and no field list open — AC 41" --> C["Within 200 ms: panel, lines, origin marker,<br/>candidate pin and manoeuvre point removed<br/>in-flight request aborted, 0 new requests"]
    C --> D["Search field and NAV-003 card show again<br/>pin back at the card's point"]
    D --> E{"Opening card still exists?"}
    E -- yes --> E1(["Focus → its «Маршрут гаргах» button"])
    E -- "no (defensive)" --> E2(["Focus → search input"])
    A -- "Esc with a field list open" --> L["List closes, field text restored<br/>panel stays"]
    A -- "language switch — AC 50" --> LG["Labels and turn texts switch in place ≤ 500 ms<br/>text built on the client, ADR-0008<br/>0 requests, route, selection and focus kept"]
```

Notes
- While the panel has never been opened, NAV-002 and NAV-003 behave exactly as before: the search input is the first Tab stop and no route request is sent (AC 42).
- While the panel is open the NAV-003 search field is hidden, so the NAV-003 AC 1 rule "search input is the first Tab stop" applies only with the panel closed (screen spec › Accessibility › Tab order).

## F7. Static public build (routing off; D44, PO decision Q1 2026-09-30)

```mermaid
flowchart TD
    A(["Static build: VITE_STATIC_DEMO on<br/>features.routing false"]) --> B["«Маршрут гаргах» works, panel opens — AC 53"]
    B --> C["Within 1 s, no spinner:<br/>«Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох»"]
    C -- "«Дахин оролдох», tabs, swap, switch, setting a point" --> C2["Same row again within 1 s<br/>0 /v1/route requests to any host — AC 53, 54"]
    C2 --> C
    B -- "typing in a field" --> S["NAV-003 static state: «Хайлт түр ажиллахгүй байна»<br/>0 search requests"]
    B -- "right-click / long-press" --> K["Coordinate card with the two set buttons<br/>nearest place: NAV-003 static unavailable row"]
    C -- offline --> O["«Интернэт холболт алга» wins"]
```

## Keyboard walk-through (location on, RS1 P1 → P3, car)
1. Search «Зайсан», Enter → place card, focus on the card heading (NAV-003).
2. Tab to «Маршрут гаргах», Enter → panel opens, focus on the «Машин» tab, route requested (AC 3, 46).
3. The live region announces «3 маршрут олдлоо, 4,6 км, 13 мин, Хүрэх цаг 14:03» (AC 47).
4. ArrowRight → «Явган» selected; after 300 ms one request (AC 11). ArrowLeft → back to «Машин».
5. Tab → avoid switch; Tab → «Маршрут 1» radio; ArrowDown → «Маршрут 2» selected, announced, 0 requests (AC 18, 45).
6. Tab → turn list (first row); ArrowDown to row 3; Enter → camera to that turn (AC 30).
7. Tab → map canvas → … (NAV-002 order). Shift+Tab back into the panel.
8. Esc → panel closes, focus on «Маршрут гаргах», the card is back (AC 41).
