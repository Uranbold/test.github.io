# Flow NAV-022: Android "Download Mongolia map" (first-launch offer, Settings section, network and storage rules, progress and resume, automatic updates, 14-day offer, delete) and the "offline" indicator

- **Story:** [NAV-022](../../requirements/stories/NAV-022-android-offline-pack-download-update-map.md) (AC referenced per step). The indicator part serves [NAV-021](../../requirements/stories/NAV-021-android-on-device-routing-reroute.md) AC 27–28 and [NAV-023](../../requirements/stories/NAV-023-offline-search-reverse.md) AC 21–24. PO decisions D160, D163–D166, D169, D199–D201 ([decisions.md](../../requirements/decisions.md)).
- **Screen spec:** [`screens/android-offline-pack.md`](../screens/android-offline-pack.md) (O1 first-launch offer, O2 Settings section, O3 mobile-data dialog, O4 14-day offer, O5 delete dialog, O6 messages and notifications, O7 the indicator). Base screens: NAV-005 S1/S2/S5/S7, NAV-011 S2 and coordinate card, NAV-012 notification and S7 battery row, NAV-018 S3 sheet. Everything not named here behaves as there.
- **Architecture:** [ADR-0017](../../architecture/adr/0017-offline-mongolia-pack-android.md) §5 (PackManager on WorkManager, atomic install, consumers switch), §6 (openapi 0.6.0 `getOfflinePackManifest`, `getOfflinePackFile`).
- **Prototype:** [`prototypes/NAV-022-offline-pack.html`](../prototypes/NAV-022-offline-pack.html), checked by `prototypes/check-layout-nav022.mjs` (state ids in `code` below).

Copy is quoted by its `mn` value in «»; every string is a glossary term (section 2.7 OF1–OF29, all `needs native review`, plus reused rows). `{size}` follows the story's size display rule with a no-break space before the unit («120 МБ»). **Wi-Fi** and **mobile data** mean what the story's Terms table says (validated + not metered / validated + metered). A captive-portal Wi-Fi is not Wi-Fi.

## F1. First launch: the one-time offer (D169) — AC 1–3

```mermaid
flowchart TD
    A(["App starts: first launch of this installation, or first start after the app update that adds this feature (AC 3, Open question 3 a)"]) --> B{"Offer flag already set?"}
    B -- yes --> Z(["No offer. Download stays in «Тохиргоо» (F2)"])
    B -- no --> C{"Pack installed?"}
    C -- yes --> Z
    C -- no --> D{"Network at start"}
    D -- "no validated network, or mobile data only" --> E["Flag SET, no offer on this or any later start (AC 3, D169)"] --> Z
    D -- "Wi-Fi" --> F["S1 renders first (map, search). Manifest fetched in the background (AC 11)"]
    F --> G{"Manifest within 10 s?"}
    G -- "network error, 404, 429, timeout" --> H["No offer, flag NOT set: a later start with Wi-Fi tries again (AC 3)"] --> Z
    G -- ok --> I{"Guidance running, or a sheet / card / dialog / permission prompt open?"}
    I -- "yes: wait until S1 is idle (rule B1)" --> I
    I -- "S1 idle" --> J["O1 offer sheet above R5 (scrim over the map only): «Монголын газрын зураг татах», OF3, «Татах хэмжээ: 120 МБ», «Утсанд шаардлагатай зай: 338 МБ», «Дараа» / «Татах». Flag SET the moment it shows (AC 2). state offer-first"]
    J -- "«Дараа», Back, swipe down, tap on the scrim, process death" --> K["Sheet closes, nothing downloads. Never offered again on this installation, not even after delete (AC 2, 38)"] --> Z
    J -- "«Татах»" --> L["Sheet closes (150 ms). Same path as a Settings download: F2 from the storage check"]
```

Notes:
- The offer appears at most once per installation and never interrupts guidance or a task the user started (rule B1). If the user starts a search or a preview before the 10 s manifest window ends, the offer waits until they return to S1 within the same app session; if the session ends first, the flag stays unset only when the offer never showed (AC 2 sets it on show), so it can appear on the next start with Wi-Fi.
- **Demo build (NAV-019, `demo` build type):** no offer, no manifest request, no O2 section (0 network requests, D184). See the spec, Known limitations 5.

