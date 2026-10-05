# Flow NAV-017: web demo mode (public demo folder with noindex → route picker → simulated guidance → arrival)

- **Story:** [NAV-017](../../requirements/stories/NAV-017-web-demo-mode-replay.md). AC referenced per step. PO decisions D71–D75 (2026-10-01); the demo folder is **public without a password** since D107 (2026-10-03), and D17 does not cover this demo (D116, 2026-10-04), so F0 and F1 have no password step.
- **Screens:** [`screens/NAV-017-web-demo-mode.md`](../screens/NAV-017-web-demo-mode.md) (D1 route picker, D2 guidance, D3 arrival; the NAV-002 states that still apply).
- **Rules:** [`navigation-ux.md`](../navigation-ux.md) §2–4, §7–8 and **§11 (web demo-mode replay, new in v0.5)**. Map layers: [`map-style.md` §7.5](../map-style.md).
- **Prototype:** [`prototypes/NAV-017-demo-mode.html`](../prototypes/NAV-017-demo-mode.html), checked by `prototypes/check-layout-nav017.mjs`.
- **Architecture:** no API operation except the basemap archive (`getBasemapPmtiles`). The architect decides how the demo-mode build, the sub-folder base, where the tiles are read from and HTTP Range on the host (story R6, request 1; no Basic auth since D107) and the iOS speech unlock (request 2) work. The flows show what the user sees and hears.

Copy is quoted by its `mn` value in «»; every string is a glossary term; keys are in the screen spec › Copy. "Replay clock" = time since «Эхлэх» without paused time (story › Terms). Place names (P1 …) are manifest data, not UI strings.

## F0. Getting the build onto the PO's host (people and files, no app UI)

The hostname is **never** written in the repo, an issue or a document, and the repo holds no password or `.htpasswd` file (D35; the D74 rule kept by D107; AC 6). Placeholders: `<demo-host>`, `<demo-folder>`.

```mermaid
flowchart TD
    B["Developer or PO runs the demo-mode build<br/>(npm run build:demo-mode → its own output folder) — AC 1"] --> C{"Output contains .htaccess,<br/>.htpasswd or other server config,<br/>or index.html lacks the noindex meta?"}
    C -- yes --> X["Build check fails: never upload — AC 3"]
    C -- no --> U["PO uploads the output CONTENTS into<br/>#lt;web root#gt;/#lt;demo-folder#gt;/ (hPanel File Manager)<br/>public folder, no password (D107, D116) — AC 2, 5"]
    U --> K1{"Request to https://#lt;demo-host#gt;/#lt;demo-folder#gt;/<br/>without credentials"}
    K1 -- "200 and the page has the noindex meta" --> K3{"Tile archive Range request"}
    K1 -- "anything else (404, 401/403, 200 without noindex)" --> FIX["Stop. Upload the demo-mode build again<br/>(or remove a leftover folder setting) before sharing the link — AC 3, 5"]
    K3 -- "206, Content-Range, no Content-Encoding" --> OK(["PO shares the link with the people the PO chooses;<br/>never linked from the public site (AC 7). Anyone with the URL<br/>can open it: badge + noindex mark it as a test (D107, D116) — AC 5, 49"])
    K3 -- "200 / 416" --> TR["Tiles do not load over Range (R6):<br/>architect's fallback per web/README.md"]
    FIX --> U
    OK -. "public static build re-uploaded later" .-> W["README warning: the public upload must not delete #lt;demo-folder#gt;;<br/>repeat the 200 + noindex and 206 checks after every upload — AC 5"]
```

## F1. Open the demo on the iPhone — AC 8, 11, 14, 36

