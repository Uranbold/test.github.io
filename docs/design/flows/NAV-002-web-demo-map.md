# Flow NAV-002: Web demo map

- **Story:** [NAV-002](../../requirements/stories/NAV-002-web-demo-map.md) (AC referenced per step)
- **Screen:** [`screens/NAV-002-web-map.md`](../screens/NAV-002-web-map.md) (one screen with overlays; no navigation between screens)
- **Style / tokens:** [`map-style.md`](../map-style.md), [`tokens.json`](../tokens.json)
- **Prototype:** [`prototypes/NAV-002-web-map.html`](../prototypes/NAV-002-web-map.html) (static wireframe, every state, day/night, mn/en)

Copy in the diagrams is quoted by resource key (see the screen spec, §Copy). Mongolian strings in brackets are the `mn` values.

## F1. Open the page (happy path and tile errors)

```mermaid
flowchart TD
    A([User opens http://localhost:5173]) --> B[Read saved theme + language<br/>default: day, mn — AC 26, 30]
    B --> C[Render chrome: top controls, attribution strip<br/>html lang + title from resources — AC 30, 34]
    C --> D[Create map: centre P1, z12, bearing 0<br/>source pmtiles://GATEWAY/tiles/basemap.pmtiles — AC 2, 3, 5]
    D --> E{First idle with tiles<br/>within 300 ms?}
    E -- yes --> R[Ready: map + controls + scale bar]
    E -- no --> L[Loading pill «Ачаалж байна…»<br/>aria-live polite — AC 37]
    L --> F{Outcome}
    F -- first idle with tiles --> R
    F -- "archive fetch fails: network / CORS / 404 / 5xx / not PMTiles v3<br/>or no tiles after 10 s" --> X[Blocking card: «Газрын зургийг ачаалж чадсангүй»<br/>+ «Дахин оролдох» — AC 38, 39]
    F -- browser goes offline --> O1[Blocking card, offline variant:<br/>«Интернэт холболт алга» — AC 45]
    X -- "«Дахин оролдох»" --> L2[Card button shows progress<br/>re-create source, keep theme + language + camera]
    L2 -- tiles render within 5 s --> R
    L2 -- fails again --> X
    O1 -- online event --> L
    R --> Z([User explores: F2, F3, F4])

    classDef err fill:#F9DEDC,stroke:#B3261E,color:#410E0B
    classDef off fill:#303030,stroke:#303030,color:#F2F2F2
    class X,L2 err
    class O1 off
```

Notes
- The loading pill never shows before 300 ms, so a fast load has no flash (AC 37). It hides within 500 ms of the first `idle`.
- There is no endless spinner: the 10 s watchdog always ends in Ready or the blocking card (AC 38).
- WebGL unavailable or an unexpected start-up exception (not in the AC, generic error row in the glossary): blocking card with «Алдаа гарлаа» + «Дахин оролдох» (reloads the page). The attribution stays visible.

## F2. Tile errors and connectivity while the map is shown

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Healthy
    Healthy --> Degraded: 3 consecutive tile requests fail<br/>(network, 404, 5xx) — AC 41
    Degraded --> Healthy: any later tile request succeeds<br/>(pan, zoom or «Дахин оролдох»)<br/>banner hides ≤ 2 s
    Healthy --> Offline: offline event — AC 43
    Degraded --> Offline: offline event (offline wins, AC 45)
    Offline --> Healthy: online event, missing tiles reload<br/>banner hides ≤ 2 s — AC 44
    note right of Degraded
        Top banner (error container):
        «Газрын зургийг ачаалж чадсангүй»
        + «Дахин оролдох»
        Drawn tiles stay, map stays interactive
    end note
    note right of Offline
        Top banner (message surface):
        «Интернэт холболт алга»
        No action, recovers by itself
    end note
