# Flow NAV-005: Android active navigation (map → destination → preview → guidance → arrival)

- **Story:** [NAV-005](../../requirements/stories/NAV-005-active-navigation-android.md) (first slice, Android only, D24). AC referenced per step.
- **Screens:** [`screens/NAV-005-android-navigation.md`](../screens/NAV-005-android-navigation.md) (S1 map, S2 search and coordinate card, S3 route preview, S4 location messages and permission rationale, S5 guidance, S6 arrival, S7 settings sheet, S8 notification).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) (banner, voice schedule, off-route, GPS loss, camera). Map layers: [`map-style.md` §7.4](../map-style.md).
- **Prototype:** [`prototypes/NAV-005-guidance.html`](../prototypes/NAV-005-guidance.html) (guidance, arrival, preview and message states); [`prototypes/NAV-005-location-dot.html`](../prototypes/NAV-005-location-dot.html) (browse location dot states, F12).
- **Section N (2026-10-04, AC 74–79, [D171–D178](../../requirements/decisions.md)):** the browse-map location dot, F12. F1 and F3 changed only where the my-location press now waits for a *showable* fix (accuracy known and ≤ 100 m).
- **API and architecture:** `openapi.yaml` 0.5.0 `search` (GET `/v1/search`), `postRoute` (POST `/v1/route`); [ADR-0009](../../architecture/adr/0009-android-guidance-client.md) (app-owned route client and reroute policy, off-route = 3 consecutive good fixes > 50 m, on-device voice schedule). The flows show the user-visible outcome.

Copy is quoted by its `mn` value in «»; every string is a glossary term; keys are in the screen spec › Copy. "Good fix" = accuracy ≤ 25 m; "fresh" = ≤ 10 s old (≤ 60 s for the preview origin).

## F1. Launch and map screen

```mermaid
flowchart TD
    A(["App launched"]) --> L{"Language chosen in the app before?"}
    L -- no --> MN["UI in Mongolian, whatever the device language — AC 60"]
    L -- yes --> SL["UI in the chosen language"]
    MN --> T
    SL --> T{"Theme setting<br/>(default «Автомат»)"}
    T --> M["S1 map: UB at P1 z12, day or night flavor<br/>labels name:mn → name → name:en<br/>«© OpenStreetMap contributors» visible — AC 1, 2, 58"]
    M --> NP["No OS location prompt on launch — AC 1"]
    M --> U{"User action"}
    U -- "types in «Газар, хаяг хайх»" --> F2S(["F2a search"])
    U -- "long-press on the map" --> F2C(["F2b coordinate card"])
    U -- "my-location button" --> F3(["F3 location access"])
    U -- "my-location button, location OK" --> F12(["F12 browse location dot"])
    U -- "«Тохиргоо»" --> F11(["F11 settings sheet"])
    M -. "tiles fail" .-> TE["Message «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох»<br/>(NAV-002 rule; search and long-press still work)"]
    M -. "offline" .-> OF["Message «Интернэт холболт алга»; loaded tiles stay"]
```

## F2. Choose a destination

### F2a. Search (AC 3)
```mermaid
flowchart TD
    S0["User types in «Газар, хаяг хайх»"] --> D{"Online?"}
    D -- no --> OFF["List row «Интернэт холболт алга», 0 requests"]
    D -- yes --> DB["Debounce 250 ms; NAV-003 request profile<br/>lang mn/en, bias D30 rounded to 3 decimals, query as typed"]
    DB --> R{"Response"}
    R -- "results" --> L["List «Хайлтын илэрц»"]
    R -- "empty" --> NR["«Илэрц олдсонгүй»"]
    R -- "5xx / timeout / network" --> UN["«Хайлт түр ажиллахгүй байна» + «Дахин оролдох»"]
    R -- "429" --> RL["«Түр хүлээгээд дахин оролдоно уу», «Дахин оролдох» disabled for Retry-After"]
    L -- "tap a result" --> P(["Within 500 ms: F4 route preview<br/>destination = result name"])
    UN -- "«Дахин оролдох»" --> DB
    S0 -- "Back / clear" --> M(["S1 map"])
```

### F2b. Long-press (AC 4)
```mermaid
flowchart TD
    LP["Long-press 600 ms, ≤ 10 dp movement"] --> C["Card «Сонгосон цэг»<br/>coordinates with 5 decimals, «Маршрут гаргах», «Хаах»<br/>candidate pin at the point; 0 reverse requests"]
    C -- "«Маршрут гаргах»" --> P(["F4 route preview, destination «Сонгосон цэг»"])
    C -- "«Хаах» / Back" --> M(["S1 map, pin removed"])
    C -- "another long-press" --> C
```

