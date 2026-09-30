# Flow NAV-003: Search a place (Cyrillic / Latin, autocomplete, place card, coordinate card)

- **Story:** [NAV-003](../../requirements/stories/NAV-003-search-cyrillic-latin-autocomplete.md) (AC referenced per step)
- **Screen:** [`screens/NAV-003-search.md`](../screens/NAV-003-search.md). It adds a search field, results list, pin and place card to the NAV-002 map screen ([`screens/NAV-002-web-map.md`](../screens/NAV-002-web-map.md)). Nothing here is a separate page.
- **Prototype:** [`prototypes/NAV-003-search.html`](../prototypes/NAV-003-search.html) (static wireframe of every state, day/night, mn/en, and every width)
- **API:** `docs/architecture/api/openapi.yaml` 0.4.0, `GET /v1/search` and `GET /v1/reverse`. No contract change.

The diagrams quote copy by resource key (screen spec, §Copy). The `mn` value is in «» where it helps. Every string is a glossary term (T1–T31 and existing rows).

## F1. Type a query (happy path, request rules)

```mermaid
flowchart TD
    A([User focuses the search field<br/>first Tab stop — AC 1]) --> B[User types or pastes<br/>max 200 characters kept — AC 2]
    B --> C{Input empty?}
    C -- yes --> E0[Empty state: list closed, abort in-flight request,<br/>clear button hidden, no message — AC 31]
    C -- no --> D[Show clear button «Хайлтыг арилгах»<br/>clear the highlighted option<br/>restart the 250 ms debounce — AC 3]
    D --> F[Debounce fires: settled query =<br/>NFC, trim, collapse spaces — story Terms]
    F --> G{"≥ 2 characters?"}
    G -- no --> E1[List closed, no request — AC 4]
    G -- yes --> H{Same as the query<br/>whose list is shown?}
    H -- yes --> H1[Keep the list, no request — AC 6]
    H -- no --> I{Coordinate pair<br/>lat, lon in range?}
    I -- yes --> CO[One option «Сонгосон цэг» + coordinates<br/>no request — AC 26 → F3]
    I -- no --> J{Offline?<br/>navigator.onLine false}
    J -- yes --> OFF[State row «Интернэт холболт алга»<br/>no request — AC 34 → F4]
    J -- no --> K{Inside a 429<br/>Retry-After window?}
    K -- yes --> RL[State row stays «Түр хүлээгээд дахин оролдоно уу»<br/>retry disabled, nothing sent — AC 35 → F4]
    K -- no --> L[Rewrite for sending only, input unchanged:<br/>district abbreviation → full name — AC 16<br/>Latin-only → + at most 1 Cyrillic transliteration request — AC 15]
    L --> M["GET /v1/search q, lang=mn|en, limit 5–10,<br/>lat/lon = device fix ≤ 60 s old while following, else map centre,<br/>rounded to 3 decimals — AC 7, 9, 46<br/>abort / ignore any older request — AC 5"]
    M --> N{Answer within 300 ms?}
    N -- no --> LD[Loading row «Ачаалж байна…»<br/>listbox aria-busy=true — AC 13]
    LD --> O
    N -- yes --> O{Outcome}
    O -- "200 with features" --> R[Merge responses, drop duplicates osm_type+osm_id,<br/>MN results first, max 10 options — AC 8, 15<br/>each option: name / type label / context line — AC 17–19]
    R --> R2["Live region: «{count} илэрц олдлоо» once — AC 42"]
    R2 --> SEL([User picks an option → F2])
    O -- "200, empty features" --> NR[State row «Илэрц олдсонгүй», not an error<br/>announced politely — AC 32]
    O -- "network / CORS / 502 / 503 / 504 / 8 s timeout" --> UN[State row «Хайлт түр ажиллахгүй байна»<br/>+ «Дахин оролдох» — AC 33 → F4]
    O -- "429 Retry-After N" --> RL
    O -- 400 --> BR[State row «Алдаа гарлаа», no retry of the same request — AC 37]

    classDef err fill:#F9DEDC,stroke:#B3261E,color:#410E0B
    classDef off fill:#303030,stroke:#303030,color:#F2F2F2
    class UN,BR,RL err
    class OFF off
```

