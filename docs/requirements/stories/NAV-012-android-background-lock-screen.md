---
id: NAV-012
title: Android background and lock-screen navigation polish (rich notification, lock-screen view, continue after swipe-away, restore after process death, OEM battery-saver guidance, Bluetooth/car audio, phone calls, automatic sunrise/sunset theme)
phase: 1
priority: must         # Phase 1 beta; proposed by the orchestrator, PO "go ahead" 2026-10-03 (decision-log row pending, see Context)
size: L                # was M in the backlog draft; L because it bundles eight device-level features and a restore state machine
needs_design: true
needs_backend: false   # no contract change: reroutes after a restore are the existing postRoute
needs_mobile: true     # Android only (mobile/android); iOS is NAV-015
status: ready
---

# NAV-012: Android background and lock-screen navigation polish

## Story
As a **taxi / delivery driver**, I want **guidance to keep running and keep talking to me when the screen is off, when I swipe the app away, when the phone kills it, when I take a call or when my phone plays through the car stereo, and to see the next turn on the lock screen and in the notification**, so that **I never lose my route in the middle of a delivery and never have to unlock the phone or restart navigation while driving**.

Secondary personas:
- **UB commuter by car**: listens to music or the radio through the car's Bluetooth, takes calls on Viber or a normal call during the commute, and gets the night map automatically on dark winter mornings and evenings.
- **Intercity / countryside driver**: on long trips the OEM battery saver or a cold, dying battery can stop the app; after reopening, guidance must come back to the same destination **without a network** where possible.
- **Pedestrian**: walks with the phone locked in a pocket and checks the next turn on the lock screen or in the notification without unlocking.
- **Tourist (English UI)**: gets the same notification, lock-screen view and restore in English; the battery hint and the README explain why the phone may stop the app.

## Context
- **Origin:** backlog draft NAV-012 (2026-10-01), a NAV-005 follow-up. The scope is the backlog row: rich notification (manoeuvre icon, distance, next instruction), lock-screen view, continue after swipe-away, restore after process death, OEM battery-saver guidance (in-app hint and README), Bluetooth/car audio routing for voice and chime, phone-call handling (duck/pause and resume), automatic sunrise/sunset theme computed on the device without a network. NAV-005 already points here: AC 20 ("continuing after swipe-away is NAV-012"), Out of scope, Edge cases ("restore is NAV-012"), R11; ADR-0009 Follow-ups; NAV-005 screen spec S8 ("Rich layout and lock-screen view: NAV-012"); `navigation-ux.md` and `map-style.md` («Автомат» becomes sunrise/sunset, no fourth option).
- **Started 2026-10-03:** feature request relayed by the orchestrator; the PO said "go ahead" (the relayed message was "Can we skip this and go ahead"). **Priority must (Phase 1 beta), as proposed by the orchestrator.** The PO did not name a priority, so "go ahead" is read as accepting the proposal. The decision-log row is **pending**: the parallel NAV-011 run is adding D106–D108 for the same day, and the BA adds the NAV-012 row after those land, to avoid duplicate IDs. The 2026-10-03 PO decisions on the demo folder and light QA are recorded by the NAV-011 run, not here.
- **PO decisions that apply (not open):**
  - [D24](../decisions.md): Android first. iOS is out of scope (NAV-015).
  - [D17](../decisions.md) / [D91](../decisions.md): no external release before NAV-007 (and NAV-016 or a passing Mongolian voice). This story builds and verifies; it does not release.
  - [D62](../decisions.md): phones without Google Play services are supported. Nothing here may need Play services.
  - [D63](../decisions.md): «Автомат» is the default theme and today follows the Android dark-theme setting. **This story changes the meaning of «Автомат» to sunrise/sunset** (section H), as the design docs already plan. Because that replaces part of a PO decision, it is Open question 4 (working assumption: option (a)).
  - [D9](../decisions.md) / [D26](../decisions.md): team-only use of staging. A restore may send one reroute with the current position, like any reroute.
  - [D23](../decisions.md): chime plus A1 when there is no usable voice. Every voice rule here applies to the chime too.
- **Light-QA mode for this run (orchestrator run instructions, 2026-10-03):** QA writes the test plan and runs only the JVM/Robolectric unit tests for the changed areas plus the NAV-005 suite as regression (`./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`). No long suites. Most of this story needs a real Android phone (lock screen, OEM battery savers, Bluetooth, calls). Those checks are listed in AC 52 and reported as **not verified in this environment**. The PO's own phone is an iPhone (NAV-011 Context), so the real-device list waits for an Android test phone.
- **Relation to NAV-005.** When NAV-012 ships, these NAV-005 AC change on Android. NAV-005 itself is **not edited now** (mid-flight rule, the D104 pattern). QA adapts the listed NAV-005 tests and names them in its handoff:

  | NAV-005 AC | First-slice behaviour | After NAV-012 |
  |---|---|---|
  | AC 20 | removing the app from Recents ends guidance within 5 s (test `GuidanceServiceTest.swipeAwayEndsGuidance`) | guidance continues (AC 13). The NAV-005 test is replaced, not deleted silently |
  | AC 15, 17 (S8) | notification title = banner line, text = distance · street, action «Дуусгах» | rich content and voice action (section A); AC 15 and AC 17 otherwise unchanged |
  | AC 36 | prompt skipped when audio focus is denied | also stopped and caught up after calls (section G); skipping and no queueing unchanged |
  | AC 58 | «Автомат» follows the system dark theme (D63) | «Автомат» follows sunrise/sunset (section H, Open question 4) |
  | AC 67 | nothing about the trip is stored on the device | one exception, the restore record, only while guidance runs and during the restore window (AC 16, 17, 47) |
  | AC 73 | real-device list | extended by AC 52 |