```mermaid
flowchart TD
    A(["PO opens https://#lt;demo-host#gt;/#lt;demo-folder#gt;/ in Safari"]) --> L["Page loads from the sub-folder (relative URLs, AC 2), no password prompt<br/>(public folder, D107, D116; AC 8); robots noindex (AC 3); language and theme from storage or defaults<br/>(Mongolian, day; D12) — no location prompt, Geolocation never called"]
    L --> T{"Map ready?<br/>(NAV-002 rules)"}
    T -- "> 300 ms" --> LD["NAV-002 loading pill «Ачаалж байна…»"] --> T
    T -- "tiles fail at start" --> TC["NAV-002 blocking card «Газрын зургийг ачаалж чадсангүй»<br/>+ «Дахин оролдох»; the picker waits"]
    TC -- "«Дахин оролдох» succeeds" --> T
    T -- "offline at start" --> OC["NAV-002 blocking card «Интернэт холболт алга»;<br/>map loads by itself when back online"] --> T
    T -- "first idle" --> D1["Within 1 s: D1 route picker «Туршилтын горим»<br/>list «Маршрут сонгох» with R1, R2, R3 (names, mode, distance, duration)<br/>«Эхлэх» disabled; «© OpenStreetMap contributors» — AC 8"]
    D1 --> V0["Voice check starts in the background (F5); nothing is shown yet — AC 28"]
    D1 --> NC["NAV-002 controls: language, theme, zoom, compass, attribution work.<br/>No search field, no «Маршрут гаргах», no long-press card,<br/>no my-location button (screen spec Design notes 1–2) — AC 11, 14"]
```

## F2. Pick a route — AC 8–10, 42

```mermaid
flowchart TD
    P0(["D1 picker, no entry selected"]) --> SEL["User selects R1, R2 or R3 (tap, or Arrow keys in the radio group)"]
    SEL --> CACHE{"Route data of that entry<br/>already loaded in this page session?"}
    CACHE -- yes --> DRAW
    CACHE -- no --> NET{"Online?"}
    NET -- no --> OFF["Footer row «Интернэт холболт алга»; «Эхлэх» disabled<br/>NAV-002 offline banner in R2. Back online → 1 load by itself"]
    OFF -. "online event" .-> LOAD
    NET -- yes --> LOAD["1 GET of the entry's data file from the page origin only — AC 42<br/>> 300 ms → footer row «Ачаалж байна…» — AC 10"]
    LOAD --> RES{"Result"}
    RES -- "parsed OK" --> DRAW["Within 1 s: route line (map-style §7.2 selected), origin and destination markers;<br/>camera fits the route above the sheet (max zoom 17; jump with reduced motion);<br/>«Эхлэх» enabled — AC 9"]
    RES -- "404 / 500 / HTML / parse error" --> ERR["Within 1 s: footer row «Алдаа гарлаа» + «Дахин оролдох»<br/>«Эхлэх» disabled; map and other entries keep working — AC 10"]
    ERR -- "«Дахин оролдох» (1 load per press)" --> LOAD
    DRAW -- "another entry selected" --> SEL
    ERR -- "another entry selected" --> SEL
    DRAW -- "«Эхлэх»" --> F3(["F3 start"])
```
A selection made while a load is still running wins: the older load is aborted and never draws (no flash of the previous route).

## F3. Start the replay — AC 12, 14, 27, 39

```mermaid
sequenceDiagram
    actor U as User
    participant P as D1 picker
    participant H as «Эхлэх» handler
    participant A as Audio (speechSynthesis / Web Audio)
    participant G as D2 guidance
    U->>P: tap «Эхлэх» (button disabled at once: a second tap does nothing)
    P->>H: activation (user gesture)
    H->>A: inside the handler: first speechSynthesis.speak (depart text, or an empty utterance when the chime is used)<br/>and create / resume() the AudioContext — iOS unlock, AC 27 (exact method: architect request 2)
    H->>G: within 1 s: picker replaced by D2, badge «Туршилтын горим» shown — AC 12, 14
    H->>G: request a screen wake lock (silently skipped if missing or refused) — AC 39
    G->>G: replay clock starts at the first track point, camera eases to the puck, heading up, pitch 45° (700 ms, jump with reduced motion)
    G->>A: depart prompt (or one chime) within 2 s — AC 27
    Note over G,A: no usable voice for the UI language → A1 notice for 8 s (F5) — AC 29
```