```

- Archive replaced on the gateway mid-session (new ETag after a NAV-001 rebuild, AC 42): the failing requests count toward Degraded. «Дахин оролдох» clears the PMTiles header/directory cache and re-requests the visible tiles, so it picks up the new archive. The map is never blank without a message for more than 10 s.
- Empty areas (countryside, outside the extract such as X2 Beijing) are **not** errors: requests succeed with empty tiles, so the state stays Healthy (AC 10). Missing glyph ranges are not tile errors either.

## F3. My location

```mermaid
flowchart TD
    S([Page load]) --> P{Geolocation API present<br/>and secure context?}
    P -- no --> U[Button aria-disabled, slashed icon<br/>description «Байршил тодорхойлж чадсангүй» — AC 24]
    P -- yes --> Q{Permissions API says denied?<br/>no geolocation call — AC 18}
    Q -- denied --> D0[Button in denied style]
    Q -- granted / prompt / unknown --> I[Button idle]

    I -- press --> REQ[Button: locating<br/>watchPosition, timeout 10 s]
    D0 -- press --> REQ
    REQ -- browser prompt: Allow --> FIX
    REQ -- "PERMISSION_DENIED<br/>(prompt refused or blocked)" --> DEN[Message: «Байршлын зөвшөөрөл олгоогүй байна»<br/>«Хөтчийн тохиргоонд байршлын зөвшөөрлийг асаана уу»<br/>+ «Хаах». Button denied — AC 21]
    REQ -- "POSITION_UNAVAILABLE / timeout 10 s" --> UNA[Message: «Байршил тодорхойлж чадсангүй»<br/>+ «Дахин оролдох» + «Хаах» — AC 22]
    UNA -- "«Дахин оролдох»" --> REQ
    DEN -- "press button again" --> DEN

    REQ -- first fix --> FIX[Fly to fix, zoom = max current, 15<br/>marker «Миний байршил» + accuracy circle<br/>button: following — AC 19, 25]
    FIX --> FOL((Following))
    FOL -- "new fix" --> FOL2[Marker + camera move ≤ 2 s — AC 20]
    FOL2 --> FOL
    FOL -- "user drags, keyboard-pans or<br/>double-click zooms" --> NF((Not following<br/>button outline))
    NF -- "new fix: marker moves, camera stays" --> NF
    NF -- press --> FIX
    FOL -- location error after a fix --> ST[Marker stale grey at last position<br/>Message «Байршил тодорхойлж чадсангүй» — AC 23]
    NF -- location error after a fix --> ST
    ST -- fixes resume --> FOL3[Marker normal, message hides ≤ 2 s]
    FOL3 --> FOL

    classDef err fill:#F9DEDC,stroke:#B3261E,color:#410E0B
    class DEN,UNA,ST err
```

Rules
- No geolocation call and no permission prompt until the first press (AC 18). Checking `navigator.permissions.query({name: "geolocation"})` at load is allowed (it does not prompt) and only pre-styles the button. If the permission later changes to granted (`change` event), the button returns to idle.
- Zooming with the wheel or pinch while following zooms **around the centre** (the marker), so following continues (Google Maps pattern). Double-click zooms at the pointer, so it ends following. Rotation and the zoom buttons keep following.
- Position outside Mongolia (X2): same as any fix, no message (AC 25).
- Coordinates never leave the browser (AC 47).

## F4. Theme and language

```mermaid
sequenceDiagram
    actor U as User
    participant UI as Chrome (CSS variables, resources)
    participant M as MapLibre
    participant S as localStorage (try/catch)
    U->>UI: Press theme button (shows the mode it switches to)
    UI->>M: setStyle(night.json or day.json, diff) — camera unchanged
    UI->>UI: data-theme on html → UI tokens switch in the same frame
    M-->>UI: style.load → re-add location layers (map-style.md §7)
    UI->>S: save theme — AC 28
    Note over UI,M: ≤ 2 s total (AC 27). Labels, marker, attribution stay.
    U->>UI: Press language button («English» / «Монгол»)
    UI->>UI: swap every string, html lang, document.title, scale units — ≤ 500 ms (AC 31)
    UI->>S: save language
    Note over UI,M: Map labels do not change (Open question 1 a). Open messages switch language too.
```

- If storage is unavailable (private mode, blocked), the toggles still work for the session and the next visit starts at day + mn.

## F5. Message precedence (AC 45)

```mermaid
flowchart LR
    subgraph status["Status slot (one at a time)"]
        direction TB
        o[Offline] --> t[Tiles unavailable] --> l[Loading]
    end
    subgraph loc["Location slot (independent)"]
        direction TB
        d[Denied] --- u[Could not determine / stale]
    end
    status -. both may show together, stacked .- loc
```

- Status slot: offline > tiles unavailable > loading. Only the highest is shown. Before any tile has rendered it uses the blocking card, afterwards the top banner.
- Location slot: one location message at a time (the newest replaces the older). It never covers the attribution or the scale bar (screen spec §Layout rules).

## Error-path checklist (design principle: offline, GPS-lost, no-result on every screen)
| Path | Where handled |
|---|---|
| Offline | F1 (blocking, before first tiles), F2 (banner) |
| Gateway down / rebuilding / CORS / bad archive | F1 blocking card, F2 Degraded banner |
| GPS / location lost after a fix | F3 stale |
| Permission denied / unsupported / insecure origin | F3 |
| No result | Not applicable (no search in NAV-002). An empty map area is normal content, not an error (F2 note) |
| Unexpected failure / no WebGL | F1 note, «Алдаа гарлаа» |