## F2. A user-started download (O2 «Татах», the O1 offer, «Шинэчлэх») — AC 4–11, 26

```mermaid
flowchart TD
    A(["User taps «Монголын газрын зураг татах» in O2, «Татах» in O1, or «Шинэчлэх» in O2"]) --> M{"Manifest known?"}
    M -- no --> M1["Fetch it (any validated network, AC 11). O2 shows «Татаж байна… 0%» meanwhile (optimistic, under 400 ms)"] --> M2{"Fetched?"}
    M2 -- "no network" --> Q["Queued: O2 «Wi-Fi холболт хүлээж байна» + «Цуцлах» (AC 10). state set-waiting"]
    M2 -- "error" --> FAIL["O2 error row «Газрын зургийг татаж чадсангүй» + «Дахин оролдох» (AC 15). state set-failed"]
    M2 -- ok --> S
    M -- yes --> S{"Allocatable space ≥ required space? (AC 6)"}
    S -- no --> ST["0 file requests. O2 error row «Утсанд хангалттай зай алга. 45 МБ зай чөлөөлнө үү.» + «Дахин оролдох»; from O1 also the S1 message (F6). state set-storage / msg-storage"]
    ST -- "user frees space, taps «Дахин оролдох»" --> S
    S -- yes --> N{"Network now"}
    N -- "Wi-Fi" --> W["First file request within 5 s (AC 8) → F3"]
    N -- "mobile data" --> D["O3 dialog «Мобайл датагаар 120 МБ татах уу?» «Wi-Fi хүлээх» / «Татах»; 0 file requests before an answer (AC 8). state dlg-mobile"]
    D -- "«Татах»" --> W2["Runs on any validated network until it ends (AC 9) → F3"]
    D -- "«Wi-Fi хүлээх», Back, outside tap" --> Q
    N -- "no validated network" --> Q
    Q -- "Wi-Fi appears" --> W3["Starts within 30 s (AC 9) → F3"]
    Q -- "mobile data appears" --> Q2["Stays queued: never uses mobile data without O3 (AC 10). The user can start again from O2 to get O3"]
    Q -- "«Цуцлах»" --> C0(["Back to the state before (no pack, or the old pack)"])
```

The O3 answer is **never remembered** (AC 9): every user-started download over mobile data asks again. «Шинэчлэх» over mobile data shows O3 with the update size (for example «Мобайл датагаар 33 МБ татах уу?»).

## F3. Download lifecycle: progress, cancel, resume, failure, check and install — AC 12–20, 41

```mermaid
flowchart TD
    A(["Transfer runs (WorkManager job, notification channel «Офлайн газрын зураг», low importance)"]) --> P["O2 and the notification: «Татаж байна… 42%» + bar, refreshed ≤ 2 s; «Цуцлах». TalkBack hears the percentage at most every 10 s (AC 12, 45). state set-downloading"]
    P -- "«Цуцлах» (O2 or notification)" --> C["Within 5 s: transfers stop, 0 partial files, state as before (AC 13)"]
    P -- "network drops / process killed / reboot" --> R["Notification and O2: «Wi-Fi холболт хүлээж байна» (Wi-Fi rule) or the progress frozen; resumes with Range when the allowed network returns (AC 14, 15)"] --> P
    P -- "404: file retired" --> RM["Re-read the manifest, continue with the new files (AC 15); no message"] --> P
    P -- "429" --> RA["Wait Retry-After, then resume (AC 15); progress text unchanged"] --> P
    P -- "5 failures in a row" --> F["«Газрын зургийг татаж чадсангүй» + «Дахин оролдох»; partial files kept for a resume; old pack active (AC 15). state set-failed / set-update-failed / msg-failed"]
    P -- "disk full while writing" --> FS["Partial files deleted ≤ 5 s; «Утсанд хангалттай зай алга. {size} зай чөлөөлнө үү.» (AC 7)"]
    P -- "100 % received" --> V["Checking and installing: bar indeterminate, text «Татаж байна… 100%» until the BA term exists (Open question 1); «Цуцлах» still works (AC 13). state set-verifying"]
    V -- "checksum, gzip or self-test fails" --> F2["0 files activated, partial directory deleted ≤ 5 s; user-started: «Газрын зургийг татаж чадсангүй» + «Дахин оролдох» (AC 17)"]
    V -- "all pass" --> I["Atomic install (AC 18); consumers switch (AC 19): map ≤ 5 s with the camera kept (after guidance if guiding), search at the next query, routing after guidance"]
    I --> D{"App in the foreground?"}
    D -- "yes, on S1 or S2" --> M["S1 message «Офлайн газрын зураг бэлэн боллоо» for 3 s (AC 41). state msg-ready"]
    D -- "yes, on S3 / S5 / S7" --> M2["No message: O2 shows the installed state; S3 / S5 stay calm (rule B3, Open question 3)"]
    D -- "no" --> NT["Notification «Офлайн газрын зураг бэлэн боллоо»; tap opens S7 at O2 (AC 41)"]
    I --> O2["O2 installed: «Мэдээллийн огноо», «Газрын зураг: 2026-09-07», «Маршрут: 2026-09-30», «Хайлт: 2026-09-28», «Эзэлж буй зай: 202 МБ», delete, attribution, ODbL line (AC 4, 39). state set-installed"]
```

