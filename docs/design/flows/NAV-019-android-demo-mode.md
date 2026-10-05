# Flow NAV-019: Android demo build (install → route picker → real preview → simulated replay through the real guidance → arrival → picker)

- **Story:** [NAV-019](../../requirements/stories/NAV-019-android-demo-mode.md). AC referenced per step. PO decisions D154 (option B) and D155 (light process). The orchestrator defaults **R1–R7 are proposed defaults, PO to confirm** (story Open questions 1–7); branches that depend on an open answer are marked **[OQ n]**.
- **Screens:** [`screens/android-demo-picker.md`](../screens/android-demo-picker.md) (P1 picker, P2 preview delta, P3 guidance delta with the demo row, P4 arrival). It is a **delta** on the NAV-005, NAV-011, NAV-012 and NAV-018 screen specs; everything not named here behaves as there.
- **Base flows (reused unchanged):** [`NAV-005-android-navigation.md`](NAV-005-android-navigation.md) (guidance, voice, arrival), [`NAV-011-android-route-preview-search-parity.md`](NAV-011-android-route-preview-search-parity.md) (preview sheet, typed coordinates), [`NAV-012-android-background-lock-screen.md`](NAV-012-android-background-lock-screen.md) (notification, lock screen, swipe-away, audio output, calls, «Автомат»), [`NAV-018-android-origin-turn-list.md`](NAV-018-android-origin-turn-list.md) (points block, point editor, turn list). Web counterpart: [`NAV-017-web-demo-mode.md`](NAV-017-web-demo-mode.md).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) §1–§10, §12 and **§13 (demo replay on Android, new)**.
- **Architecture:** [ADR-0016](../../architecture/adr/0016-android-demo-mode.md): `ReplayVariant`, `ReplayLocationSource`, `ReplayClock`, `DemoNetworkBlock`, `DemoTiles` (one-time copy, `pmtiles://file://`). No API operation (0 requests, AC 33).
- **Prototype:** [`prototypes/NAV-019-android-demo.html`](../prototypes/NAV-019-android-demo.html), checked by `prototypes/check-layout-nav019.mjs`.

Copy is quoted by its `mn` value in «»; every string is a glossary term (W1 and W2 are `needs native review`). Place names (R1–R3 ends) are manifest data, not UI strings. "Replay clock" = time since «Эхлэх» without paused time (story › Terms).

## F0. Getting the demo build onto the PO's phone (people and files, no app UI) — AC 1–5, 41, 43

No APK, tile archive, keystore, hostname or property value is ever committed or published (D17, D35). Placeholders only: `<path-to>.pmtiles`.

```mermaid
flowchart TD
    A["Developer sets nav.demoTilesFile=#lt;path-to#gt;.pmtiles<br/>(uncommitted gateway.local.properties or -P) [OQ3]"] --> B{"./gradlew :app:assembleDemo"}
    B -- "neither tile property set" --> X["Build fails with one message naming both properties<br/>and the README — AC 3 (debug, release, tests unaffected)"]
    B -- "manifest / route / track invalid" --> X2["Build fails (ADR-0016 §8 validation)"]
    B -- ok --> C["APK: applicationId + .demo, debug key, not debuggable,<br/>launcher label «Туршилтын горим», icon on the badge colour — AC 1"]
    C --> D["Direct file transfer to the PO only (USB, cable, local share)<br/>never a website, store or download link — AC 43, D17 [OQ1]"]
    D --> E["PO allows installs from that source once (Android system dialog, not our UI)"]
    E --> F(["Installed next to the debug build: two icons, two labels,<br/>separate data — AC 2"])
```

## F1. Open the demo build — AC 6, 7, 30, 35, 36

