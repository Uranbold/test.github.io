# Flow NAV-012: Android background and lock-screen navigation (notification, lock screen, swipe-away, restore, battery saver, audio, calls, automatic theme)

- **Story:** [NAV-012](../../requirements/stories/NAV-012-android-background-lock-screen.md) (Phase 1 beta, priority must, Android only). AC referenced per step.
- **Screens:** [`screens/NAV-012-android-background-lock-screen.md`](../screens/NAV-012-android-background-lock-screen.md). It is a **delta** on [`screens/NAV-005-android-navigation.md`](../screens/NAV-005-android-navigation.md) (S3 preview, S5 guidance, S6 arrival, S7 settings, S8 notification) and on the NAV-011 collapsed preview sheet. Everything not named here stays as NAV-005 / NAV-011.
- **Base flows:** [`flows/NAV-005-android-navigation.md`](NAV-005-android-navigation.md). On Android, NAV-012 **replaces** the NAV-005 swipe-away outcome (NAV-005 AC 20, flow F5) with F3 below and **extends** F5 (background), F9 (voice) and F11 (theme).
- **Rules:** [`navigation-ux.md` §12](../navigation-ux.md) (timing and voice rules for this story), §9 (theme). Map style: [`map-style.md` §8](../map-style.md).
- **Prototype:** [`prototypes/NAV-012-background.html`](../prototypes/NAV-012-background.html), checked by `prototypes/check-layout-nav012.mjs`.
- **API:** no new operation. A restore that needs a new route sends `postRoute` exactly as a NAV-005 reroute.

Copy is quoted by its `mn` value in «»; every string is a glossary term (B1–B5 are `needs native review`); keys are in the screen spec › Copy. "Good fix" = accuracy ≤ 25 m, "fresh" = ≤ 10 s old (NAV-005). "Restore window" = 30 min after the last heartbeat (story working assumption, Open question 5).

## F1. Rich ongoing notification (AC 1–7)

The notification is a second view of the banner. It never has its own state: it copies the banner within 1 s.

```mermaid
stateDiagram-v2
    [*] --> Manoeuvre: «Эхлэх», notification permission granted — AC 1
    [*] --> NoNotification: permission denied (Android 13+) — AC 7
    NoNotification --> NoNotification: guidance, lock screen, swipe-away and restore still work<br/>(restore only when the app is opened)
    Manoeuvre --> Manoeuvre: distance changes → update ≥ every 2 s, ≤ 1 post/s — AC 2
    Manoeuvre --> Recalculating: off-route — ≤ 1 s
    Recalculating --> Manoeuvre: new route / back on route
    Manoeuvre --> GpsLost: no good fix 10 s
    GpsLost --> Manoeuvre: good fix
    Manoeuvre --> Arrival: arrival detected
    Arrival --> [*]: removed ≤ 10 s after arrival (NAV-005 AC 55)
    Manoeuvre --> [*]: «Дуусгах» (screen, notification, lock screen) — removed ≤ 2 s
    note right of Manoeuvre
        title = distance «300 м», text = instruction,
        expanded: + street, header «Хүрэх цаг 14:35»,
        actions «Дууг хаах» / «Дууг нээх», «Дуусгах»
        0 sounds, 0 vibrations, 0 heads-up — AC 4
    end note
```

```mermaid
flowchart TD
    N["Notification shown (any guidance state)"] --> A{"User action"}
    A -- "tap the notification" --> O["S5 guidance within 2 s (over the lock screen if locked)<br/>same route and progress, 0 requests, 0 repeated prompts — AC 6"]
    A -- "«Дууг хаах» / «Дууг нээх»" --> V["Voice muted / unmuted within 1 s, choice remembered<br/>label + on-screen button follow; screen does NOT open — AC 3"]
    A -- "«Дуусгах»" --> E["Guidance ends (NAV-005 AC 19), restore record deleted ≤ 2 s — AC 17"]
    A -- "swipe away (Android 14+ only)" --> SW["Guidance continues unchanged — AC 5"]
    SW --> RP{"Next banner instruction change?<br/>(new manoeuvre, reroute start, GPS lost, arrival)"}
    RP -- yes --> N
    A -- "language switched in «Тохиргоо»" --> L["Texts, actions and channel name in the new language ≤ 2 s, 0 requests — AC 4"]
```

## F2. Lock-screen view (AC 8–12)