- **Where failures show:** in O2 when S7 is open (inline status row, rule B2); as an S1 message when the user is on S1 or S2 (F6); as a notification when the app is in the background. Never as a dialog, never during guidance (S5 shows nothing; the notification channel is not the guidance channel).
- **Notification permission denied (Android 13+):** the download still runs; O2 shows the progress; no new permission is asked (AC 41).

## F4. Automatic updates and «Шинэчлэх» — AC 21–26

```mermaid
flowchart TD
    A(["Periodic worker every 24 h: Wi-Fi, battery not low, storage not low (AC 21)"]) --> B{"Manifest 304?"}
    B -- yes --> Z(["Nothing changes, no UI"])
    B -- "200 with files to fetch (compatible kinds only, AC 24; also an older version with a different sha256, AC 25)" --> S{"Space ok? (AC 6)"}
    S -- no --> Z2(["Skip silently, retry next period"])
    S -- yes --> D["Download and install in the background, no dialog; low-importance progress notification may show; no completion notification (AC 22)"]
    D --> DD["O2 data dates change within 5 s of the install (AC 22, 39)"]
    D -- "check fails" --> Z3(["Fails silently, retries next period (AC 17)"])
    U(["User opens S7: O2 shows «Татах хэмжээ: 33 МБ» + «Шинэчлэх» only when files to fetch exist (AC 4). state set-update"]) -- "«Шинэчлэх»" --> F2["User-started download: F2 (storage, O3 on mobile data) → F3. state set-update-running"]
```

## F5. The 14-day routing + search offer over mobile data (D165, D200) — AC 27–30

```mermaid
flowchart TD
    A(["App comes to the foreground, or the network changes while in the foreground"]) --> C{"All AC 27 conditions? mobile data · older of routing/search data_timestamp > 14 × 24 h · files to fetch among routing/search · no guidance · not declined in the last 7 × 24 h · not shown in this foreground session"}
    C -- no --> Z(["No offer"])
    C -- yes --> I{"S1 idle (no sheet, card, dialog, typing, preview)?"}
    I -- "no: wait (rule B1)" --> I
    I -- yes --> O["O4 sheet above R5: «Маршрут, хайлтын мэдээлэл хуучирсан байна», «Мобайл датагаар 33 МБ шинэчлэх үү?», «Маршрут: 2026-09-15», «Хайлт: 2026-09-15», «Дараа» / «Шинэчлэх». state offer-14"]
    O -- "«Шинэчлэх»" --> D["Routing + search only, over mobile data, no second confirmation (AC 28): storage check (AC 6) → F3"]
    O -- "«Дараа», Back, swipe down, scrim tap" --> N["Decline time stored (survives restarts); not offered for 7 × 24 h (AC 29, D200)"]
    N --> Z
```

`tiles` is never part of O4 (AC 27). A fresh update on Wi-Fi ends the offers (AC 30). If the phone is moving faster than the NAV-011 typing-lock speed, O4 waits until it is still (Open question 2, recommendation; not in the AC yet).