```mermaid
flowchart TD
    A(["PO taps the «Туршилтын горим» launcher icon"]) --> R{"Interrupted replay from a killed process?"}
    R -- "yes or no: nothing is restored in the demo build (AC 30) [OQ8]" --> P1
    P1["Within 2 s: P1 picker over the map — «Туршилтын горим», «Маршрут сонгох»,<br/>E1–E3 (names, mode, distance, duration); Mongolian on first launch (D59);<br/>«© OpenStreetMap contributors» — AC 6, 37. No location or notification prompt here"]
    P1 --> T{"Tiles state (ADR-0016 §9)"}
    T -- "first launch of this version: copying" --> L["PM pill «Ачаалж байна…» after 300 ms; map background meanwhile;<br/>list usable — AC 35 (target ≤ 30 s)"]
    L --> T
    T -- "Ready" --> M["Basemap from the bundled archive, day/night flavor, name:mn labels, 0 requests — AC 35"]
    T -- "Failed (missing, short, bad header)" --> TF["Within 5 s: PM card «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох»;<br/>list still shows E1–E3 and opens previews — AC 36"]
    TF -- "«Дахин оролдох»: re-run the copy" --> T
    P1 --> S["Gear «Тохиргоо» (1 tap) → S7: «Хэл», theme options,<br/>«Батарейн хязгаарлалт» — as the normal app — AC 7"]
    S -- "Back" --> P1
    P1 -- "system Back" --> H(["App closes (home screen behaviour)"])
```

## F2. Pick a route and check the preview — AC 8–11, 17, 31

```mermaid
flowchart TD
    P0(["P1 picker"]) --> TAP["Tap entry E1, E2 or E3"]
    TAP --> PARSE{"Route JSON and GPX track from the APK assets<br/>read and parsed (normal preview path, ≤ 1 s)"}
    PARSE -- "unreadable / unparsable" --> ERR["Within 1 s: «Алдаа гарлаа» under that entry; no preview;<br/>other entries keep working — AC 8"]
    ERR -- "tap another entry" --> TAP
    PARSE -- ok --> P2["Within 1 s: P2 preview (NAV-018 sheet) with 0 route requests:<br/>badge pill «Туршилтын горим», manifest names in the start and destination fields,<br/>recorded mode tab, «N мин · N км», «Хүрэх цаг HH:MM», route line and markers,<br/>«Маршрутын заавар» in the expanded sheet; «Эхлэх» ENABLED, no O1 — AC 9, 10, 17"]
    P2 --> CTRL{"User changes something that needs a new route:<br/>mode tab, avoid switch, swap, a field (point editor),<br/>long-press card «Эхлэх цэг болгох» / «Очих газар болгох»"}
    CTRL --> F7(["F7 demo-mode states (0 requests); «Эхлэх» disabled — AC 11, 31"])
    F7 -- "✕ or system Back" --> P0
    P2 -- "✕ or system Back (≤ 1 s)" --> P0
    P0 -. "same entry again: recorded route restored — AC 11" .-> TAP
    P2 -- "«Эхлэх»" --> F3(["F3 start"])
```

## F3. Start the replay: permissions, service, first prompt — AC 12, 13, 18, 19, 26

```mermaid
sequenceDiagram
    actor U as PO
    participant P as P2 preview
    participant OS as Android dialogs
    participant S as Guidance service (NAV-005, unchanged)
    participant R as ReplayLocationSource
    participant G as P3 guidance
    U->>P: «Эхлэх» (disabled at once: a double tap starts one replay)
    alt OQ6 (b), recommended: location permission missing
        P->>OS: NAV-005 S4 rationale «Байршлаа ашиглахыг зөвшөөрнө үү» → OS location dialog
        OS-->>P: granted → continue
        OS-->>P: denied → NAV-005 denied message in the summary region with «Тохиргоо нээх»; no replay
    else OQ6 (a): no location prompt (specialUse service type in the demo build)
        Note over P,OS: nothing is asked
    end
    P->>S: start the foreground service, channel «Замчлал», header «Туршилтын горим» — AC 12, 27
    S->>G: within 1 s P3: banner, demo row (badge + «Түр зогсоох»), progress panel — AC 12, 17
    R->>S: fix 0 = first track point, then fix i at its track time (± 200 ms); 0 platform location requests — AC 12, 13
    S->>G: depart prompt or one chime within 2 s; A1 for 8 s if no Mongolian voice — AC 18, 19
    opt Android 13+, first «Эхлэх» in the demo build
        S->>OS: notification permission dialog over P3 (once) — AC 26
        OS-->>S: denied → the replay continues without the notification
    end
```

## F4. Replay states, including pause — AC 12–17, 20–22, 24, 25, 32