## F4. Replay states — AC 12–23, 32–35, 40–41

```mermaid
stateDiagram-v2
    [*] --> Following: «Эхлэх»
    Following --> Following: fix i at its track time (±100 ms) → puck snapped ≤ 200 ms, banner distance,<br/>progress (≤ 5 s), camera ≤ 1 s, prompts per navigation-ux §4.2 at 1×
    Following --> Free: pan / pinch / rotate / tilt (recenter shows ≤ 300 ms) — AC 23
    Free --> Following: «Байршил руу буцах» or 15 s without a gesture (≤ 1 s) — AC 23
    Following --> Paused: page hidden (tab or app switch, screen lock) ≤ 1 s — AC 15
    Free --> Paused: page hidden
    Paused --> Following: page visible again ≤ 1 s, same position and replay time,<br/>0 repeated prompts, wake lock re-requested — AC 15, 39
    Following --> Arrived: snapped position ≤ 30 m from the route end or past it — AC 32
    Free --> Arrived: same
    Following --> Ended: «Дуусгах» — AC 35
    Free --> Ended: «Дуусгах»
    Following --> Ended: last fix applied without arrival — AC 16
    Arrived --> Picker: «Хаах» — AC 33
    Ended --> Picker: ≤ 1 s
    Picker --> [*]
```
- **Paused** is invisible: the page is hidden. The replay clock, the progress values and any utterance or chime stop; nothing is produced while hidden; on return nothing has been passed, so nothing is announced late (AC 15). Paused from **Free** returns to **Free**; the 15 s recenter timer restarts.
- **Ended** (AC 35): utterance or chime stopped, route and puck removed, wake lock released ≤ 1 s, picker with **no** entry selected, camera eases to bearing 0 and pitch 0 (300 ms) at the same centre and zoom. Focus moves to the picker heading.
- **Arrived** (AC 32–34): banner arrival variant; arrival prompt once (it waits ≤ 3 s for a current utterance); replay clock stopped; arrival panel with the destination name and «Хаах»; 0 further prompts or chimes, also while the G4 test track stands 10 m before the end for 30 s. The camera keeps following the puck (which no longer moves) until «Хаах».
- **Allowed in every replay state** (no state change): mute / unmute (AC 30), language switch (AC 31), theme switch and rotation (AC 41), network loss (F7). Not offered: off-route, reroute, GPS loss (story › Out of scope).
- **Browser Back or reload during a replay** leaves or reloads the page; the replay is not resumable and the next load shows the picker (no state in the URL, AC 43).

## F5. Voice decision and the D23 fallback — AC 24–31

```mermaid
flowchart TD
    I(["Picker shown, or UI language changed"]) --> SS{"window.speechSynthesis exists?"}
    SS -- no --> FB
    SS -- yes --> GV["getVoices(); if the list is empty, wait for voiceschanged — max 3 s"]
    GV --> LANG{"UI language"}
    LANG -- mn --> MN{"A voice with lang ^mn([-_]|$)?"}
    LANG -- en --> EN{"A voice with lang ^en([-_]|$)?<br/>(prefer en-US)"}
    MN -- yes --> SPEAK["Voice mode: every prompt spoken with that voice,<br/>utterance.voice and utterance.lang set explicitly — AC 28"]
    EN -- yes --> SPEAK
    MN -- "no / 3 s passed" --> FB["Chime mode: every prompt = one Web Audio chime (≈ 330 ms),<br/>same timing rules; Mongolian text never goes to another voice — AC 29"]
    EN -- "no / 3 s passed" --> FBE["Chime mode, no notice (A1 names Mongolian) — AC 29"]
    FB --> NOTE{"Replay running and A1 not yet shown in this replay?"}
    NOTE -- yes --> A1["A1 «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.»<br/>8 s or until tapped, in the message stack above the progress panel — AC 29"]
    SPEAK -- "utterance error event during the replay" --> FB
    SPEAK --> MUTE{"Muted?"}
    FB --> MUTE
    FBE --> MUTE
    MUTE -- yes --> SIL["0 utterances, 0 chimes; banners unchanged — AC 30"]
    MUTE -- no --> PLAY["Play per navigation-ux §4.5 rules 1, 3, 4, 6 and §11.4"]
```
The voice found is never stored or sent (AC 28). Pending (not in the AC yet, D78 via triage F1): only voices with `localService === true` would count; on the PO's iPhone this changes nothing.