## F6. Delete — AC 36–38

```mermaid
flowchart TD
    A(["O2 «Офлайн газрын зургийг устгах»"]) --> D["O5 dialog «Офлайн газрын зургийг устгах уу?», «Эзэлж буй зай: 202 МБ», «Цуцлах» / «Устгах». state dlg-delete"]
    D -- "«Цуцлах», Back, outside tap" --> Z(["Nothing changes (AC 37)"])
    D -- "«Устгах», no guidance" --> X["Within 5 s: files and active.json deleted, space freed, map back to online PMTiles ≤ 5 s, search and routing as without a pack (AC 36). O2 returns to the no-pack state with «Монголын газрын зураг татах»; focus moves to that button. state set-none"]
    D -- "«Устгах», guidance running (S7 opened from S5)" --> G["Search file deleted at once; map and routing files stay until guidance ends, deleted ≤ 10 s after (AC 36). O2 shows the no-pack state at once"]
    X --> P(["Periodic checks download nothing; the first-launch offer stays off (AC 38)"])
```

A download that is running when the user confirms «Устгах» is cancelled first (AC 13), then the pack is deleted (Open question 4).

## F7. The "offline" indicator OF24 on routes and search results (D201) — NAV-021 AC 27–28, NAV-023 AC 21–24

```mermaid
flowchart TD
    A(["Preview, reroute, search or reverse request"]) --> B{"Who answered? (D163, D199: online first; on-device ≤ 3.0 s after sending, or at once without a validated network)"}
    B -- "gateway" --> N["No indicator anywhere"]
    B -- "on-device engine" --> W{"Where is the answer shown?"}
    W -- "S3 preview" --> P["Chip «Офлайн» on the duration line of the summary (applies to every option of that answer); TalkBack: summary, then «Офлайн газрын зургаас». state ind-preview, ind-preview-k2"]
    W -- "S5 guidance (route from the device, preview or reroute)" --> G["Icon-only indicator after the remaining time and distance in the progress panel; name «Офлайн газрын зургаас», announced once per route. state ind-guidance"]
    W -- "S2 results list" --> R["One chip «Офлайн» in a header row above the first result; TalkBack after results: «{count} илэрц олдлоо», «Офлайн газрын зургаас». state ind-results"]
    W -- "S2 no result" --> E["«Илэрц олдсонгүй» followed by the chip. state ind-noresult"]
    W -- "place card from a device result / coordinate card reverse" --> C["Chip next to «Ойролцоох газар» (coordinate card) or after the place type line (place card). state ind-card"]
    G -- "a later reroute comes from the gateway" --> N
```

The indicator says where the **data** came from, not whether the phone is online. It is never shown on the map itself (NAV-022 AC 40) and is visually different from the NAV-005 network message «Интернэт холболт алга» (a message card), which keeps its own rules.

## F8. Error and edge paths (summary)

| Path | What the user sees | AC |
|---|---|---|
| No network at first launch | Nothing; O2 in «Тохиргоо» later | 3 |
| Captive-portal Wi-Fi | Treated as no Wi-Fi: queued «Wi-Fi холболт хүлээж байна» | 9, 10 |
| Metered hotspot | O3 asks | 8 |
| Manifest fails in O2 | Error row «Газрын зургийг татаж чадсангүй» + «Дахин оролдох» | 15 |
| Storage full before start / while writing | «Утсанд хангалттай зай алга. {size} зай чөлөөлнө үү.» + «Дахин оролдох» | 6, 7 |
| Corrupted file | «Газрын зургийг татаж чадсангүй» + «Дахин оролдох»; old pack active | 17 |
| Pack too new for the app | Nothing offered; installed pack and its dates stay; «Шинэчлэх» absent | 24 |
| GPS lost / location permission denied | No effect on this feature (no location is used); NAV-005 rules for the map | — |
| Slow network | Percentage moves slowly; nothing blocks the app; «Цуцлах» always available | 12, 13 |
| App killed during download | On return: O2 shows the resumed progress or «Wi-Fi холболт хүлээж байна»; no restart from 0 | 14, 18 |