```mermaid
flowchart TD
    G["S5 guidance visible"] --> OFF["Screen off (power button or timeout)"]
    OFF --> NOW["App never turns the screen on by itself — AC 12"]
    NOW --> ON["User turns the screen on"]
    ON --> L["S5 over the lock screen within 1 s, no unlock<br/>banner, map, progress, «© OpenStreetMap contributors» — AC 8"]
    L --> U{"User action (over the lock screen)"}
    U -- "«Байршил руу буцах», orientation, «Дууг хаах»" --> L2["Works without unlocking — AC 8"]
    U -- "«Тохиргоо»" --> K["OS unlock prompt first — AC 9"]
    K -- "unlocked" --> S7["S7 settings over the guidance"]
    K -- "cancelled" --> L
    U -- "System Back" --> BG["App to background → phone lock screen<br/>(nothing of the app is revealed; guidance continues)"]
    U -- "«Дуусгах»" --> END["Guidance ends (NAV-005 AC 19)<br/>phone lock screen ≤ 2 s, not the map screen — AC 9"]
    L --> ARR["Arrival while over the lock screen"]
    ARR --> AP["S6 arrival panel stays over the lock screen<br/>until «Хаах» or screen off — AC 10"]
    AP -- "«Хаах»" --> LS["Phone lock screen ≤ 2 s — AC 10"]
    END --> CLR["Lock-screen flag cleared ≤ 2 s;<br/>the app never shows over the lock screen again until the next «Эхлэх» — AC 11"]
    LS --> CLR
```

- An open S7 sheet or any other non-guidance surface **closes when the screen turns off**, so waking the phone shows only S5 (AC 9). Settings already applied stay applied.
- The lock-screen **notification** shows the AC 1 content (never the destination name or coordinates). If the user has hidden sensitive notification content on the lock screen, the public version «Замчлал» shows instead (AC 12).

## F3. Continue after swipe-away (AC 13–15) — replaces NAV-005 F5 swipe-away on Android

```mermaid
flowchart TD
    G["Guidance active"] --> SW["User removes the app from Recents"]
    SW --> K{"Did the OEM kill the process?"}
    K -- "no (stock Android, most phones)" --> C["Guidance continues: service, 1 Hz fixes (no gap > 2 s),<br/>voice, off-route, reroute, notification updates — AC 13"]
    K -- "yes (aggressive OEM)" --> F4(["F4/F5 restore (interrupted session)"])
    C --> R{"User opens the app<br/>(notification, launcher, Recents)"}
    R --> S5["S5 within 2 s, same route and progress<br/>0 requests, 0 repeated prompts, no depart prompt — AC 14"]
    C --> E["«Дуусгах» in the notification → NAV-005 AC 19 — AC 15"]
    N["No guidance active"] --> SW2["App removed from Recents"] --> Z["≤ 2 s: 0 services, 0 location requests — AC 15"]
```

## F4. Restore record lifecycle (AC 16–17, 21, 24)

The record is invisible to the user; this machine explains why the app sometimes opens straight into guidance.

```mermaid
stateDiagram-v2
    [*] --> NoRecord
    NoRecord --> Active: «Эхлэх» → record written ≤ 2 s<br/>(destination, costing, avoid-unpaved, language, route, start, heartbeat) — AC 16
    Active --> Active: reroute → rewritten ≤ 2 s, heartbeat ≥ every 60 s
    Active --> NoRecord: normal end («Дуусгах», arrival «Хаах»/end) → deleted ≤ 2 s — AC 17
    Active --> Interrupted: process killed (OEM saver, crash, update, phone off)
    Interrupted --> Restoring: app opened ≤ 30 min after heartbeat (F5)<br/>or service recreated (F6)
    Interrupted --> NoRecord: app started > 30 min after heartbeat → deleted ≤ 2 s,<br/>map screen, no notice, 0 requests — AC 21
    Restoring --> Active: guidance running again
    Restoring --> NoRecord: 3rd interruption within 10 min → deleted,<br/>map screen, no notice — AC 24
    Interrupted --> NoRecord: unknown schema / unreadable → deleted silently — AC 24
```

## F5. Opening the app with a valid record (AC 18–20, 23)