## F3. Location access (permission, precision, services, first fix) — AC 8–14

Entered from «Маршрут гаргах» (F2b), a search result (F2a → F4), or the my-location button (F1). The **pending action** is remembered and continues by itself when the problem is fixed.

```mermaid
flowchart TD
    E(["Location needed for a pending action"]) --> PG{"Permission state"}
    PG -- "never asked" --> RA["S4 rationale dialog<br/>«Байршлаа ашиглахыг зөвшөөрнө үү»<br/>«Үргэлжлүүлэх» / «Хаах» — AC 8"]
    RA -- "«Хаах»" --> X1(["Back to where the user was;<br/>destination kept; search and long-press still work"])
    RA -- "«Үргэлжлүүлэх»" --> OS["OS dialog: FINE + COARSE<br/>(never BACKGROUND) — AC 8"]
    OS -- "precise granted" --> SV
    OS -- "approximate only (Android 12+)" --> AP
    OS -- "denied (once or forever)" --> DN
    PG -- "granted precise" --> SV
    PG -- "granted approximate, my-location press (as built)" --> SV
    PG -- "granted approximate, other actions" --> AP["S4 message «Нарийвчилсан байршлыг зөвшөөрнө үү»<br/>«Тохиргоо нээх» / «Хаах»<br/>«Эхлэх» disabled, 0 route requests — AC 10"]
    PG -- "denied / don't ask again" --> DN["S4 message «Байршлын зөвшөөрөл олгоогүй байна»<br/>hint «Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу»<br/>«Тохиргоо нээх» / «Хаах»; OS dialog not shown again — AC 11"]
    AP -- "«Тохиргоо нээх»" --> APPSET["App settings page (system)"]
    DN -- "«Тохиргоо нээх»" --> APPSET
    APPSET -- "returns with precise granted" --> SV
    APPSET -- "returns unchanged" --> PG
    SV{"Location services on?"} -- no --> LS["S4 message «Байршил тогтоох үйлчилгээ унтарсан байна»<br/>«Тохиргоо нээх» (system location settings) / «Хаах» — AC 12"]
    LS -- "returns with location on" --> FX
    SV -- yes --> FX["Wait for a good fix<br/>(platform LocationManager GPS, or the platform fused provider on API 31+, no Google Play services: ADR-0009 §9, AC 14)<br/>my-location press: a showable fix ≤ 100 m instead, F12 — AC 76"]
    FX -- "good fix ≤ 10 s" --> OK(["Pending action continues within 2 s<br/>without another tap — AC 9, 11, 12"])
    FX -- "none in 10 s" --> NF["S4 message «Байршил тодорхойлж чадсангүй»<br/>«Дахин оролдох» / «Хаах»; 0 route requests — AC 9"]
    NF -- "«Дахин оролдох»" --> FX
```

## F4. Route preview and start — AC 5–7, 13, 15, 35, 38