```mermaid
stateDiagram-v2
    [*] --> Replaying: «Эхлэх»
    Replaying --> Replaying: fix i at its track time → snapped puck, banner, progress, camera,<br/>prompts per navigation-ux §4 (same engine as real fixes) — AC 13–15
    Replaying --> Free: map gesture (recenter shows ≤ 300 ms) — AC 15
    Free --> Replaying: «Байршил руу буцах» or 15 s
    Replaying --> Paused: «Түр зогсоох» (≤ 1 s) — AC 22
    Free --> PausedFree: «Түр зогсоох»
    Paused --> Replaying: «Үргэлжлүүлэх» (≤ 1 s, same track time, 0 repeated prompts, no depart prompt)
    PausedFree --> Free: «Үргэлжлүүлэх»
    Paused --> PausedFree: map gesture
    PausedFree --> Paused: «Байршил руу буцах» or 15 s
    Replaying --> Deviation: engine detects off-route (not expected on R1–R3)
    Deviation --> Replaying: back within 50 m → normal banner ≤ 2 s — AC 32
    Replaying --> Arrived: snapped position ≤ 30 m from the end or past it — AC 24
    Free --> Arrived: same
    Replaying --> Ended: «Дуусгах» (screen, lock screen, notification) — AC 23
    Free --> Ended: «Дуусгах»
    Paused --> Ended: «Дуусгах»
    PausedFree --> Ended: «Дуусгах»
    Deviation --> Ended: «Дуусгах»
    Replaying --> Ended: last fix delivered without arrival (≤ 2 s) — AC 25
    Arrived --> Picker: «Хаах» — AC 24
    Ended --> Picker: ≤ 2 s, no entry marked
    Picker --> [*]
```
- **Paused** (navigation-ux §13.2): replay clock frozen, puck still, utterance or chime stopped, 0 prompts, remaining distance and «Хүрэх цаг» frozen, **no «GPS дохио тасарлаа»** however long; the button reads «Үргэлжлүүлэх» (filled). Map gestures, recenter, voice button, settings, language and theme switches work. The notification keeps its last content; the foreground service and the lock-screen view stay. A call during a pause changes nothing.
- **Deviation** (AC 32): recalculating banner with «Маршрутын үйлчилгээ түр ажиллахгүй байна» (or «Интернэт холболт алга» without a validated network); the replay keeps following the track; reroute attempts are blocked (0 requests, ADR-0016 §10).
- **Allowed in every replay state without a state change:** mute (AC 20), language switch (AC 21), theme switch and rotation (NAV-005 AC 59, 64), screen off and on (F5).
- **Not offered:** speed choice (1× only), seek, GPS loss **[OQ4]**.

## F5. Background, lock screen and interruptions — AC 16, 27–30

```mermaid
flowchart TD
    S(["Replay running (P3)"]) --> OFF["Screen off / Home / other app / swipe-away from Recents"]
    OFF --> C["Replay continues in the foreground service: fixes at the track rate (no gap > 2 s),<br/>prompts play, notification updates (NAV-012 §12.1) — AC 16.<br/>Unlike NAV-017: nothing pauses when the UI is hidden"]
    C --> ON["Screen on"]
    ON --> L1["P3 over the lock screen with the demo row (NAV-012 L1);<br/>«Түр зогсоох» and «Дуусгах» usable without unlocking — AC 28"]
    S --> BT["Bluetooth / wired / USB output connects or disconnects"] --> BT2["NAV-012 §12.5 unchanged — AC 29"]
    S --> CALL["Phone call (cellular or VoIP)"] --> CALL2["0 prompts during the call; one catch-up after it (NAV-012 §12.6);<br/>the replay keeps running during the call — AC 29"]
    S --> KILL["OEM battery saver / crash / force stop ends the process"]
    KILL --> K2["Notification gone; no restore record, no N2 notification [OQ8]"]
    K2 --> K3(["Next launch: P1 within 2 s, nothing restored — AC 30;<br/>the PO notes it in checklist (i)"])
```

## F6. End and arrival — AC 23–25