## F6. Arrival and back to the picker — AC 32–35

```mermaid
flowchart TD
    A["Approaching prompt «{n} метрт очих газартаа хүрнэ» at the main trigger,<br/>if the schedule has one (not for an arrive < 30 m after the last manoeuvre, D68)"] --> B{"Snapped position ≤ 30 m from the route end, or past it"}
    B --> C["Banner «Та очих газартаа ирлээ» or «Таны очих газар баруун талд байна» / «Таны очих газар зүүн талд байна»<br/>spoken once or one chime (waits ≤ 3 s for a current utterance)"]
    C --> D["Progress panel → D3 arrival panel: destination name (manifest) + «Хаах»<br/>replay clock stopped; 0 prompts after this; wake lock released ≤ 1 s"]
    D -- "«Хаах»" --> E(["D1 picker, no entry selected, no route drawn — AC 33"])
```

## F7. Language, theme, rotation and network during the replay — AC 31, 40, 41

```mermaid
flowchart TD
    S(["Replay running"]) --> LG["Language button in the demo row"]
    LG --> L2["Labels, banner and progress switch ≤ 1 s; current utterance stops;<br/>voice re-evaluated (F5); A1 shown if not yet shown and no voice;<br/>live region NOT updated for the switch; 0 requests, 0 repeated prompts — AC 31"]
    S --> TH["Theme button in the demo row"]
    TH --> T2["Map style + UI colours ≤ 1 s; route, puck, banner, progress stay;<br/>0 requests except tiles, 0 repeated prompts — AC 41"]
    S --> RO["Phone rotated"]
    RO --> R2["Portrait stack ↔ landscape left column ≤ 1 s; same step, same queue — AC 41"]
    S --> OFF["Network lost"]
    OFF --> O2["Within 2 s: «Интернэт холболт алга» in the message stack above the progress panel;<br/>replay, banner, prompts, progress and arrival continue from loaded data;<br/>loaded tiles stay, missing tiles show the map background — AC 40"]
    O2 -- "back online" --> O3["Indicator gone ≤ 2 s; missing tiles load"]
```

## Error-path summary (every screen has offline, GPS-lost and no-result states)
| Screen | Offline | GPS lost / no location | No result / data error |
|---|---|---|---|
| Before the picker (NAV-002 start) | NAV-002 blocking card «Интернэт холболт алга»; loads by itself when back | not applicable: Geolocation is never called in the demo-mode build | tiles fail: NAV-002 card «Газрын зургийг ачаалж чадсангүй» + «Дахин оролдох» |
| D1 picker | NAV-002 offline banner in R2; selecting an entry whose data are not loaded yet shows the footer row «Интернэт холболт алга» and loads by itself when back online | not applicable (no location; the route origin is recorded data) | the list always has R1–R3 (bundled manifest); a data file that fails or does not parse shows «Алдаа гарлаа» + «Дахин оролдох» (AC 10) |
| D2 guidance | «Интернэт холболт алга» in the message stack; replay continues (AC 40) | not applicable: the position is simulated (AC 14); there is no GPS-lost state | not applicable: the replay data are loaded before «Эхлэх» is enabled; a track that ends early behaves as «Дуусгах» (AC 16) |
| D3 arrival | nothing needed (no network use) | not applicable | — |
| Host / demo folder | Safari's own error page (accepted) | — | missing or wrong upload: the host's own error page (not our UI); the after-upload 200 + `noindex` check catches it (AC 5, F0) |