```mermaid
flowchart TD
    O(["Destination chosen (F2)"]) --> PV["S3 preview sheet opens:<br/>«Миний байршил» → destination, «Машин» selected<br/>TextToSpeech starts initialising now (navigation-ux §4.6)"]
    PV --> LOC{"Fresh good fix ≤ 60 s?"}
    LOC -- no --> F3(["F3 location access"]) --> LOC
    LOC -- yes --> SAME{"Origin and destination ≤ 10 m?"}
    SAME -- yes --> SP["«Эхлэх цэг, очих газар ижил байна», 0 requests"]
    SAME -- no --> NET{"Online?"}
    NET -- no --> OFF["«Интернэт холболт алга», 0 requests<br/>back online → 1 request within 2 s"]
    NET -- yes --> REQ["1 POST /v1/route: costing auto/pedestrian,<br/>exclude_unpaved only if the switch is on (car),<br/>alternates 0, osrm, banner + voice instructions, km, mn-MN/en-US — AC 5<br/>loading row «Ачаалж байна…» after 300 ms"]
    REQ --> RES{"Response"}
    RES -- "200" --> OKR["Route line, markers, «13 мин · 4,6 км», «Хүрэх цаг 14:03»<br/>snap notice if > 500 m — AC 6<br/>«Эхлэх» enabled"]
    RES -- "400 NoRoute" --> NR["«Маршрут олдсонгүй» (+ avoid hint if on)"]
    RES -- "NoSegment / 171" --> OA["«Эхлэх цэг эсвэл очих газар үйлчилгээний хүрээнээс гадуур байна»"]
    RES -- "DistanceExceeded on «Явган»" --> TF["«Энэ зай явганаар эсвэл дугуйгаар хэт хол байна»"]
    RES -- "network / 5xx / 12 s" --> UN["«Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох»"]
    RES -- "429" --> RL["«Түр хүлээгээд дахин оролдоно уу»; 0 requests for Retry-After (5 s default)"]
    RES -- "other 400 / 413 / bad body" --> ER["«Алдаа гарлаа»"]
    OKR -- "mode tab or avoid switch changed" --> REQ
    UN -- "«Дахин оролдох»" --> REQ
    OKR -- "«Эхлэх»" --> PREC{"Precise location and fresh good fix?"}
    PREC -- no --> F3
    PREC -- yes --> FIRST{"Android 13+ and notification<br/>permission never asked?"}
    FIRST -- yes --> NOTIF["OS notification dialog shown over the guidance screen<br/>(guidance starts anyway) — AC 13"]
    FIRST -- no --> G
    NOTIF --> G(["Within 1 s: F5 guidance<br/>foreground service (type location), notification «Замчлал»,<br/>screen kept on, 0 extra route requests — AC 15, 18"])
    PV -- "«Хаах» / Back" --> M(["S1 map (destination pin stays for a search result,<br/>card returns for a long-press point)"])
```
«Эхлэх» is disabled in every state without a route (AC 7).

## F5. Guidance: top-level states — AC 15–25, 31, 41–57

```mermaid
stateDiagram-v2
    [*] --> OnRoute: «Эхлэх» (depart prompt ≤ 2 s, AC 35)
    OnRoute --> OnRoute: fix → banner distance, progress, camera, voice triggers (navigation-ux §4.2)
    OnRoute --> OffRoute: good fixes > 50 m from the route (AC 41)
    OffRoute --> OnRoute: new route active (AC 45) or back within 50 m (AC 46)
    OnRoute --> GpsLost: no good fix for 10 s (AC 51)
    OffRoute --> GpsLost: no good fix for 10 s (reroute paused)
    GpsLost --> OnRoute: good fix on the route (AC 52)
    GpsLost --> OffRoute: good fix > 50 m away (AC 52)
    OnRoute --> Arrived: ≤ 30 m from the route end or past it (AC 55)
    Arrived --> [*]: «Хаах» → S1 map
    OnRoute --> Ended: «Дуусгах» (screen or notification), swipe-away
    OffRoute --> Ended: «Дуусгах», swipe-away
    GpsLost --> Ended: «Дуусгах», swipe-away
    Ended --> [*]: within 2 s (5 s after swipe-away) — service stopped,<br/>notification removed, 0 fixes, audio stopped → S1 map without route (AC 19–20)
```

Independent of these states and allowed in all of them: mute / unmute (AC 37), orientation toggle (AC 24), map gestures and recenter (AC 25), theme / language / rotation (AC 59, 60, 64), network loss on the route (F8), Home / screen off (guidance continues, AC 17), Back (= Home: the task moves to the background, guidance continues; nothing destructive on Back).

## F6. Off-route episode and reroute pacing — AC 41–50

```mermaid
stateDiagram-v2
    [*] --> Detected: good fix (≤ 25 m) > 50 m from the route
    Detected --> Requesting: within 1 s — banner «Маршрутыг дахин тооцоолж байна»,<br/>voice «Та маршрутаас гарлаа» once (or chime)
    Requesting --> NewRoute: 200 → route replaced ≤ 500 ms, catch-up prompt
    Requesting --> Wait429: 429 Retry-After N (5 s if missing/invalid)
    Requesting --> Backoff: network / 502 / 503 / 504 / 12 s timeout
    Requesting --> NoRouteWait: 400 NoRoute / NoSegment / 171 / other 400 / 413 / bad 200
    Requesting --> Offline: no validated network
    Wait429 --> Requesting: after N s, still off-route (1 request, no tap)
    Backoff --> Requesting: after 5, 10, 20, 30, 30 … s
    NoRouteWait --> Requesting: moved ≥ 200 m AND ≥ 30 s since the failed request
    Offline --> Requesting: network back (≤ 3 s, exactly 1 request)
    Requesting --> BackOnRoute: back within 50 m before the response
    Wait429 --> BackOnRoute: back within 50 m
    Backoff --> BackOnRoute: back within 50 m
    NoRouteWait --> BackOnRoute: back within 50 m
    Offline --> BackOnRoute: back within 50 m
    NewRoute --> [*]
    BackOnRoute --> [*]: banner shows an instruction ≤ 1 s, 0 extra requests
```