```mermaid
flowchart TD
    O(["User opens the app (launcher, Recents, AC 22 notification)"]) --> V{"Record valid?<br/>(heartbeat ≤ 30 min, schema known,<br/>≤ 2 restores in 10 min)"}
    V -- no --> M["S1 map as usual, record deleted, no notice — AC 21, 24"]
    V -- yes --> P{"Precise location access<br/>and location services on?"}
    P -- no --> LOC["S1 map + NAV-005 S4 location message<br/>(«Байршлын зөвшөөрөл олгоогүй байна» / «Байршил тогтоох үйлчилгээ унтарсан байна»<br/>/ «Нарийвчилсан байршлыг зөвшөөрнө үү») + «Тохиргоо нээх», «Хаах»<br/>record kept — AC 23"]
    LOC -- "access granted inside the window" --> RS
    LOC -- "window ends" --> M2["Record deleted; map screen stays"]
    P -- yes --> RS["S5 restoring ≤ 3 s, no «Эхлэх» tap — AC 18<br/>banner «Ачаалж байна…» (neutral), progress skeleton,<br/>camera shows the stored route, no puck yet<br/>«Замчлал сэргэлээ» for 3 s in the status area<br/>service + notification running; no depart prompt"]
    RS --> FX{"First good fix within 10 s?"}
    FX -- no --> GL["NAV-005 GPS-lost state: «GPS дохио тасарлаа»<br/>banner stays «Ачаалж байна…», 0 reroutes — AC 19"]
    GL -- "good fix" --> FX2
    FX -- yes --> FX2{"Within 50 m of the stored route?"}
    FX2 -- yes --> ON["Guidance on the stored route, 0 route requests<br/>follow camera, puck; ONE prompt for the next manoeuvre ≤ 3 s<br/>(if ≥ 30 m ahead) — AC 19; works offline — AC 20"]
    FX2 -- no --> OR["NAV-005 section G off-route: «Маршрутыг дахин тооцоолж байна»<br/>reroute with the stored costing, options, language — AC 19<br/>offline → «Интернэт холболт алга» on the banner (NAV-005 AC 50) — AC 20"]
    OR --> NEW["New route → manoeuvre banner + catch-up prompt (NAV-005)"]
    ON --> HINT["Next route preview (after this trip): battery hint shown once,<br/>even if dismissed in the last 30 days — AC 26"]
```

## F6. Service recreated by the system without the app (AC 22, 25)

```mermaid
flowchart TD
    K["Process killed during guidance; record valid"] --> SR["System recreates the guidance service<br/>(no reboot start: no RECEIVE_BOOT_COMPLETED — AC 25)"]
    SR --> LA{"Location available to the service now?<br/>(per API level, ADR B-A2)"}
    LA -- yes --> BG["Guidance resumes without opening the app ≤ 10 s<br/>notification = guidance state, ONE prompt (AC 19)"]
    LA -- no --> N["≤ 5 s: notification «Замчлал тасарлаа» / «Үргэлжлүүлэхийн тулд дарна уу»<br/>actions «Үргэлжлүүлэх», «Дуусгах»; service stops; 0 location requests"]
    N --> A{"User action"}
    A -- "tap or «Үргэлжлүүлэх»" --> F5(["F5: S5 restoring"])
    A -- "«Дуусгах»" --> D["Record deleted, notification removed ≤ 2 s"]
    A -- "nothing" --> X["Notification disappears when the restore window ends; record deleted"]
    NP["Notification permission denied"] -.-> NN["No notification: restore only when the app is opened (F5) — AC 7"]
```

## F7. Battery-saver hint (AC 26–30)

```mermaid
flowchart TD
    P["S3 route preview shows a route<br/>(NAV-011 collapsed sheet, any mode)"] --> R{"App subject to battery optimisation?"}
    R -- no --> NONE["No hint"]
    R -- yes --> SN{"Dismissed in the last 30 days<br/>and no restore since?"}
    SN -- yes --> NONE
    SN -- no --> E["Collapsed sheet: entry row «Батарейн хязгаарлалт» after «Хүрэх цаг»<br/>(NAV-011 caps kept; «Эхлэх» still one tap) — AC 26"]
    E -- "tap the row, drag the sheet up,<br/>or TalkBack on (opens expanded)" --> H
    SN -- "no, wide window (side sheet)" --> H
    H["Full hint first in the expanded part:<br/>«Батарей хэмнэх тохиргоо замчлалыг зогсоож болзошгүй. Утасны тохиргоонд батарейн хязгаарлалтыг унтраана уу.»<br/>«Хаах», «Тохиргоо нээх» — AC 26"]
    E -- "«Эхлэх»" --> G
    H -- "«Хаах»" --> SZ["Hint and entry row hidden; not shown again for 30 days<br/>(except once after a restore) — AC 26"]
    H -- "«Тохиргоо нээх»" --> SYS["System battery-optimisation settings<br/>(or the app's system details page) — AC 27"]
    SYS -- "back, exemption granted" --> GONE["Hint and entry row gone ≤ 2 s; not shown while exempt — AC 27"]
    SYS -- "back, still restricted" --> H
    H -- "«Эхлэх»" --> G["S5 guidance; the hint is never shown in guidance or over the lock screen"]
    ST["S7 «Тохиргоо»"] --> ROW["Row «Батарейн хязгаарлалт» + «Тохиргоо нээх» (always)<br/>B2 text under it only while restricted — AC 28"]
    ROW -- "«Тохиргоо нээх»" --> SYS
```

OEM names and settings paths are only in `mobile/android/README.md` (AC 29–30), never in the app.

## F8. Audio output changes and volume keys (AC 31–34)