Notes
- The input always shows exactly what the user typed. Abbreviation expansion and transliteration change only what is sent (AC 16).
- When the first of two requests (as typed + transliteration) answers, its options may render at once. The second response merges in without reordering options the user may already be looking at: new options are added below, `MN` first (AC 8, 15). The live region announces once, when both have answered or after 1 s, whichever is first (AC 42).
- While a new query is pending, the previous options stay on screen, but no option is highlighted, until the response or the 300 ms loading row replaces them. This avoids flicker when typing fast. A response for an older query never replaces a newer list (AC 5).
- Focus alone never opens the list and never sends a request. Only typing, pasting, ArrowDown (reopens the last list for the same text) or Enter does (AC 1, 41).
- GPS lost, stale fix (> 60 s), or location permission denied: the bias quietly falls back to the map centre. No message, and search never fails because of location (story edge cases). Search never calls the Geolocation API itself (AC 9).

## F2. Select a result: fly-to, pin, place card

```mermaid
flowchart TD
    A([List open with options]) --> B{How?}
    B -- "click / tap an option" --> S
    B -- "ArrowDown/Up highlight + Enter" --> S
    B -- "Enter, nothing highlighted" --> E{List for the current<br/>settled query rendered?}
    E -- yes --> S1[Take the first option] --> S
    E -- "no (still debouncing or pending)" --> E2[Search now, no debounce — AC 41]
    E2 --> E3{First response}
    E3 -- has options --> S1
    E3 -- "empty / state message" --> E4[Show that state row, select nothing]
    S[Select option] --> T[Close list. Input shows the selected name — AC 21<br/>Replace any previous pin + card: never two pins — AC 22]
    T --> U[Pin at the feature point, name = result name<br/>Card opens: heading = name, type label, context line,<br/>coordinates «47.91881, 106.91690» — AC 21<br/>Focus → card heading — AC 43]
    U --> V{Feature has extent?}
    V -- yes --> V1["fitBounds(extent): padding 40 px + the UI covering that edge,<br/>max zoom 17 — AC 20"]
    V -- no --> V2["flyTo point at viewport centre ±5 px:<br/>z13 for Хот, Суурин, Дүүрэг, Хороо, Хороолол, Аймаг, Сум<br/>z16 for every other type — AC 20"]
    V1 --> W[Arrives within 2 s<br/>reduced motion: jump within 500 ms — AC 20]
    V2 --> W
    W --> X([Card open. Pan / zoom keeps the pin at its place,<br/>card stays open — AC 24])
    X -- "«Хаах» or Esc in the card" --> Y[Pin + card removed, focus → search input,<br/>query text stays — AC 22]
    X -- "user types a new query" --> Z["Card hidden, not closed, while the list is open.<br/>Pin stays. List closes without a pick → card shows again"]
    X -- "clear button / delete all text" --> Z2[Input empty, card stays open — AC 31]
    X -- "right-click / long-press elsewhere" --> F3([F3: coordinate card replaces this card])
```

## F3. Coordinate card (reverse geocoding)

```mermaid
flowchart TD
    A1([Desktop: right-click on the map]) --> P
    A2([Touch: long-press ≥ 600 ms, moved ≤ 10 px]) --> P
    A3([Typed coordinates → option «Сонгосон цэг» selected<br/>camera centres, z16 or current zoom if higher — AC 26]) --> P
    A4([Normal click, drag, pinch]) --> NO[Nothing opens — AC 25]
    P[Within 500 ms: pin «Сонгосон цэг» at the point<br/>card heading «Сонгосон цэг» + coordinates<br/>camera does not move for right-click / long-press — AC 25<br/>focus → card heading — AC 43] --> Q{Offline?}
    Q -- yes --> QO[Nearest-place area: «Интернэт холболт алга», no button.<br/>Back online while the card is open → send reverse once]
    QO -. online event .-> R
    Q -- no --> QR{In a reverse 429 window?}
    QR -- yes --> RL
    QR -- no --> R["GET /v1/reverse lat/lon ≥ 5 decimals (not rounded),<br/>lang, limit=1, radius=0.5 — exactly one request — AC 27"]
    R --> S{Answer within 300 ms?}
    S -- no --> SL[Nearest-place area: «Ачаалж байна…» — AC 27] --> T
    S -- yes --> T{Outcome}
    T -- "feature" --> OK[«Ойролцоох газар» + name, type label, context line<br/>pin and heading stay at the chosen point — AC 28]
    T -- "empty (steppe, Beijing X2)" --> EM[«Илэрц олдсонгүй», not an error — AC 29]
    T -- "network / 5xx / 504 / 8 s timeout" --> UN[«Хайлт түр ажиллахгүй байна» + «Дахин оролдох»<br/>retry re-sends once — AC 30]
    T -- "429 Retry-After N" --> RL[«Түр хүлээгээд дахин оролдоно уу»<br/>«Дахин оролдох» disabled N s, nothing sent — AC 36]
    T -- 400 --> BR[«Алдаа гарлаа», no retry of the same request — AC 37]
    UN -- «Дахин оролдох» --> R
    RL -- "after N s: user presses «Дахин оролдох»<br/>or opens another coordinate card" --> R

    classDef err fill:#F9DEDC,stroke:#B3261E,color:#410E0B
    classDef off fill:#303030,stroke:#303030,color:#F2F2F2
    class UN,BR,RL err
    class QO off
```