- **Coordination (binding for this delivery):**
  - **NAV-011** (Android route preview and search parity) is built **in parallel** in `mobile/android`.
  - Before editing a shared file (`MainActivity`, `strings.xml` in `values/` and `values-en/`, settings, the guidance service, the manifest), read its latest version. Make small, targeted edits and **never revert the other story's changes**.
  - Put NAV-012 code in its own packages where possible (for example `…/background/restore`, `…/background/battery`, `…/audio/calls`, `…/theme/sun`, `…/service/notification`; the names are the mobile engineer's choice).
  - Never stop, restart or rebuild the shared dev stack at `http://127.0.0.1:8080`. Live route requests: at most 2 per second from the whole test run.
  - No secrets, keystores, hostnames or IPs in the repo (loopback for local dev is allowed).
- **API:** no new operation. A restore that needs a new route uses `postRoute` exactly as a NAV-005 reroute (AC 43–50). `openapi.yaml` does not change.

### Design questions for the architect (record in an ADR-0009 amendment or a new ADR; none blocks UX)
- **B-A1. Resuming Ferrostar from a stored route.** Can the stored OSRM response be fed back into the navigator so that a restored session continues with **0** route requests when the position is on the route (AC 19, 20)? How progress is re-established (re-snap from the first good fix).
- **B-A2. Service restart and background-start limits per API level (26–36).** `START_STICKY` or not; what AC 22 can do on each level given the Android 12+ foreground-service start restrictions and the while-in-use location rules for a service started from the background; the Android 14+ user-dismissable foreground-service notification (AC 5).
- **B-A3. Restore record storage.** Format, schema version, write and delete timing, exclusion from cloud backup and device transfer (`data_extraction_rules.xml`, `allowBackup`).
- **B-A4. Call detection without `READ_PHONE_STATE`.** `AudioManager` mode listener (API 31+) and the fallback on API 26–30; audio-focus handling; how "a prompt was skipped because of a call" is tracked for AC 38.
- **B-A5. Notification layout.** Standard template with a large icon vs `DecoratedCustomViewStyle`; whether the Android 16 progress-centric notification style is used where available; rendering the vector manoeuvre icons as bitmaps.
- **B-A6. Sun calculation.** A pure-Kotlin implementation of the NOAA algorithm (or an offline library with an Apache-2.0-compatible licence and no network access), unit-tested on the JVM.
- **B-A7. Lock screen.** `setShowWhenLocked` / `requestDismissKeyguard` and the API 26 window-flag fallback; how screens other than guidance require unlocking (AC 9).
- **B-A8. R8 keep rules** for JNA and the UniFFI bindings: ADR-0009 says they are "decided with NAV-012, before any release build is distributed". This is a technical item with no AC here. The architect decides whether it stays tied to this story.

### Terms used in this story
- **Guidance, good fix, fresh fix, reference device:** as NAV-005 (good fix: accuracy ≤ **25 m**; fresh: ≤ **10 s** old).
- **Normal end of guidance:** «Дуусгах» on the screen or in the notification, or arrival (NAV-005 AC 19, 55).
- **Interrupted session:** the app process ended while guidance was active, without a normal end. Causes: the system or an OEM battery saver killed the process, a crash, an app update, or the phone switched off (also a battery that died in the cold).
- **Restore record:** the only trip data NAV-012 keeps on the device (AC 16).
- **Heartbeat:** the time stamp in the restore record, updated at least every **60 s** during guidance.
- **Restore window:** **30 min** after the last heartbeat (BA default, Open question 5).
- **Call in progress:** the condition defined in AC 35.
- **Sun times:** sunrise and sunset at a position, with the sun's centre at **−0.833°** (standard refraction and solar radius, as the NOAA Solar Calculator uses).
- **Reference points:** P1 Sükhbaatar Square (47.9189, 106.9176) and X1 Erdenet (49.0270, 104.0440) from NAV-001; **K1 Khovd** (48.0056, 91.6419; Mongolia's UTC+7 zone) and **Z1 Zamyn-Üüd** (43.7167, 111.9000), added for the sun tests.

### User-facing strings
Every Mongolian string comes from `docs/requirements/glossary.md`. **Reused, no new wording:**

| Use in NAV-012 | Glossary row | `mn` |
|---|---|---|
| Notification channel, title fallback | Navigation (active guidance) | «Замчлал» |
| Notification and lock-screen actions | End navigation, Voice on / off, A5 | «Дуусгах», «Дууг хаах» / «Дууг нээх» |
| Notification arrival time | ETA, N16 | «Хүрэх цаг HH:MM», «+{days} өдөр» |
| Notification states | Reroute, GPS signal lost, Arrival (voice) | «Маршрутыг дахин тооцоолж байна», «GPS дохио тасарлаа», «Та очих газартаа ирлээ» and the side variants |
| Restore notification action | A7 Continue | «Үргэлжлүүлэх» |
| Battery hint buttons | A3 Open settings, Close (dismiss) | «Тохиргоо нээх», «Хаах» |
| Theme options | Day mode, Night mode, A6 | «Өдрийн горим», «Шөнийн горим», «Автомат» |
| Attribution | OSM attribution | «© OpenStreetMap contributors» |

**New rows B1–B5, added 2026-10-03 to glossary section 2.5, all `needs native review`** (BA proposals. The PO may pre-review them, as in D69, and the NAV-007 panel rates them). Section 2.4 belongs to NAV-011 (K1–K3).

| # | English term | `mn` | `en` | Where |
|---|---|---|---|---|
| B1 | Battery restrictions (settings row) | «Батарейн хязгаарлалт» | "Battery restrictions" | AC 28 |
| B2 | Battery saver may stop navigation (hint) | «Батарей хэмнэх тохиргоо замчлалыг зогсоож болзошгүй. Утасны тохиргоонд батарейн хязгаарлалтыг унтраана уу.» | "Battery saving can stop navigation. Turn off battery restrictions in your phone settings." | AC 26, 28 |
| B3 | Navigation resumed (notice) | «Замчлал сэргэлээ» | "Navigation resumed" | AC 18 |
| B4 | Navigation interrupted (notification title) | «Замчлал тасарлаа» | "Navigation was interrupted" | AC 22 |
| B5 | Tap to continue (notification text) | «Үргэлжлүүлэхийн тулд дарна уу» | "Tap to continue" | AC 22 |

No Mongolian OEM names, settings paths or Android system wording appear in the UI. They are in the README (English, AC 29). The sunrise/sunset theme needs no new string under the working assumption (Open question 4 (a)). Options (b) and (c) would need a new glossary row first.

## Acceptance criteria

### A. Rich ongoing notification
1. **Given** guidance on a route with the notification permission granted, **When** the ongoing notification in channel «Замчлал» is shown, **Then** it contains:
   - (a) the icon of the next manoeuvre, the same drawable as the NAV-005 banner icon for that manoeuvre (AC 21);
   - (b) the distance to the next manoeuvre in the NAV-004 AC 23 format, equal to the banner distance at the same moment;
   - (c) the next instruction text, identical to the banner (NAV-005 section E; Valhalla text never, NAV-005 AC 26–27);
   - (d) the street name after the manoeuvre when it is not empty (NAV-005 AC 21 cleaning);
   - (e) «Хүрэх цаг HH:MM» (with «+{days} өдөр» when needed) as on the trip progress panel;
   - (f) the actions «Дуусгах» and the voice toggle, labelled «Дууг хаах» while voice is on and «Дууг нээх» while it is muted.

   The status-bar small icon stays the monochrome navigation arrow. In the collapsed notification at least (a), (b) and (c) are visible on a 360 dp-wide phone at font scale 100 % (real-device check, AC 52). Robolectric checks the content fields.
2. **Given** guidance, **When** the banner state changes (new manoeuvre, reroute start, new route, GPS lost or restored, arrival), **Then** the notification shows the same state within **1 s**. While the distance changes, the notification distance updates at least every **2 s**. The notification is never posted more than once per second. States:
   - recalculating: title «Маршрутыг дахин тооцоолж байна», no manoeuvre icon and no distance;
   - GPS lost: «GPS дохио тасарлаа», no distance;
   - arrival: the arrival text and the flag icon, until the notification is removed (within **10 s** of arrival, NAV-005 AC 55).
3. **Given** the notification, **When** its voice action is activated, **Then** within **1 s** voice is muted or unmuted exactly as with the on-screen toggle (NAV-005 AC 37: **0** utterances and **0** chimes while muted, choice remembered). The action label and the on-screen button change to match. The guidance screen does not open, and guidance does not end.
4. **Given** any notification update during guidance, **When** it is posted, **Then** it makes **0** sounds, **0** vibrations and **0** heads-up pop-ups (channel importance low, alert once). A language switch updates the texts, the action labels and the channel name within **2 s**, with **0** route requests.
5. **Given** Android 14 or later, **When** the user swipes the guidance notification away, **Then** guidance continues unchanged (service, location, voice, reroute). The notification is posted again at the **next banner instruction change** (new manoeuvre, reroute start, GPS lost, arrival), not earlier, and once per change. On Android 13 and lower the notification cannot be swiped away (ongoing).
6. **Given** the notification, **When** it is tapped, **Then** the guidance screen opens within **2 s** (after unlocking, if the OS requires it) with the current route and progress, **0** route requests and **0** repeated prompts.
7. **Given** the notification permission is denied (Android 13+), **When** guidance runs, **Then** NAV-005 AC 13 still holds (guidance works, no notification), and the lock-screen view (section B), swipe-away (section C) and restore (section D) still work. Exception: the AC 22 notification cannot be shown, so that case falls back to restoring when the app is opened (AC 18).

### B. Lock-screen view
8. **Given** guidance is active and the screen was turned off (power button or timeout), **When** the screen is turned on again, **Then** within **1 s** the guidance screen is shown over the lock screen without unlocking. It shows the live banner, map, trip progress and attribution «© OpenStreetMap contributors». «Байршил руу буцах», the orientation toggle, the voice toggle and «Дуусгах» work without unlocking.
9. **Given** the guidance screen over the lock screen, **When** the user activates anything that would leave it («Тохиргоо», back navigation to the map screen, search), **Then** the OS unlock prompt appears first. No other screen of the app is shown until unlocking succeeds, and if the user cancels, the guidance screen stays. **When** «Дуусгах» is activated over the lock screen, **Then** guidance ends as NAV-005 AC 19, and within **2 s** the phone shows its lock screen again, not the app's map screen.
10. **Given** arrival happens while the guidance screen is shown over the lock screen, **When** the arrival panel is shown, **Then** it stays over the lock screen until «Хаах» or until the screen turns off. After «Хаах», the phone's lock screen is shown within **2 s**.
11. **Given** no guidance is active (never started, ended or arrived), **When** the phone is locked and woken, **Then** the app is never shown over the lock screen. The lock-screen flag is cleared within **2 s** after guidance ends.
12. **Given** guidance, **When** the phone is locked, **Then**:
    - the app never turns the screen on by itself (no wake-up before manoeuvres);
    - while the guidance screen is visible over the lock screen, NAV-005 AC 18 (keep screen on) applies;
    - the lock-screen notification shows the AC 1 content, never the destination name and never coordinates, unless the user has hidden notification content on the lock screen in the system settings, which is respected.

### C. Continue after swipe-away
13. **Given** guidance, **When** the user removes the app from Recents, **Then** guidance continues: the foreground service keeps running, fixes keep arriving at 1 Hz with **no** gap longer than **2 s** caused by the removal, voice prompts, off-route detection and reroute work, and the notification keeps updating (AC 2). **This replaces NAV-005 AC 20 on Android when NAV-012 ships.** Robolectric: `onTaskRemoved` neither ends the session nor stops the service. On a phone whose OEM kills the process on swipe-away, section D applies (real-device check, AC 52).
14. **Given** guidance continued after swipe-away, **When** the user opens the app from the notification, the launcher or Recents, **Then** the guidance screen is shown within **2 s** with the same route and progress, **0** route requests, **0** repeated prompts and no depart prompt.
15. **Given** no guidance is active, **When** the app is removed from Recents, **Then** within **2 s** **0** app services run and **0** location requests stay registered (as NAV-005). **Given** guidance after swipe-away, **When** «Дуусгах» in the notification is used, **Then** NAV-005 AC 19 holds.

### D. Restore after process death
16. **Given** «Эхлэх» is activated, **When** guidance starts, **Then** within **2 s** a restore record is written to app-private storage. It contains **only**:
    - the destination coordinates (≥ 5 decimals) and the destination text shown on the arrival panel;
    - the `costing`, the «Шороон замаас зайлсхийх» setting and the route `language`;
    - the active route response as received (working assumption, Open question 2);
    - the session start time and the heartbeat.

    The record is rewritten within **2 s** after every new route (reroute) and its heartbeat is updated at least every **60 s**. It is excluded from cloud backup and device-to-device transfer, and no other app can read it. It contains no search query text, no position history and no origin.
17. **Given** a restore record, **When** guidance ends normally (AC 9, NAV-005 AC 19, AC 55), **Then** the record is deleted within **2 s**. Afterwards a storage scan finds **0** coordinates (regex `-?\d{1,3}\.\d{4,}`), **0** route bodies and **0** destination texts (NAV-005 AC 67 holds again).
18. **Given** an interrupted session whose heartbeat is at most **30 min** old, **When** the user opens the app (launcher, Recents, or the AC 22 notification), **Then** within **3 s**:
    - the guidance screen is shown, navigating to the same destination with the same `costing` and «Шороон замаас зайлсхийх» setting, **without** tapping «Эхлэх»;
    - the foreground service runs and the notification is shown (NAV-005 AC 15);
    - «Замчлал сэргэлээ» (B3) is shown for **3 s** without covering the banner or the attribution.

    No depart prompt is spoken (working assumption: automatic resume, Open question 5).
19. **Given** a restored session, **When** the first good fix arrives, **Then**:
    - if the position is within **50 m** of the stored route, guidance continues on it with **0** route requests;
    - if it is more than **50 m** away, the NAV-005 section G rules apply (reroute with the stored `costing`, options and language);
    - within **3 s**, exactly **one** prompt for the next manoeuvre is spoken with the current distance (NAV-005 AC 32 text, or the chime per AC 39) if that manoeuvre is at least **30 m** ahead.

    **If no good fix arrives within 10 s**, the NAV-005 AC 51 GPS-lost state applies (guidance stays active, **0** reroute requests).
20. **Given** a restore with no validated network, **When** the position is within 50 m of the stored route, **Then** guidance works fully offline as NAV-005 AC 54, with **0** route requests. **When** it is off the stored route, **Then** NAV-005 AC 50 applies.
21. **Given** a restore record whose heartbeat is more than **30 min** old, **When** the app starts, **Then** the record is deleted within **2 s**, the map screen opens as usual, no notice is shown and **0** route requests are sent.
22. **Given** a valid restore record, **When** the system recreates the guidance service without the app being opened, **Then**:
    - **if location access is available to the service** at that moment (per the B-A2 ADR for each API level), guidance resumes as AC 19 without opening the app, within **10 s**;
    - **otherwise**, within **5 s** the service posts a notification in channel «Замчлал» and then stops. The notification has the title «Замчлал тасарлаа» (B4), the text «Үргэлжлүүлэхийн тулд дарна уу» (B5) and the actions «Үргэлжлүүлэх» and «Дуусгах». **0** location requests are made. Tapping the notification or «Үргэлжлүүлэх» resumes as AC 18. «Дуусгах» deletes the record (AC 17) and removes the notification within **2 s**. The notification disappears by itself when the restore window ends.
23. **Given** the location permission was revoked or location services were switched off while the app was dead, **When** a restore starts, **Then** the NAV-005 AC 8–12 flow runs and the record is kept. If the user grants access within the restore window, guidance resumes within **2 s** (AC 18–19). Otherwise the map screen is shown and the record is deleted when the window ends.
24. **Given** a restore record, **When** it is restored, **Then** it is restored at most **2** times within **10 min** (BA default). A third interruption in that time deletes the record and opens the map screen without a notice, so a crash loop cannot trap the user. A record with an unknown schema version (for example after an app update) or one that cannot be read is deleted silently, without a crash.
25. **Given** the manifest, **When** it is inspected, **Then** there is **no** `RECEIVE_BOOT_COMPLETED` (no automatic start after a reboot; a reboot inside the restore window restores only when the app is opened, AC 18) and **no** `ACCESS_BACKGROUND_LOCATION` (NAV-005 AC 8 unchanged; Open question 1).

### E. OEM battery-saver guidance
26. **Given** the app is subject to battery optimisation (`PowerManager.isIgnoringBatteryOptimizations` is false), **When** the route preview shows a route, **Then** a non-blocking hint «Батарей хэмнэх тохиргоо замчлалыг зогсоож болзошгүй. Утасны тохиргоонд батарейн хязгаарлалтыг унтраана уу.» (B2) appears with «Тохиргоо нээх» and «Хаах». It does not cover «Эхлэх», the route summary or the attribution, and «Эхлэх» still starts guidance with one tap. After «Хаах» the hint is not shown again for **30 days**, with one exception: it is shown **once** on the next route preview after a restore (AC 18 or 22). It is **never** shown during guidance or over the lock screen.
27. **Given** the hint or the settings row, **When** «Тохиргоо нээх» is activated, **Then** the system battery-optimisation settings open (or the app's system details page where that screen does not exist). The app does **not** hold `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (working assumption, Open question 3). **When** the user returns with the exemption granted, **Then** the hint disappears within **2 s** and is not shown while the exemption lasts.
28. **Given** the settings sheet «Тохиргоо», **When** it opens, **Then** it has a row «Батарейн хязгаарлалт» (B1) with «Тохиргоо нээх», shown whatever the battery state. While the app is restricted, the B2 text is shown under the row.
29. **Given** `mobile/android/README.md`, **When** it is read, **Then** it has a section on keeping navigation alive under OEM battery savers. For each of these families it gives the settings path(s) that allow the app to run in the background (battery restriction, autostart or app launch management, locking the app in Recents where that exists), the OS version the steps were written for, and "verified on device: yes (model, OS version, date)" or "not verified":
    - Samsung (One UI)
    - Xiaomi / Redmi / POCO (MIUI, HyperOS)
    - Huawei (EMUI, HarmonyOS)
    - Honor (MagicOS)
    - OPPO / realme (ColorOS, realme UI)
    - vivo (Funtouch OS, OriginOS)
    - OnePlus (OxygenOS)
    - stock Android / Pixel

    It also gives a short tester checklist: 10 min of guidance with the screen off, a swipe-away, and a forced stop followed by a restore. It is in English (repository docs policy) and contains no hostnames or IPs.
30. **Given** the in-app battery texts, **When** checked, **Then** they are generic: no OEM names, no settings paths and no Android system wording in the UI. They come only from glossary rows B1–B2 and the reused rows.

### F. Bluetooth and car audio
31. **Given** a voice prompt or a chime, **When** it plays, **Then** it uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` (NAV-005 AC 36) and leaves the output routing to the platform. The app never selects an output device, never opens Bluetooth SCO/HFP call audio, and does not request `BLUETOOTH_CONNECT` (manifest and code scan). On the reference device, with a car or headset connected over Bluetooth media (A2DP), prompts play through it; with nothing connected, they play on the phone speaker (real-device check, AC 52).
32. **Given** guidance, **When** a Bluetooth, USB or wired output connects or disconnects (including `ACTION_AUDIO_BECOMING_NOISY`), **Then**:
    - guidance continues, and the voice stays on or muted as set (no automatic mute or unmute);
    - the next prompt plays on the output that is current then;
    - a prompt in progress either finishes or stops within **1 s**, and is never queued for later (NAV-005 AC 34, 3 s rule);
    - **0** route requests are sent.
33. **Given** a Bluetooth media output, **When** prompts play, **Then** the first word of each prompt is audible on **5 of 5** consecutive prompts on the reference car or headset (real-device check). The implementation may add a lead-in of at most **500 ms**. Audio still starts within NAV-005 AC 34's **1 s** after the trigger.
34. **Given** the guidance screen is visible (also over the lock screen), **When** the hardware volume keys are pressed, **Then** they change the volume of the stream that navigation prompts use, not the ringer (Robolectric: the activity's volume control stream).

### G. Phone calls
35. **Given** guidance, **When** the app checks for a call, **Then** a **call is in progress** while the audio mode is `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` (VoIP calls, for example Viber, WhatsApp, Messenger or Telegram), `MODE_CALL_SCREENING` or `MODE_RINGTONE`, or while the app's audio focus is lost transiently. The app holds **no** `READ_PHONE_STATE` or `READ_CALL_LOG` permission (manifest check).
36. **Given** a prompt or chime is playing, **When** a call starts, **Then** it stops within **500 ms**, and the audio focus is released within **1 s**.
37. **Given** a call is in progress, **When** voice triggers occur, **Then** **0** utterances and **0** chimes are played: prompts are skipped and not queued (NAV-005 AC 36). Banner, notification, off-route detection, reroute and the GPS-loss state continue as usual.
38. **Given** at least one prompt for the **current next manoeuvre** was skipped because of a call, **When** the call ends and guidance is still active with a good fix, **Then**:
    - within **2 s**, exactly **one** catch-up prompt for that manoeuvre is spoken with the current distance (NAV-005 AC 32 text), or the chime (AC 39), if the manoeuvre is at least **30 m** ahead;
    - there is no catch-up for off-route, GPS lost or restored, or arrival messages, and none when no prompt was skipped;
    - a regular trigger for the same manoeuvre within **5 s** after the catch-up is skipped, so the prompt is not doubled. After that the normal schedule continues.

    Unit test with a fake audio-mode source and a fake clock.
39. **Given** another app plays audio, **When** a prompt plays, **Then** the app requests transient audio focus with ducking. Music ducks and returns to its previous level within **1 s** after the prompt (NAV-005 AC 36). Spoken-audio apps that pause instead of ducking (podcasts, audiobooks) resume after the prompt (platform behaviour, real-device check).

### H. Automatic sunrise/sunset theme
40. **Given** the on-device sun function, **When** it computes sunrise and sunset for P1, X1, K1 and Z1 on 2026-03-20, 2026-06-21, 2026-09-23 and 2026-12-21, **Then** every result is within **±2 min** of the NOAA Solar Calculator value for that point and date (JVM test; QA records the reference values in the fixture). Illustration only: at P1 on 2026-12-21 sunrise is about 08:40 and sunset about 17:00 (UTC+8).
41. **Given** the theme «Автомат» (working assumption, Open question 4 (a)), **When** it applies, **Then** the day theme (map flavor and Compose colours) is used from sunrise to sunset at the current position, and the night theme otherwise. The position is:
    - the latest fix of this app session;
    - otherwise the platform's last known location if it is at most **24 h** old (read, not stored by the app);
    - otherwise P1.

    The computation makes **0** network requests (NAV-005 AC 65) and works in UTC, so the device time-zone setting does not change the result (Mongolia has two time zones, UTC+8 and UTC+7, and no daylight saving time).
42. **Given** «Автомат», **When** sunrise or sunset passes, **Then** the theme switches within **60 s** after the computed time. It is also re-evaluated within **1 s** when the app starts or returns to the foreground. During guidance NAV-005 AC 59 holds (route, puck, banner and progress stay; **0** route requests; **0** repeated prompts). «Автомат» causes at most **1** theme change in any **10 min** window, so a boundary that moves by seconds with the position cannot make the theme flicker.
43. **Given** «Өдрийн горим» or «Шөнийн горим», **When** chosen, **Then** behaviour is unchanged from NAV-005 AC 58. Under the working assumption the Android system dark-theme setting no longer affects «Автомат». The default stays «Автомат» for new installs and for existing installs that never changed the theme.
44. **Given** any latitude from −90 to 90 and any date, **When** the sun function runs, **Then** it never throws. On days without a sunrise or sunset it returns day if the sun is above −0.833° at that time and night otherwise (JVM test).

### I. Privacy, strings, permissions and regression
45. **Given** a full session with NAV-012 features (lock screen, swipe-away, an interrupted session and a restore, calls, theme switches), **When** network requests are captured, **Then** NAV-005 AC 65 and 68 hold: every request goes to the configured gateway, and **0** go to any other host. A restore sends only the route requests that AC 19–20 allow.
46. **Given** the Android resources, **When** the NAV-005 AC 61 check runs, **Then** every new `mn` value matches glossary rows B1–B5 or a reused row exactly, the `mn` and `en` key sets are identical, and **0** user-facing literals are hard-coded.
47. **Given** a G1 and G2 replay with an interruption and a restore under Robolectric, **When** logs, files, DataStore and the notification are inspected, **Then** **0** coordinates, route bodies or destination texts are found in logs or notifications. The only trip data in storage is the restore record (AC 16), and only between «Эхлэх» and its deletion (AC 17, 21, 22, 24). This is the only exception to NAV-005 AC 67.
48. **Given** the merged manifest, **When** compared with NAV-005, **Then** it adds no permission beyond what the B-A2/B-A7 ADR names and justifies, and it contains **none** of `ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `BLUETOOTH_CONNECT`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or `SYSTEM_ALERT_WINDOW` (subject to Open questions 1 and 3).
49. **Given** the NAV-005 suite, **When** it runs as regression, **Then** it passes except for the tests of the behaviours NAV-012 deliberately changes (Context table: AC 20, the S8 notification content, AC 58's meaning of «Автомат», AC 67's restore exception). QA adapts those tests and names each one in its handoff. **No line added by NAV-011 is removed or reverted** (the reviewer checks the diff of shared files).

### J. Build and verification (light QA for this run)
50. **Given** `mobile/android`, **When** `./gradlew :app:assembleDebug` runs (mobile engineer), **Then** a debug APK is produced, and `mobile/android/README.md` has the AC 29 section.
51. **Given** `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`, **When** it runs, **Then** it passes and includes JVM/Robolectric tests for:
    - notification content, states, voice action, language switch and the Android 14 re-post rule (AC 1–5; Robolectric at SDK 34 for AC 5);
    - swipe-away (AC 13–15);
    - the restore record: write, delete, expiry, loop limit, schema mismatch, offline restore and the AC 22 fallback notification, all with a fake clock (AC 16–24);
    - the battery hint rules, with a fake power manager (AC 26–28);
    - audio attributes, output changes and the volume stream (AC 31, 32, 34);
    - the call state machine and catch-up, with a fake audio-mode source (AC 35–38);
    - the sun function and theme switching (AC 40–44);
    - the privacy scan and the manifest scan (AC 47, 48);
    - the strings check (AC 46);
    - the NAV-005 suite as regression (AC 49).
52. **Given** the checks that need a real Android phone, **When** QA reports, **Then** each one below is listed as **not verified in this environment**, with the reason (no Android device; the PO's phone is an iPhone), to be run on the reference device:
    - collapsed and expanded notification rendering (AC 1);
    - the lock-screen view, unlock prompts and «Дуусгах» over the lock screen (AC 8–12);
    - ≥ **10 min** of guidance with the screen off and after a swipe-away (AC 13);
    - a real process kill and a restore (`adb shell am kill` in the background, a force-stop, a reboot) (AC 18–22);
    - the AC 22 behaviour on Android 12, 14 and 15 or later where available;
    - OEM battery savers on at least one Xiaomi/Redmi and one Samsung phone, plus the README steps (AC 26–29);
    - Bluetooth car or headset output, connecting and disconnecting during a prompt, and first-word clipping (AC 31–33);
    - a cellular call and a Viber or WhatsApp call during guidance, ringing without answering, and the catch-up prompt (AC 35–38);
    - music ducking and podcast pause/resume (AC 39);
    - the theme switch at a real sunset (AC 42);
    - battery use per hour with the screen off;
    - cold-weather battery behaviour (winter).

## Edge cases
- **GPS lost:** during a restore, the GPS-lost state (AC 19). During a call, the GPS-loss rules continue and there is no catch-up while GPS is lost (AC 38). The theme uses the last fix or P1 (AC 41).
- **No network:** offline restore on the stored route (AC 20). Off the stored route, NAV-005 AC 50 applies. The sun theme needs no network (AC 41).
- **Off-route at restore:** the NAV-005 section G rules apply (AC 19). A restore inside an off-route episode starts a new episode.
- **Unpaved roads:** the «Шороон замаас зайлсхийх» setting is kept in the restore record and used for any reroute (AC 16, 19).
- **No result / no route at restore:** NAV-005 AC 49 («Маршрут олдсонгүй», 200 m / 30 s rule).
- **Cyrillic/Latin search:** not applicable. This story adds no search. The destination text in the restore record is the text the user saw, not the query.
- **Winter:**
  - a battery that dies in the cold and a phone restarted within 30 min restores on opening (AC 18, 25);
  - dark mornings (about 08:40 sunrise in late December) and early evenings get the night theme automatically (AC 41);
  - gloves: the notification actions and the lock-screen controls keep NAV-005's 48 dp targets;
  - car heater noise: first-word clipping and volume keys (AC 33, 34).
- **Swipe-away on OEMs that kill on swipe:** treated as an interrupted session (AC 13, section D).
- **Crash loop:** at most 2 restores in 10 min (AC 24).
- **App update during guidance:** the process is killed, and a schema mismatch is deleted silently (AC 24).
- **Notification dismissed (Android 14+):** posted again at the next instruction change (AC 5).
- **Notification permission denied:** no rich notification and no AC 22 notification; restore happens when the app is opened (AC 7).
- **Ringing call not answered, or a call on hold:** counts as a call in progress (AC 35). The catch-up comes after it ends.
- **Two calls back to back:** one catch-up after the last call ends (AC 38).
- **Bluetooth connected only for calls (HFP, no media profile):** prompts play on the phone speaker. Voice over call audio is out of scope (AC 31).
- **Media volume at 0:** prompts are inaudible. There is no warning in this story (Out of scope).
- **Device clock wrong:** the theme switches at the wrong time by the same amount (accepted, as «Хүрэх цаг» in NAV-005).
- **Western aimags (UTC+7):** correct sun times because the computation is in UTC (AC 41; K1 test point).
- **Phone locked, then arrival:** the arrival panel stays over the lock screen until «Хаах» (AC 10).

## Data dependencies & risks
No new OSM data dependency: this story reuses the NAV-005 route and banner data. The risks are device, platform and policy risks.

| # | Risk | Impact on NAV-012 | Mitigation / owner |
|---|---|---|---|
| R1 | **OEM background killers** (Xiaomi, Huawei, Honor, OPPO, vivo, Samsung; common in Mongolia) | Guidance stops with the screen off or after swipe-away | Restore (section D), battery hint and README (section E); real-device check (AC 52) |
| R2 | **Android background-start limits** (Android 12+ foreground-service start restrictions, while-in-use location for services started from the background) | The silent automatic restart of AC 22 may be impossible on newer Android versions without background location | AC 22 fallback notification; B-A2 ADR; Open question 1 |
| R3 | **Play policy** on background location and the battery-exemption permission | Store rejection or a policy declaration before upload | Neither permission is used (AC 25, 27, 48); Open questions 1 and 3 |
| R4 | **Ferrostar pre-1.0** may not accept a stored route for resuming | The restore always needs a route request, and fails offline | B-A1; fallback: reroute from the current position (Open question 2 (b) behaviour) |
| R5 | **Personal data stored on the device** (destination and route in the restore record) | A new exception to NAV-005 AC 67 | Content limited (AC 16), deleted on normal end and expiry (AC 17, 21), no backup; Open question 2 |
| R6 | **Bluetooth A2DP latency** clips the first word | Drivers miss the start of a prompt | Lead-in ≤ 500 ms (AC 33); real-device check |
| R7 | **VoIP call detection** differs between apps and OEMs | A prompt plays over a Viber call, or a catch-up is missing | Mode and focus rules (AC 35); real-device check with Viber/WhatsApp |
| R8 | **Sun calculation errors** (time zone, refraction) | Theme switches at the wrong time | NOAA reference tests on 4 points × 4 dates (AC 40) |
| R9 | **No Android test phone** (the PO's phone is an iPhone) | Most of this story stays unverified on a real device | AC 52 list; an Android test phone is needed before beta (non-blocking request) |
| R10 | **Parallel NAV-011 edits in shared files** | Merge conflicts or lost changes | AC 49; own packages; latest-file reads |
| R11 | **Mongolian TTS availability** (NAV-005 R1) | Catch-up and restore prompts are chimes on most phones | Unchanged D23 fallback; NAV-016 |
| R12 | **Cold-weather battery drain** (winter) | Phones switch off mid-trip | Restore window and reboot rule (AC 18, 25); battery measurement (AC 52) |

## Out of scope
- iOS (NAV-015); Android Auto and CarPlay; wearables; media-session or headset-button controls.
- Waking the screen before manoeuvres; heads-up alerts for manoeuvres.
- Voice over the Bluetooth call channel (HFP/SCO) and a setting for it (Open question 6 notes it).
- A warning when the media volume is 0.
- Travelled-route trimming and manoeuvre arrows on the route line (`map-style.md` lists them as "NAV-012 polish"; not in the agreed scope, Open question 7).
- Automatic start after a reboot; restoring after the restore window; trip history.
- A light-sensor (ambient light) theme.
- Server-side changes of any kind; any `openapi.yaml` change.
- R8 / release minification rules (B-A8, architect).
- Release to external users (D17, D91) and any store upload (D64).

## Open questions
None blocks design: each one has a working assumption that matches the existing rules and design docs, and the AC are written against it. Questions 1–5 are needed **before implementation** of the section they name.

1. **Background location for an automatic restart (section D, AC 22, 25).** (a) Never request `ACCESS_BACKGROUND_LOCATION` (NAV-005 AC 8 unchanged). Guidance restores when the app is opened or the AC 22 notification is tapped, and silently only where Android allows it. (b) Request background location so that the service can restart guidance on its own (an extra permission prompt, a Play policy declaration, a privacy cost). **Recommendation: (a)** (working assumption).
2. **Store the active route on the device for offline restore (AC 16, 20).** (a) Store the destination, the options **and the route** while guidance runs, deleted on normal end or expiry. A restore then works offline on the stored route, which matters for countryside drivers. (b) Store only the destination and the options. A restore always sends a route request and needs a network. **Recommendation: (a)** (working assumption).
3. **Battery exemption (AC 27).** (a) Open the system battery settings; the user changes the setting there (no extra permission, no Play policy risk). (b) Show the system "allow" dialog directly with `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (one tap, but subject to Play policy review). **Recommendation: (a)** (working assumption).
4. **Meaning of «Автомат» (section H; replaces part of [D63](../decisions.md)).** (a) «Автомат» follows sunrise/sunset everywhere in the app, and the system dark-theme setting no longer applies (already planned in `navigation-ux.md` and `map-style.md`, no new string). (b) Sunrise/sunset only during guidance, and the system theme outside guidance. (c) Keep «Автомат» = system theme and add a fourth option for sunrise/sunset (new glossary row, settings change). **Recommendation: (a)** (working assumption). If the PO chooses (a), the BA logs a new decision row that supersedes D63 for the meaning of «Автомат».
5. **Restore behaviour and window (AC 18, 21).** (a) Resume automatically within **30 min** of the last heartbeat, with the «Замчлал сэргэлээ» notice and «Дуусгах» available. (b) Ask first with «Үргэлжлүүлэх» / «Дуусгах» (needs a new question text in the glossary). (c) A different window (10 or 60 min). **Recommendation: (a), 30 min** (working assumption).
6. **Lock-screen view setting and voice over call audio (AC 8, 31).** (a) No settings in this story: the guidance screen always shows over the lock screen during guidance, and prompts never use the call channel. (b) Add settings for either (new glossary rows). **Recommendation: (a).**
7. **Items the design docs call "NAV-012" that are not in the agreed scope:** travelled-route trimming and manoeuvre arrows on the route line (`map-style.md`), and the R8 keep rules (ADR-0009). (a) Trimming and arrows become a separate draft item through triage; R8 stays a technical item for the architect before any release build. (b) Add them to NAV-012 (+M). **Recommendation: (a).**
8. **Priority confirmation.** The PO said "go ahead" without naming a priority, and the story records **must** (Phase 1 beta) as proposed by the orchestrator. **Recommendation: confirm must.** The BA writes the decision-log row after the NAV-011 rows (D106–D108) land.

## Traceability
Artefacts to be created or updated (none exist yet for NAV-012 on 2026-10-03): UX NAV-012 screen spec or an update of the NAV-005 screen spec S5, S7 and S8, plus `navigation-ux.md` (theme) and `map-style.md` §7.4; architect ADR-0009 amendment or a new ADR (B-A1–B-A8); test plan `docs/qa/test-plans/NAV-012.md`; code `mobile/android/**` in NAV-012 packages; tests `mobile/android/app/src/test/**`.

| AC | Screen spec / flow | API operation | ADR | Code | Test | Issues |
|---|---|---|---|---|---|---|
| AC1–7 | S8 notification (rich layout), S5 voice toggle | — | B-A2, B-A5 | TBD | Robolectric (TBD) | NAV-005 AC 13, 15, 17, 37 |
| AC8–12 | Guidance over the lock screen, arrival panel | — | B-A7 | TBD | Robolectric (flags), real device (AC 52) | NAV-005 AC 18, 19, 55 |
| AC13–15 | — | — | B-A2 | TBD | Robolectric (TBD); replaces `GuidanceServiceTest.swipeAwayEndsGuidance` | NAV-005 AC 20 (replaced) |
| AC16–25 | Restore notice B3, restore notification B4/B5 | `postRoute` (only as a NAV-005 reroute) | B-A1, B-A2, B-A3 | TBD | JVM + Robolectric with a fake clock (TBD) | NAV-005 AC 43–54, 67; Open questions 1, 2, 5 |
| AC26–30 | Battery hint on the route preview, settings row B1 | — | — | TBD; `mobile/android/README.md` | Robolectric with a fake power manager (TBD) | Open question 3; glossary B1, B2 |
| AC31–34 | — | — | B-A4 | TBD | JVM/Robolectric (TBD), real device | NAV-005 AC 34, 36 |
| AC35–39 | — | — | B-A4 | TBD | JVM with a fake audio-mode source (TBD) | NAV-005 AC 32, 36, 39 |
| AC40–44 | S7 theme section | — | B-A6 | TBD | JVM (NOAA fixture, TBD) | D63 (Open question 4), NAV-005 AC 58–59 |
| AC45–49 | — | all | B-A2, B-A3 | TBD | interceptor, log/storage scan, manifest scan, strings check (TBD) | NAV-005 AC 61, 65–68; NAV-011 coordination |
| AC50–52 | — | — | — | `mobile/android/README.md` | Gradle, QA report (TBD) | Light-QA run instructions 2026-10-03 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-03 | NAV-012 feature request (orchestrator, feature-delivery; PO "go ahead" in chat) | Created from the backlog draft row: 52 AC in sections A–J (rich notification, lock-screen view, continue after swipe-away, restore after process death, OEM battery-saver guidance with in-app hint and README, Bluetooth/car audio, phone calls with catch-up, automatic sunrise/sunset theme, privacy/strings/permissions/regression, light-QA verification with a real-device list). Design questions B-A1–B-A8 for the architect. Glossary rows B1–B5 added in section 2.5 (`needs native review`). Priority **must** (Phase 1 beta) as proposed by the orchestrator (decision-log row pending until the NAV-011 rows land). Size M → L. A table lists the NAV-005 AC this changes when it ships (AC 20 replaced; S8 content, AC 36, AC 58 meaning, AC 67 exception); NAV-005 itself is not edited (mid-flight rule). Eight non-blocking open questions with working assumptions | Refine NAV-012 to `ready` for UX and the architect, verifiable in this environment with JVM/Robolectric tests and an explicit real-device list |