```mermaid
flowchart TD
    A["Approaching prompt «{n} метрт очих газартаа хүрнэ» when the schedule has one"] --> B{"Snapped simulated position ≤ 30 m from the route end, or past it"}
    B --> C["Banner «Та очих газартаа ирлээ» / «Таны очих газар баруун талд байна» /<br/>«Таны очих газар зүүн талд байна», spoken once or one chime; 0 prompts after"]
    C --> D["P4: arrival panel with the destination name + «Хаах»; demo row keeps the badge only;<br/>service stops and notification removed within 10 s — AC 24"]
    D -- "«Хаах»" --> P1(["P1 picker, no entry marked"])
    E["«Дуусгах» (screen, lock screen, notification; also while paused)"] --> E2["Within 2 s: service stops, notification removed, utterance stops, 0 requests — AC 23"]
    E2 --> P1
    F["Last track point delivered, no arrival detected (data defect on R1–R3)"] --> E2
```

## F7. Demo-mode states for anything that would need the network — AC 11, 31–34

Every request is blocked inside the app before DNS (ADR-0016 §7); the existing outcome code shows the existing states. **0 requests**, also for «Дахин оролдох».

```mermaid
flowchart TD
    Q(["P2 control or point editor action that needs a request"]) --> NET{"Validated network?"}
    NET -- no --> OFF["«Интернэт холболт алга»"]
    NET -- yes --> KIND{"What was asked?"}
    KIND -- "new route: mode tab, avoid switch, swap,<br/>a new start / destination, card buttons" --> RU["Summary region: «Маршрутын үйлчилгээ түр ажиллахгүй байна» + «Дахин оролдох»;<br/>«Эхлэх» disabled"]
    KIND -- "search in the point editor" --> SU["Result list: «Хайлт түр ажиллахгүй байна» + «Дахин оролдох»"]
    KIND -- "nearest place on the long-press card" --> RV["Card nearest-place area: «Хайлт түр ажиллахгүй байна»"]
    TYPED["Typed coordinate pair in the point editor"] --> SEL["«Сонгосон цэг» option shown (no request, NAV-011 AC 7)"] --> RU
    RU -- "«Дахин оролдох»" --> RU
    SU -- "«Дахин оролдох»" --> SU
    RU -- "✕ / Back → P1 → same entry" --> REC(["Recorded route again — AC 11"])
```
- **Not reachable in the demo build** (hidden, screen spec › Hidden or changed): the S1 search bar and S1 long-press card (P1 replaces S1), the my-location button, the «Миний байршил» option in the start editor.
- **During the replay:** a detected deviation shows the reroute banner of F4 (AC 32). The S5 offline status «Интернэт холболт алга» is **not** shown when the tiles are bundled (screen spec Design note 4, Open question U1); with `nav.demoTilesUrl` it shows as NAV-005 AC 54.
- **Airplane mode** (AC 34): F1–F6 work unchanged with bundled tiles; only F7 answers «Интернэт холболт алга».

## Error-path summary (every screen has offline, GPS-lost and no-result states)
| Screen | Offline | GPS lost / no location | No result / data error |
|---|---|---|---|
| P1 picker | nothing to show with bundled tiles; with `nav.demoTilesUrl` the NAV-002 offline message in PM | not applicable: P1 never uses location | the list is the packaged manifest (always R1–R3); a broken entry shows «Алдаа гарлаа» under it (AC 8); tiles failing show «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» (AC 36) |
| P2 preview | requests that would need the network show «Интернэт холболт алга» (F7) | the preview needs no device fix; [OQ6 (b)] location permission denied at «Эхлэх» shows the NAV-005 denied message | changed inputs show «Маршрутын үйлчилгээ түр ажиллахгүй байна» (F7); search shows «Хайлт түр ажиллахгүй байна» |
| P3 guidance | no offline status with bundled tiles; replay, voice, notification and arrival work in airplane mode (AC 34) | does not occur: the position is simulated; pause never produces «GPS дохио тасарлаа» (AC 22) | does not occur: a track that ends without arrival behaves as «Дуусгах» (AC 25); a deviation shows the reroute-unavailable banner (AC 32) |
| P4 arrival | nothing needed | not applicable | — |
| Build / install | — | — | missing tile property: the demo build fails with one clear message (AC 3); install problems are the system's own dialogs |