Secondary line under «Маршрутыг дахин тооцоолж байна»: Wait429 → none; Backoff → «Маршрутын үйлчилгээ түр ажиллахгүй байна»; NoRouteWait → «Маршрут олдсонгүй» (or «Алдаа гарлаа» for other 400 / 413 / bad 200); Offline → «Интернэт холболт алга». **Global pacer (all states, AC 44):** at most 1 request in flight, consecutive requests ≥ 5 s apart, ≤ 6 per rolling 60 s; a state's own wait is extended to meet the pacer. Reroute body (AC 43): current position, `heading` = course over ground when speed ≥ 2 m/s, original destination, same costing, options, language and units, `alternates: 0`.

## F7. GPS loss and tunnel — AC 51–53

```mermaid
sequenceDiagram
    participant L as Location (1 Hz)
    participant G as Guidance
    participant UI as Screen
    participant V as Voice
    L->>G: last good fix t0
    Note over L,G: no good fix for 10 s (tunnel, underpass)
    G->>UI: t0+10 s (≤ 1 s): status «GPS дохио тасарлаа», puck stale grey,<br/>banner distance and progress frozen (dimmed)
    G->>V: «GPS дохио тасарлаа» once (or chime)
    Note over G: no manoeuvre prompts, 0 reroute requests,<br/>guidance never ends by itself
    L->>G: first good fix (e.g. 400 m further on, G3)
    G->>UI: «GPS дохио сэргэлээ» for 3 s, puck blue,<br/>banner = correct next manoeuvre ≤ 2 s
    G->>V: «GPS дохио сэргэлээ» once (or chime), then one catch-up prompt
    Note over G: manoeuvres passed during the gap are not announced.<br/>More than 50 m from the route → F6
```

## F8. Network loss on the route — AC 54

```mermaid
flowchart LR
    A["On route, network drops"] --> B["Within 2 s: status «Интернэт холболт алга» (non-blocking)<br/>banner, voice, progress, arrival keep working from the downloaded route<br/>loaded tiles stay; missing tiles show the map background; 0 requests"]
    B --> C{"Network back"}
    C --> D["Indicator gone within 2 s"]
    B --> E{"Off-route meanwhile?"}
    E --> F["F6 Offline state: the banner secondary line says it;<br/>the status indicator is hidden so the text is not shown twice"]
```

## F9. Voice decision and the D23 fallback — AC 36–39

```mermaid
flowchart TD
    I(["Route preview opens"]) --> INIT["TextToSpeech init (3 s limit)"]
    INIT --> LANG{"UI language"}
    LANG -- mn --> MNV{"mn / mn-MN available?<br/>(not MISSING_DATA / NOT_SUPPORTED)"}
    LANG -- en --> ENV{"en voice available?"}
    MNV -- yes --> SPEAK["Voice mode: prompts spoken (navigation-ux §4.1–4.5)"]
    ENV -- yes --> SPEAK
    MNV -- "no / init > 3 s" --> FB["Fallback: every prompt = chime ≤ 1 s<br/>never Mongolian through another voice"]
    ENV -- "no / init > 3 s" --> FBE["Fallback: chime, no notice (BA request pending)"]
    FB --> NOTE["At guidance start: notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна.<br/>Заавар зөвхөн дэлгэцэнд харагдана.» once per session, 8 s — AC 39"]
    SPEAK -- "TTS error during the session" --> FB
    SPEAK --> FOCUS{"Audio focus granted?"}
    FOCUS -- no --> SKIP["Prompt skipped, not queued — AC 36"]
    FOCUS -- yes --> PLAY["Duck others, speak, release ≤ 1 s"]
    LANG -. "language switched (AC 60)" .-> INIT
```

## F10. Arrival — AC 55–57

```mermaid
flowchart TD
    A["Approaching: «{n} метрт очих газартаа хүрнэ» at the main trigger (if the schedule has one)"] --> B{"≤ 30 m from the route end or past it"}
    B --> C["Banner arrival variant «Та очих газартаа ирлээ» (or side variant)<br/>spoken once (or chime); exactly 1 arrival message, also while stationary (G4)"]
    C --> D["Progress panel → S6 arrival panel: destination name + «Хаах»"]
    C --> E["≤ 10 s: service stopped, location updates stopped,<br/>notification removed, screen-on cleared; 0 prompts, 0 reroutes"]
    D -- "«Хаах» / Back" --> F(["S1 map without route; bearing eases to north"])
```