```mermaid
flowchart TD
    G["Guidance with voice on or muted"] --> C{"Output connects / disconnects<br/>(Bluetooth A2DP, USB, wired, AUDIO_BECOMING_NOISY)"}
    C --> K["Guidance continues; voice setting unchanged (no auto-mute/unmute); 0 requests — AC 32"]
    K --> PL{"Prompt playing at that moment?"}
    PL -- yes --> FS["Finishes or stops ≤ 1 s; never queued for later — AC 32"]
    PL -- no --> NX["Next prompt plays on the output that is current then"]
    BT["Car / headset over Bluetooth media"] --> LI["Prompt via the platform route; optional lead-in ≤ 500 ms,<br/>still starts ≤ 1 s after the trigger — AC 31, 33"]
    HFP["Bluetooth for calls only (HFP)"] --> SPK["Phone speaker (voice over call audio is out of scope) — AC 31"]
    VK["Hardware volume keys while S5 is visible (also over the lock screen)"] --> VS["Change the navigation prompt stream, not the ringer — AC 34"]
```

## F9. Phone calls: stop, skip and one catch-up (AC 35–39)

```mermaid
stateDiagram-v2
    [*] --> NoCall
    NoCall --> InCall: audio mode IN_CALL / IN_COMMUNICATION (VoIP) /<br/>CALL_SCREENING / RINGTONE, or transient focus loss — AC 35
    InCall --> InCall: voice triggers → 0 utterances, 0 chimes, skipped, not queued<br/>remember "skipped for the current next manoeuvre" — AC 37
    InCall --> InCall: banner, notification, off-route, reroute, GPS loss continue
    InCall --> CatchUp: call ends (last of back-to-back calls), guidance active, good fix,<br/>a prompt for the CURRENT next manoeuvre was skipped, manoeuvre ≥ 30 m ahead
    InCall --> NoCall: call ends, nothing skipped (or manoeuvre < 30 m, GPS lost) → no catch-up
    CatchUp --> Guard: ≤ 2 s: ONE prompt with the current distance (or chime) — AC 38
    Guard --> NoCall: a regular trigger for the same manoeuvre within 5 s is skipped
    note right of InCall
        A prompt playing when the call starts stops ≤ 500 ms,
        audio focus released ≤ 1 s — AC 36.
        No catch-up for off-route, GPS lost/restored or arrival.
    end note
```

Music and other audio: every prompt requests transient focus with ducking; music returns to its level ≤ 1 s after the prompt; podcasts that pause resume (platform behaviour) (AC 39).

## F10. «Автомат» theme by sunrise and sunset (AC 40–44) — extends NAV-005 F11

```mermaid
flowchart TD
    T["Theme setting"] -- "«Өдрийн горим» / «Шөнийн горим»" --> FIX["Fixed theme (NAV-005 AC 58); system dark theme ignored — AC 43"]
    T -- "«Автомат» (default)" --> POS{"Position for the sun calculation"}
    POS -- "fix in this app session" --> SUN
    POS -- "else platform last known ≤ 24 h (read, not stored)" --> SUN
    POS -- "else" --> P1["P1 Sükhbaatar Square"] --> SUN
    SUN["Sun times on the device, UTC, 0 network requests — AC 40, 41"] --> D{"Now between sunrise and sunset?"}
    D -- yes --> DAY["Day map flavor + Compose day colours"]
    D -- no --> NIGHT["Night map flavor + Compose night colours"]
    DAY --> W{"Sunrise/sunset passes, app starts or returns to the foreground"}
    NIGHT --> W
    W --> HY{"Last automatic change ≥ 10 min ago?"}
    HY -- yes --> SW["Switch ≤ 60 s after the computed time (≤ 1 s on start / foreground) — AC 42<br/>during guidance: route, puck, banner, progress kept; 0 requests; 0 repeated prompts"]
    HY -- no --> WAIT["Wait until 10 min have passed (no flicker) — AC 42"]
```

## Error and edge paths covered (cross-reference)

| Situation | Where |
|---|---|
| Offline during a restore | F5 (on the stored route: full offline guidance; off it: NAV-005 AC 50) |
| GPS lost during a restore or a call | F5 (GPS-lost state, banner stays «Ачаалж байна…» until a good fix), F9 (no catch-up while GPS is lost) |
| Permission revoked while the app was dead | F5 (S4 message, record kept until the window ends) |
| Notification permission denied | F1, F6 (no notification; restore on open) |
| Crash loop / app update | F4 (≤ 2 restores in 10 min; unknown schema deleted silently) |
| No route at a restore reroute | F5 → NAV-005 AC 49 «Маршрут олдсонгүй» (200 m / 30 s rule) |
| Reboot inside the window | F4/F5: restores only when the app is opened (no boot start) |
| Two calls back to back, ringing without answering | F9 (one catch-up after the last call ends) |
| Western aimags (UTC+7), wrong device clock | F10 (UTC calculation; a wrong clock shifts the switch by the same amount) |