Notes
- The heading, coordinates and pin never change with the `reverse` outcome. Only the nearest-place area changes (AC 28–30). The card never calls the result an address (story R5, glossary "Nearest place").
- Right-click is taken over only on the map canvas (the browser menu is suppressed there, and nowhere else). On Android Chrome a long-press also fires `contextmenu`: one gesture opens one card and sends one `reverse` request.

## F4. Search request states (error and recovery paths)

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Empty
    Empty --> Debouncing: keystroke
    Debouncing --> Empty: text cleared / < 2 chars
    Debouncing --> Pending: settled query sent
    Debouncing --> Offline: settled query while offline
    Debouncing --> Cooldown: settled query inside Retry-After window
    Pending --> Loading: still pending at 300 ms
    Pending --> Results: 200 + features
    Loading --> Results: 200 + features (row gone ≤ 200 ms)
    Pending --> NoResults: 200, empty
    Loading --> NoResults: 200, empty
    Pending --> Unavailable: network, CORS, 502, 503, 504, 8 s
    Loading --> Unavailable: same
    Pending --> Cooldown: 429 (Retry-After N, default 5 s)
    Pending --> BadRequest: 400
    Unavailable --> Pending: «Дахин оролдох» (once) or next settled query
    NoResults --> Debouncing: keystroke
    Results --> Debouncing: keystroke
    BadRequest --> Debouncing: keystroke
    Offline --> Pending: online event and query ≥ 2 chars (once, ≤ 2 s)
    Offline --> Debouncing: keystroke (still offline → Offline again)
    Cooldown --> CooldownOver: N seconds passed (nothing sent)
    CooldownOver --> Pending: next settled query or «Дахин оролдох»
    note right of Cooldown
        «Түр хүлээгээд дахин оролдоно уу»
        «Дахин оролдох» aria-disabled=true
        input still accepts text
    end note
    note right of Unavailable
        «Хайлт түр ажиллахгүй байна»
        + «Дахин оролдох», no auto-retry
    end note
```

Precedence inside the list area, highest first: Offline > Cooldown (429) > Unavailable > BadRequest > Loading > NoResults / Results. Only one state row shows at a time. NAV-002 banners keep their own precedence (offline > tiles unavailable > loading, NAV-002 AC 45) and are never covered by the list (AC 39).

## F5. Keyboard and screen reader (combobox)

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Closed
    Closed --> Open: typing gives options / state row
    Closed --> Open: ArrowDown with a list for the same text
    Closed --> Closed: Esc clears the input (second Esc)
    Open --> Highlighted: ArrowDown (first) / ArrowUp (last)
    Highlighted --> Highlighted: ArrowDown / ArrowUp, wraps
    Highlighted --> Selected: Enter
    Open --> Selected: Enter (first option, AC 41)
    Open --> Closed: Esc (text stays)
    Highlighted --> Closed: Esc (text stays)
    Open --> Closed: Tab (no selection, focus moves on)
    Highlighted --> Closed: Tab (no selection)
    Selected --> CardFocused: card opens, focus on heading
    CardFocused --> Closed: «Хаах» or Esc, focus back to input
```

- Focus stays in the input while the highlight moves (`aria-activedescendant`, AC 41).
- The live region announces once per settled query: count, «Илэрц олдсонгүй», or the state message (AC 42).

## F6. Language switch while search is open (AC 38)

```mermaid
flowchart LR
    A([Language button pressed]) --> B[All NAV-003 labels, type labels,<br/>state messages switch ≤ 500 ms]
    B --> C{List open with results?}
    C -- yes --> D[Re-request the settled query once with the new lang<br/>rules of F1 apply: offline / 429 / errors]
    C -- no --> E{Card open?}
    D --> E
    E -- yes --> F[Card keeps the place name it has,<br/>switches type label, labels, state message]
    E -- no --> G([Done])
    F --> G
```