## F11. Settings, theme, language and configuration changes — AC 58–64

```mermaid
flowchart TD
    S(["«Тохиргоо» on S1 or S5"]) --> SH["S7 settings sheet:<br/>theme «Өдрийн горим» / «Шөнийн горим» / «Автомат»<br/>«Хэл»: «Монгол» / «English»"]
    SH -- "theme changed (or system dark mode changes under «Автомат»)" --> TH["Map flavor + UI colours switch ≤ 1 s<br/>route, puck, banner, progress stay; 0 requests, 0 repeated prompts — AC 59"]
    SH -- "language changed" --> LG["Labels and banner texts switch ≤ 1 s, 0 requests<br/>current utterance stops; next prompt in the new language, voice re-evaluated (F9) — AC 60"]
    R(["Rotation / other configuration change"]) --> RC["Guidance continues: same step, same queue,<br/>0 requests, 0 repeated prompts, banner visible — AC 64"]
    TH --> K["Choice remembered across restarts"]
    LG --> K
```

## F12. Browse location dot (S1, S2, S3) — AC 74–79 (section N, 2026-10-04)

The dot shows the **shown position**, not every fix (NAV-005 Terms). The numbers are the PO's starting defaults (D176). Raw fixes still feed the route origin, guidance, the typing lock, the «Автомат» theme and the NAV-018 device start (AC 78, D177). The demo build never shows a dot (NAV-019 AC 13). Looks: [`map-style.md` §7.8](../map-style.md); states table: screen spec › S1 › Location dot on the browse map.

### F12a. My-location press without a shown position (D172)

```mermaid
flowchart TD
    P(["my-location button pressed (S1 or S2)"]) --> C{"F3 checks pass?<br/>permission, services — AC 8, 11, 12"}
    C -- no --> F3M["F3 message, unchanged"]
    C -- yes --> S{"Shown position?"}
    S -- "yes, normal or stale" --> CEN["Camera centres on the dot and follows it<br/>no message, also when stale — AC 76"]
    S -- no --> W["Waiting: no dot; button in the following colours<br/>with icon location_searching; no text"]
    W -- "showable fix within 10 s" --> D["Dot and circle appear;<br/>camera centres and follows — AC 74"]
    W -- "map gesture" --> X["Wait ends, button normal, no card later;<br/>a later fix shows the dot, camera not moved"]
    W -- "10 s, no showable fix" --> G["S1 R2 card «Байршил тодорхойлж чадсангүй»<br/>«Дахин оролдох» / «Хаах»; 0 route requests — AC 76"]
    G -- "«Дахин оролдох» or my-location press" --> W
    G -- "«Хаах»" --> X
    G -- "showable fix arrives" --> GD["Card closes within 2 s; dot appears;<br/>camera centres and follows (pending action continues, as F3)"]
```

With approximate permission only, the press passes F3 as built and coarse fixes (usually worse than 100 m) end in the card (NAV-005 Open question 1, BA).

### F12b. What the dot does as fixes arrive (AC 75–77)

```mermaid
stateDiagram-v2
    [*] --> NoDot
    NoDot : No dot, no circle (no showable fix yet)
    NoDot --> Shown : first showable fix
    state Shown {
        Moving : Moving, dot and camera follow each fix within 1 s
        Holding : Holding, dot and camera move 0 m
        [*] --> Moving
        Moving --> Holding : speed below 0.5 m/s, or no speed and d within r
        Holding --> Moving : speed 1.0 m/s or more, or 2nd fix in a row with d beyond r
    }
    Shown --> Pending : jump, more than max(50 m, 2 x acc) away and over 50 m/s
    Pending : Jump pending, dot 0 m
    Pending --> Shown : next fix within 5 s confirms (dot moves) or not (fix discarded)
    Shown --> Stale : 10 s without a showable fix
    Stale : Stale, hollow grey ring and dashed circle, no message
    Stale --> Shown : next showable fix, normal look within 2 s
    Shown --> Guidance : «Эхлэх»
    Stale --> Guidance : «Эхлэх»
    Guidance : Guidance or arrival, no dot, puck instead
    Guidance --> Shown : guidance ends, next showable fix
```

*d* = distance from the new fix to the shown position, *r* = max(the new fix's accuracy, 10 m). Fixes with unknown accuracy or worse than 100 m are ignored in every state: they move nothing and do not reset the 10 s timer (AC 76). Pending and discarded jumps do not count as "in a row" (AC 75). The circle radius is always the accuracy of the fix the dot shows (AC 74).

## F13. Offline with an installed pack: preview, network loss and reroute — AC 54, 65 (change 7a, 2026-10-05)

Applies per file: it needs a **usable** `routing.tar` (installed, accepted by the app, not switched off after 3 engine deaths in 10 minutes). Without one, F4, F6 and F8 are exactly as drawn above (NAV-021 AC 13, 30). Rule X1 (screen spec › Offline with an installed pack): the network message says *the phone has no network*; the indicator OF24 says *this route came from the phone*.

```mermaid
flowchart TD
    Q(["Preview request (F4), reroute (F6) or NAV-012 restore reroute"]) --> U{"Usable routing file?"}
    U -- no --> OLD["F4 / F6 / F8 as written: «Интернэт холболт алга», 0 requests"]
    U -- yes --> N{"Validated network?"}
    N -- no --> DEV["On-device engine answers at once; 0 HTTP requests<br/>(preview ≤ 1.5 s cold; reroute ≤ 2.0 s mid-range)"]
    N -- yes --> ON["Gateway request first; loading row «Ачаалж байна…» after 300 ms (preview)<br/>or banner «Маршрутыг дахин тооцоолж байна» (reroute)"]
    ON --> H{"Headers within 3.0 s, no 502 / 503 / 504 / 429, no connection failure?"}
    H -- yes --> GW["Gateway answer handled as today<br/>authoritative answers (NoRoute, outside, too far, other 400) shown, never retried on the device"]
    H -- no --> DEV
    DEV --> R{"Device result"}
    R -- "route" --> RT["Route shown / replaces the old route in full<br/>chip «Офлайн» on S3, icon in the S5 progress panel<br/>no «Интернэт холболт алга» on the banner, no 429 line"]
    R -- "NoRoute / outside area / too far" --> AUT["The existing texts, no indicator"]
    R -- "engine error, 10 s, process death" --> BOTH["Both sources failed: «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох» (S3)<br/>reroute: back-off 5, 10, 20, 30 … s (AC 48); old route still followed"]
    GW --> NI["No indicator (an earlier icon goes away)"]
```

```mermaid
flowchart LR
    A["On route, network drops<br/>(usable routing file)"] --> B["Within 2 s: status «Интернэт холболт алга» (unchanged, AC 54)<br/>engine bound ≤ 5 s; 0 requests while on the route"]
    B --> C{"Off-route meanwhile?"}
    C -- yes --> D["Banner «Маршрутыг дахин тооцоолж байна» (no secondary line)<br/>device route ≤ 2.0 s → icon OF24 appears, status message stays"]
    C -- no --> E["Network returns: status gone ≤ 2 s; 0 requests; the next off-route uses online first again"]
    D --> E
```

Network host (AC 65 as amended, AC 86): pack requests go to the configured packs base URL (by default the gateway host), everything else to the gateway base URL; no other host. The licences screen sends nothing ([`screens/android-licences.md`](../screens/android-licences.md)).

## Error-path summary (every screen has offline, GPS-lost and no-result states)
| Screen | Offline | GPS lost / no location | No result |
|---|---|---|---|
| S1 map | Message «Интернэт холболт алга»; loaded tiles stay | My-location button → F3 messages; no showable fix: no dot, «Байршил тодорхойлж чадсангүй» 10 s after a press (F12a); fixes stop: stale hollow grey dot, no message (F12b) | — |
| S2 search / card | List row «Интернэт холболт алга», 0 requests (**with a usable search file: on-device results with the chip, NAV-011 flow F6**) | not needed (search works without location; bias falls back to the map centre, D30) | «Илэрц олдсонгүй» |
| S3 preview | «Интернэт холболт алга», 0 requests, auto-request when back (**with a usable routing file: a route from the device with the chip, F13**) | F3 messages in the result region; «Эхлэх» disabled | «Маршрут олдсонгүй», N9, N11 |
| S5 guidance | Status indicator (F8); off-route → banner secondary line (F6); **with a usable routing file the reroute is answered on the device, no secondary line, icon OF24 (F13)** | F7 | reroute «Маршрут олдсонгүй» (F6) |
| S6 arrival | nothing needed (no network use) | nothing needed (guidance has ended) | — |
