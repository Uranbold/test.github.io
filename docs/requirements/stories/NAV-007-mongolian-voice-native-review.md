---
id: NAV-007
title: Native-speaker review of Mongolian voice guidance and turn instructions (Valhalla mn-MN + app copy, real TTS, upstream fixes)
phase: 1
priority: must   # PO-confirmed 2026-09-30 (D17)
size: L
needs_design: true
needs_backend: true
needs_mobile: true
status: ready    # since 2026-09-30. Start conditions for AC 5: the PO names the recruiting partner and the panel budget (Open question 7)
---

# NAV-007: Native-speaker review of Mongolian voice guidance and turn instructions

## Story
As a **UB commuter by car** (and as a **taxi / delivery driver**), I want **spoken and on-screen turn instructions in natural, correct Mongolian that I understand the first time I hear them**, so that **I can follow the route without looking at the screen, and I trust the app enough to use it instead of foreign-language navigation apps**.

Secondary personas:
- **Pedestrian**: walking prompts («… алх», unnamed footways, crossings).
- **Intercity / countryside driver**: long "continue" prompts, heading at start, and TTS availability with weak or no signal.
- **Tourist (English UI)**: must see no change to `en-US` output (AC 15).

## Context
- Research `docs/osm-navigation-research.md` §4.7 (voice via device TTS, "Mongolian TTS voice availability varies by device. Test it."), §9 items 1 and 7 ("Review the translation quality and contribute fixes upstream", "check Mongolian voice availability on Android/iOS"), §7 Phase 1 (MVP includes "Mongolian voice").
- NAV-001 risk R7 and out-of-scope item: "Native-speaker review is a separate task, with fixes contributed upstream". This is that task.
- PO-approved language policy: voice, banners and UI are Mongolian first, and the **glossary** (`docs/requirements/glossary.md`) fixes one approved Mongolian term per concept. This story is the review that approves or revises every Mongolian term there.
- **PO pre-review (2026-09-29).** The PO, who is **one** native reviewer, rated all 90 glossary rows on the review page (82 approve, 4 change, 4 unsure) and answered the follow-up questions the same day. The glossary now shows 83 rows `PO-approved 2026-09-29 (panel pending)` and 7 rows `PO-revised 2026-09-29 (panel pending)`: C4 voice ordinals «нэгдүгээр / хоёрдугаар …», C7 screen unit «км/цаг», Navigation «Замчлал», Origin «эхлэх цэг» plus the default label «Миний байршил», Enter roundabout «… хоёрдугаар гарцаар …», Recenter «Байршил руу буцах», and Petrol station «ШТС» on screen with «шатахуун түгээх станц» in voice. This is **input** to the panel, not a substitute for it. AC 2 and AC 5–8 still apply to every row. For `PO-revised` rows the panel rates the PO's wording, and the "previous proposal" in Notes is shown as the alternative.
- **PO decisions of 2026-09-30** (`docs/requirements/decisions.md`):
  - D17: priority **must / Phase 1**, finished before NAV-005 is released to any external user.
  - D18: the panel is **paid drivers recruited through a taxi or delivery partner, plus a paid editor**. The partner and the budget amount are not named yet (Open question 7).
  - D19: the PO is **not** one of the ≥ 5 panel members (AC 5).
  - D20: if the panel disagrees with the PO's wording, the item goes back to the PO for the **final call** (AC 19).
  - D21: «Явах чиглэл дээшээ» (heading up) is PO-approved as the pair of «Хойд зүг дээшээ». It is a new glossary row, and the panel rates it.
  - D22: «навигаци», «Төвлөрүүлэх», «км/ц» as a unit and the voice ordinal form «хоёр дахь» are marked Avoid (AC 16).
  - D23: the TTS fallback is decided after AC 9. The minimum safe behaviour is on-screen text plus a chime.
  - D24: platform order is **Android first, then iOS** (AC 9–10).
  - D9 (NAV-008): if the staging host is abroad, the PO's legal counsel reviews the personal-data law **before** outside testers' traffic goes there. Panel members are outside testers, so device sessions on such a staging host (AC 9–12) wait for NAV-008 AC 24.
- Valhalla is pinned at **3.9.0** (ADR-0002). Instructions come from Valhalla `language=mn-MN` (`bannerInstructions`, `voiceInstructions`) via `postRoute` / `getRoute`. Client-generated prompts (off-route, recalculating, GPS lost, no connection) come from our resource files.

### Pre-review findings in Valhalla 3.9.0 `locales/mn-MN.json` (BA desk check, 2026-09-29)
The BA collected these from the raw file at tag `3.9.0` through a web fetch. QA must re-confirm them at byte level (AC 4) before they are treated as facts.

| # | Key / phrase | Finding | Effect on users |
|---|---|---|---|
| F1 | `enter_roundabout_verbal` 3, 6, 11, 14 | Text is **English** ("Enter the roundabout and take the <ORDINAL_VALUE> exit onto…") | The driver hears English at roundabouts whose exit street has a begin/continue name |
| F2 | `enter_roundabout_verbal` 10 | Placeholder written `<ORDINAL VALUE>` (space, not underscore) | The exit number is not substituted. Literal or garbled text may be spoken |
| F3 | `bear` 2 | Typo «Үргэлжүүлэн» → should be «Үргэлжлүүлэн» | Misspelt banner. TTS may mispronounce it |
| F4 | `empty_street_name_labels` «явган хүний зам» (all copies) | Contains zero-width space(s) U+200B | Possible TTS pause or glitch. String comparisons and search fail |
| F5 | `sharp` 1, `sharp_verbal` 1 | «…-д огцом <RELATIVE_DIRECTION> гарга.» uses «гарга» ("take out") instead of «эргэ» ("turn") | Wrong meaning |
| F6 | `relative_directions` and `cardinal_directions` | Bare «зүүн»/«баруун» used for both left/right **and** east/west | «Зүүн руу чиглүүл.» (head east) can be heard as "head left". Safety-relevant (glossary C2) |
| F7 | Many phrases | Mixed register: bare imperative («эргэ», «яв», «гар»), -аарай («хийгээрэй»), -х («эргэх»), -нэ («эргэнэ») | Sounds curt and inconsistent (glossary C1) |
| F8 | Phrases with `<STREET_NAMES>-р`, `-д`, `-с`, `-н`, and «руу»/«рүү» | Case suffixes are attached with a hyphen, ignoring the name's vowel harmony | Ungrammatical with many names. TTS may read the hyphen (glossary C5) |
| F9 | `ordinal_values` «1-р»…«10-р» | Abbreviated ordinals in **voice** text | TTS may say "хоёр эр" instead of «хоёрдугаар» (glossary C4) |
| F10 | `approach_verbal_alert` «<LENGTH>, <CURRENT_VERBAL_CUE>» | No locative: «300 метр, баруун эргэ.» | Unnatural. Should read «300 метрт …» (glossary C3) |

F1–F5 are **defects**, and fixing them upstream is uncontroversial. F6–F10 are **style and structure** issues. Upstream may accept or reject them, so our product must carry its own fix regardless.

### Manoeuvre and prompt coverage (used by AC 3, 9 and 12)
| Group | Items that must be covered |
|---|---|
| Start | car (`start` 4–6), walk (`start` 8–10), with and without street name |
| Straight | continue, continue on named road, post-transition «… үргэлжлүүлэн явна уу» |
| Turns | slight L/R, turn L/R, sharp L/R, U-turn |
| Forks | keep left, keep straight, keep right, keep-to-stay-on |
| Motorway-like | merge (with/without side), ramp on/off, exit (number/branch/toward sign) |
| Roundabout | enter + exit 1, 2, 3, 4; named-exit variants (`enter_roundabout[_verbal]` 2, 3, 5, 6, 10, 11, 13, 14); exit roundabout |
| Destination | arrive ahead, on left, on right, named destination, approaching alert |
| Distances | «<N> километр», «1 километр», «<N> метр», «10 метрээс бага», plus at least one decimal km value |
| Chaining | multi-cue «дараа нь» (with and without distance) |
| Client-generated | off-route, recalculating, GPS lost, GPS restored, no connection, no route found |
| Unnamed features | footway, cycleway, crossing, steps, bridge, tunnel (`empty_street_name_labels`) |

## Acceptance criteria

### A. Review inventory
1. **Given** Valhalla 3.9.0 `locales/mn-MN.json` and `locales/en-US.json`, **When** the review inventory is produced, **Then** it has one row per phrase string for every instruction key used by `auto`, `taxi` and `pedestrian` costing, **plus** every value of `relative_directions`, `cardinal_directions`, `ordinal_values`, `metric_lengths` and `empty_street_name_labels`. Each row holds the key, the phrase ID, the `en-US` text and the `mn-MN` text. Transit-only, ferry-only and bicycle-only phrases are listed separately as out of scope. The in-scope row count **equals** the count produced by a script over the JSON, and the script and count are recorded with the inventory.
2. **Given** our `mn` resource files for every banner, voice and navigation-screen string, and every glossary row in sections 1–8 with status `needs native review`, `PO-approved … (panel pending)`, `PO-revised … (panel pending)` or `PO-delegated … (panel pending)`, **When** the inventory is produced, **Then** **100 %** of them are included. The number of resource keys in the inventory equals the key count of the `mn` files at the reviewed commit, which is recorded.
3. **Given** the coverage table above, **When** the sample set is built, **Then** it contains **≥ 2 rendered examples per item**. At least one uses a real Ulaanbaatar street or place name. Examples are taken from real Valhalla `mn-MN` responses on UB routes (NAV-001 reference points P1–P6 plus added points, all recorded with coordinates). Where UB has no real instance (e.g. numbered motorway exits), a template rendered with a real UB name is allowed and marked `synthetic`. The sample set has **≥ 20 real routes**.
4. **Given** findings F1–F10, **When** QA checks the raw 3.9.0 file at byte level (e.g. `jq` plus a hex dump of the affected strings), **Then** each finding is marked `confirmed` or `not reproduced` with the evidence command recorded. Only confirmed findings go into AC 13 and AC 17.

### B. Reviewer panel and written review
5. **Given** the panel is recruited, **When** its composition is recorded, **Then** it has **≥ 5 native Mongolian speakers aged 18+**, including:
   - **≥ 3** who drive in Ulaanbaatar on **≥ 4 days a week** with **≥ 2 years** of driving there
   - **≥ 1** professional taxi or delivery driver
   - **≥ 1** who drives intercity or in the countryside at least monthly
   - **≥ 1** professional editor, translator or teacher of Mongolian (the "editor")
   - both age bands 18–34 and 35+

   The PO is **not** counted among the ≥ 5 and does not rate strings in the panel (PO decision D19). The PO pre-review stays input only, and the PO makes the final call only on disagreements (AC 19). Drivers are recruited through a taxi or delivery partner and paid, and the editor is paid (D18).

   Reviewers are recorded only by pseudonymous ID (R1, R2, …) plus their profile attributes, and written consent is held outside the repo. No names, phone numbers or other personal data are committed.
6. **Given** the inventory (AC 1–2) and the sample set (AC 3), **When** the written review is done, **Then** every in-scope string is rated by **≥ 3 reviewers, one of whom is the editor**, on:
   - (a) understood correctly: Y/N
   - (b) naturalness: 1–5
   - (c) spelling and grammar correct: Y/N
   - (d) a proposed wording whenever (b) ≤ 3 or (c) = N
7. **Given** the ratings, **When** each string is classified, **Then** it is `accepted` only if its **median naturalness is ≥ 4**, **no reviewer** marked it misunderstood, and the editor marked (c) = Y. Otherwise it is `revise`. Every `revise` string ends with **one final wording** agreed by a majority of its reviewers **and** the editor, and that wording is re-rated under the same rule until it is `accepted`. For strings that have a PO wording, AC 19 applies on top of this rule.
8. **Given** the review is complete, **When** the glossary is checked, **Then** **0** rows used by Phase 1 UI or voice still have status `needs native review`, `PO-approved … (panel pending)`, `PO-revised … (panel pending)` or `PO-delegated … (panel pending)`. Each such row has a Status cell that fully matches `^(approved|revised) \d{4}-\d{2}-\d{2}$`. PO pre-review statuses do not count. The glossary change log lists the reviewer IDs and resolves the open conventions: decimal separator (C3), ordinal form (C4), name pattern (C5) and the alternatives flagged "for review".

### C. Real TTS on devices
9. **Given** a device matrix of **≥ 2 Android** phones (at least one budget model widely sold in Mongolia, and at least one on Android 12 or later) and **≥ 2 iOS** phones (the current and the previous major iOS version), **When** Mongolian TTS availability is checked, **Then** a report lists for each device:
   - every installed or installable TTS engine and voice for `mn` / `mn-MN`
   - whether it is present out of the box
   - whether it speaks with the network off (airplane mode)
   - what the app would do with no `mn` voice (silent, fallback voice or error)

   A device with no `mn` voice is a **recorded result, not a failure** of this AC, and it raises the TTS fallback question (Open question 4).

   **Order (PO decision D24, Android first):** the Android devices are tested and reported first, and the Android results alone are enough to trigger the D23 TTS-fallback decision for the Android release. The iOS rows are still required before sign-off (AC 18), unless the PO splits them into a follow-up tied to the iOS release (Open question 6).
10. **Given** the final wording synthesised on each device that has a usable `mn` voice (or on the fallback the PO chooses), at the default speech rate, played in a moving car or with **≥ 70 dB(A)** road-noise playback at the listener's position, **When** **≥ 3** driver reviewers each hear **≥ 40 prompts** in random order that cover every item in the coverage table, **Then**:
    - **≥ 95 %** of prompts are identified correctly (manoeuvre type + direction + exit number or distance where present)
    - **0** left/right or east/west confusions occur
    - **0** prompts are flagged by ≥ 2 reviewers as mispronouncing a number, unit, ordinal, "GPS" or a street name

    Android devices are tested first (D24). The same thresholds apply to iOS when its rows are run (AC 9 order note). If no device has a usable `mn` voice and the PO has not yet chosen a fallback, the minimum behaviour is on-screen text plus a chime (D23), and this AC waits for the PO's fallback decision.
11. **Given** any single-cue voice prompt from the sample set, **When** it is synthesised at the default rate on the reference Android device, **Then** it lasts **≤ 5.0 s**. A multi-cue («дараа нь») prompt lasts **≤ 8.0 s**. These are BA-proposed limits (at 60 km/h, 5 s ≈ 83 m) for UX to confirm in the NAV-005 voice spec.

### D. Fixes in our product
12. **Given** the agreed wording, **When** the golden announcement set (all `mn-MN` banner `primary.text` and voice `announcement` strings for the **≥ 20** reference routes from AC 3, plus the client-generated prompts) is generated from the product build, **Then** every string matches the agreed wording for its manoeuvre type, and the snapshot is committed as a regression test owned by QA.
13. **Given** the golden announcement set, **When** the automated wording checks run, **Then** all of these hold:
    - **0** Latin letters `[A-Za-z]` outside OSM-sourced names and the token `GPS`. This catches F1-type English leaks.
    - **0** `<` or `>` characters (unfilled placeholders, F2)
    - **0** characters U+200B–U+200D or U+FEFF (F4)
    - **0** occurrences of the confirmed misspellings from AC 4 (e.g. «Үргэлжүүлэн», F3)
    - every relative «зүүн» / «баруун» in a turn, keep, merge, exit or destination prompt is followed by one of the glossary forms («тийш», «талаа», «талд», «талаас», «эгнээ…»), and every cardinal direction is followed by «зүг» (F6, C2)
    - voice strings contain **0** matches of `\d\s?(м|км)(\s|-|/|$)` (this also catches «км/цаг» and «км/ц»), **0** matches of `\d+-р`, **0** matches of `(\d+|нэг|хоёр|гурав|дөрөв|тав|зургаа|долоо|найм|ес|арав)\s(дахь|дэх)` (old ordinal form; standalone «дахь»/«дэх» meaning "located in" is not matched) and **0** occurrences of «ШТС» (C3, C4, C7, F9, glossary "Petrol station")
    - banner and UI strings contain **0** matches of `км/ц(?!аг)`. The screen speed unit is «км/цаг» (C7).
14. **Given** a route that triggers each confirmed defect (F1 roundabout variants 3/6/11/14, F2 variant 10, F3 `bear` 2, F5 `sharp` 1, F4 an unnamed footway), **When** `postRoute` is called with `language=mn-MN` through the product stack, **Then** the response contains the agreed Mongolian wording for each, **and** the checks in AC 13 pass for that response. If no UB route triggers a variant (marked `synthetic` in AC 3), the check is instead run on the served `mn-MN` template for that phrase ID, rendered with a real UB name.
15. **Given** the same golden routes requested with `language=en-US`, **When** the output is compared with the pre-change snapshot, **Then** it is **byte-identical**. This proves the fixes are scoped to `mn-MN`.
16. **Given** every term marked "Avoid" in the glossary Notes column, **When** the `mn` resource files and the golden announcement set are scanned, **Then** there are **0** occurrences (e.g. «зогсоол» used for waypoint, «карт», «км/ч», «траффик»). This includes the old wordings the PO marked Avoid on 2026-09-30 (D22), matched as follows:
    - «навигаци» in any case form: the stem `навигаци`, case-insensitive (catches «навигацийн», «навигацид»)
    - «Төвлөрүүлэх»: the whole word, case-insensitive
    - «км/ц» as a unit: only `км/ц(?!аг)`, because «км/ц» is a prefix of the approved «км/цаг» (C7)
    - the old voice ordinal «хоёр дахь» and every other «<number> дахь/дэх»: the ordinal regex from AC 13, applied to voice strings. A standalone «дахь»/«дэх» meaning "located in" is not matched

### E. Upstream contribution
17. **Given** the agreed fixes, **When** the upstream contribution is prepared, **Then** within **10 working days** of sign-off (AC 18):
    - a pull request against `valhalla/valhalla` changing `locales/mn-MN.json` is opened
    - it contains **at least** every confirmed defect F1–F5, with style changes (F6–F10) in the same or a separate PR
    - it passes Valhalla's CI
    - it describes the review method with anonymised reviewer counts only
    - its URL is recorded in the Traceability table

    A merge is **not** required for this story to be done, because the timing is outside our control. A follow-up backlog item is created to remove our local override once we upgrade to a Valhalla release that contains the merged fix.

### F. Sign-off
18. **Given** AC 1–17 and AC 19 are met, **When** sign-off happens, **Then** a sign-off record exists in the QA report. It lists:
    - date
    - reviewer IDs and profile mix (AC 5)
    - Valhalla version and app build reviewed
    - device matrix (AC 9)
    - listening-test scores (AC 10)
    - the upstream PR URL
    - every PO final call from AC 19 (string, PO wording, panel wording, choice, date)
    - the PO's recorded approval

    The glossary change log references the same date.

### G. PO final call on disagreements (added 2026-09-30, PO decision D20)
19. **Given** a string that has a PO wording (a glossary row whose status was `PO-approved … (panel pending)`, `PO-revised … (panel pending)` or `PO-delegated … (panel pending)` when the review started, including the heading-up row «Явах чиглэл дээшээ» added under D21), **When** the panel classifies the PO wording `revise` under AC 7, or the panel's agreed final wording differs from the PO wording, **Then**:
    - the string goes back to the PO before its glossary row is closed, and the panel's wording does **not** replace the PO wording without the PO's choice
    - the review record shows, for both wordings: the text, the median naturalness, the number of "misunderstood" marks, the editor's spelling/grammar result and the editor's note
    - the PO's choice is final, and it is recorded with its date in the review record and in the glossary Notes as "PO final call (D20), <date>"
    - the glossary Status becomes `approved YYYY-MM-DD` if the PO keeps the PO wording, or `revised YYYY-MM-DD` if the PO takes the panel wording, so AC 8 still holds
    - the chosen wording still has to pass AC 10, AC 13 and AC 16. If it fails AC 10, it goes back to the PO again together with the listening-test results

    Strings with no PO wording (Valhalla phrases outside the glossary) follow AC 7 alone.

## Edge cases
- **GPS lost / restored:** client prompts «GPS дохио тасарлаа» / «GPS дохио сэргэлээ» are in the inventory. The TTS pronunciation of "GPS" is checked in AC 10.
- **No network:** online-only TTS voices go silent offline, so AC 9 records offline behaviour. «Интернэт холболт алга» is reviewed. Voice for already-downloaded instructions must still play (NAV-005 behaviour, verified here only as a TTS capability).
- **Off-route / reroute:** «Та маршрутаас гарлаа», «Маршрутыг дахин тооцоолж байна» are reviewed. There is no Valhalla string for these.
- **Unpaved roads and ger districts:** unnamed tracks are common, so `empty_street_name_labels` and prompts with no street name must read naturally. At least 3 sample routes pass through the P6 Chingeltei ger area or similar.
- **No results / no route:** «Илэрц олдсонгүй», «Маршрут олдсонгүй» are reviewed as UI copy.
- **Cyrillic/Latin names:** where OSM `name` is Latin only (e.g. a hotel or mall), the Mongolian TTS reads a Latin string. The listening test includes **≥ 2** such names, and the result is recorded (fallback order `name:mn` → `name` → `name:en`, glossary C8).
- **Names with ordinals and numbers:** «3, 4-р хороолол», «10-р хороолол», «120 мянгат», «1-р хороо» are in the sample set. The TTS must produce the correct ordinal or number form.
- **Number forms:** attributive numbers before units («гурван зуун метр», not «гурван зуу метр») are checked in AC 10. If the TTS fails, the voice text uses spelled-out numbers.
- **Long names and chaining:** the longest street name in the sample set is included in a multi-cue prompt, and AC 11 applies.
- **Winter:** closed windows and heater fan noise. The ≥ 70 dB(A) noise condition in AC 10 covers this. The winter recording or playback is noted in the report.
- **English UI (tourist):** `en-US` output must not change (AC 15).
- **Countryside / weak signal:** long «… километр үргэлжлүүлэн явна уу» prompts and cardinal start headings («хойд зүг рүү явна уу») are in the sample set.

## Data dependencies & risks
| # | Risk | Impact | Mitigation / owner |
|---|---|---|---|
| R1 | **No usable Mongolian TTS voice** on some or all target devices | Voice guidance is silent or spoken by a non-Mongolian voice. This is the biggest product risk | AC 9 measures it (Android first, D24). The fallback is decided by the PO after AC 9, and the minimum is on-screen text plus a chime (D23) |
| R2 | **Valhalla templating has no Mongolian morphology** (F8) | Some grammatical fixes cannot be expressed in `mn-MN.json` alone | Architect decides where overrides live (request below). Reviewers prefer name-standalone patterns (C5) |
| R3 | **Valhalla locales may be compiled into the binary** | A local fix may need a custom Valhalla image, not a mounted file | Architect confirms. Backend builds it if needed |
| R4 | **Upstream acceptance and release timing** | Our override may be needed for months | Local override. Follow-up item to remove it after upgrade (AC 17) |
| R5 | **`name:mn` coverage** in OSM | Latin names read by Mongolian TTS sound wrong | Recorded in the edge case. Mapping programme later |
| R6 | **Sparse `destination`, `junction:name`, exit refs** in UB | Many phrase variants rarely trigger, so real examples are scarce | Synthetic examples allowed (AC 3) |
| R7 | **Reviewer recruitment** (drivers' time, cost) | Delays the review | Panel model decided (D18: paid drivers through a taxi/delivery partner, paid editor). The partner and budget amount are still to be named (Open question 7) |
| R9 | **Personal-data law if staging is abroad** (NAV-008 D9) | Panel device sessions on a foreign staging host wait for the legal review | NAV-008 AC 24. Alternatively run device sessions against an in-country host or a local build. PO, architect |
| R8 | **Sample-set bias to central UB** | Countryside and ger-district wording is under-tested | AC 3 and the edge cases require ger-area and countryside examples |

## Out of scope
- Review of the English (`en-US`) narrative, which only gets a no-regression check (AC 15).
- Building a server-side neural TTS (e.g. Piper) or recording voice packs. If the PO chooses one, it becomes a separate story.
- Transit, ferry and bicycle phrases.
- Lane-guidance wording (Phase 2). Glossary terms exist but are reviewed with that story.
- Search-result wording beyond the glossary terms.
- Changing trigger distances or timing of prompts (NAV-005 / Ferrostar configuration).

## Open questions
Decisions for the PO or user:
1. **Reviewer panel and compensation.** Options:
   - (a) paid panel of 5–7 plus one paid editor, recruited through a taxi or delivery partner
   - (b) volunteers from the team's network plus a paid editor
   - (c) a crowd survey (larger, less controlled)

   *Recommendation: (a). The listening test needs committed drivers, and the editor is essential for spelling and grammar.* **Decided 2026-09-30 (D18): (a).** The PO is not a panel member (D19). The partner and budget amount are Open question 7.
2. **Priority and phase.** BA proposes **must, Phase 1**, finished before NAV-005 is released to any external user. Options:
   - (a) must / Phase 1
   - (b) should / Phase 1, releasing NAV-005 with known wording issues
   - (c) Phase 2

   *Recommendation: (a). F1 (English at roundabouts) and F6 (left/east ambiguity) are user-visible and safety-relevant.* **Decided 2026-09-30 (D17): (a).**
3. **Voice register.** The PO example «эргэнэ үү» implies the polite form (glossary C1). Options:
   - (a) polite -на уу throughout
   - (b) short imperative on banners, polite in voice
   - (c) let the panel choose

   *Recommendation: (a), confirmed by the panel.* The PO approved C1 (polite form) in the pre-review on 2026-09-29. The panel still confirms it.
4. **TTS fallback if AC 9 finds no usable `mn` voice** on target devices. Options:
   - (a) server-side neural TTS (e.g. Piper with a Mongolian model)
   - (b) pre-recorded prompt packs for fixed phrases, with names shown only on banners
   - (c) text-only banners plus a chime, until (a) or (b) exists

   *Recommendation: decide after the AC 9 results. Keep (c) as the minimum safe behaviour.* **Decided 2026-09-30 (D23):** the choice between (a), (b) and (c) is made after the AC 9 device results. (c) is the minimum until then. The final choice is still owed (backlog owed decision 7).
5. **Platform order** (already owed, backlog decision 1). This affects which devices come first in AC 9 and 10. **Decided 2026-09-30 (D24): Android first, then iOS.** AC 9 and AC 10 test Android first.
6. **iOS device checks and sign-off** (new 2026-09-30, a consequence of D24). With Android first, the iOS app may come much later than NAV-005 on Android. Options:
   - (a) keep the iOS rows of AC 9–10 in NAV-007, so sign-off (AC 18) waits for iOS devices
   - (b) sign off NAV-007 on Android results, and move the iOS rows of AC 9–10 into a follow-up story that must finish before the iOS release

   *Recommendation: (b). D17 requires NAV-007 before the NAV-005 external release, and that release is Android. Until the PO decides, the AC stay as written (option a).*
7. **Recruiting partner and panel budget** (new 2026-09-30, follows D18). D18 fixes the panel model but not which taxi or delivery partner recruits the drivers, or how much the panel and the editor are paid. Options:
   - (a) the PO names a partner and asks it for a quote for 5–7 drivers (written review plus one listening session each) and one editor
   - (b) the PO sets a fixed budget ceiling first, and the team sizes the panel to it (minimum 5 per AC 5)

   *Recommendation: (a). A quote reflects real driver time. This is a partner and budget decision for the PO. AC 5 recruitment cannot start without it.*

Technical question for the architect (not a user decision): where the `mn-MN` overrides live. Options:
- (a) a patched `mn-MN.json` in our own Valhalla build
- (b) client-side instruction generation from manoeuvre type using our resource files
- (c) gateway post-processing

*BA view: (a) for F1–F5 and simple wording, with (b) only if F8 can't be solved in templates.*

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC1 | — | — | — | inventory script (QA, TBD) | |
| AC2 | NAV-005 navigation screen spec (TBD) | — | `mn` resource files (TBD) | inventory script (QA, TBD) | |
| AC3 | — | `postRoute` | — | sample-set generator (QA, TBD) | |
| AC4 | — | — | — | byte-level check (QA, TBD) | |
| AC5 | — | — | — | panel record (QA report, TBD) | D18, D19 2026-09-30 |
| AC6 | — | — | — | review sheet (QA report, TBD) | |
| AC7 | — | — | — | review sheet (QA report, TBD) | |
| AC8 | — | — | `docs/requirements/glossary.md` | glossary status check (anchored regex, see AC 8) | PO pre-review 2026-09-29 (input only, not the panel) |
| AC9 | — | — | TTS harness or NAV-005 build (mobile, TBD) | device report (QA, TBD) | D23, D24 2026-09-30 (Android first); Open question 6 |
| AC10 | NAV-005 voice spec (TBD) | — | TTS harness or NAV-005 build (mobile, TBD) | listening test (QA, TBD) | D23, D24 2026-09-30 |
| AC11 | NAV-005 voice spec (TBD) | — | — | prompt-duration test (QA, TBD) | |
| AC12 | — | `postRoute`, `getRoute` | override location per architect (TBD) | golden announcement snapshot (QA, TBD) | |
| AC13 | — | `postRoute` | — | wording lint (QA, TBD) | |
| AC14 | — | `postRoute` | override location per architect (TBD) | defect regression tests (QA, TBD) | |
| AC15 | — | `postRoute` (`language=en-US`) | — | en-US snapshot (QA, TBD) | |
| AC16 | — | — | `mn` resource files (TBD) | avoid-term scan (QA, TBD) | D22 2026-09-30 (matching rules for the old wordings) |
| AC17 | — | — | upstream PR (URL TBD) | Valhalla CI | |
| AC18 | — | — | — | sign-off record (QA report, TBD) | now includes the AC 19 PO final calls |
| AC19 | — | — | `docs/requirements/glossary.md` (Status and Notes) | review record + glossary status check (QA report, TBD) | D20 2026-09-30 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 | — | Created. Includes the BA desk-check findings F1–F10 against Valhalla 3.9.0 `mn-MN.json`. | PO-approved language policy (glossary terms need native approval). Follows up NAV-001 R7 ("native-speaker review is a separate task"). |
| 2026-09-29 | PO pre-review of the glossary (review page + follow-up in chat) | Added the Context bullet "PO pre-review". **AC 2:** the inventory now also includes rows with `PO-approved … (panel pending)` / `PO-revised … (panel pending)`, so the panel scope does not shrink to unreviewed rows. **AC 8:** PO statuses do not count as done, and the status check uses an anchored regex (the PO statuses contain the substring "approved <date>"). **AC 13:** the voice unit regex now also catches «км/цаг» (a slash after «км» counts as a match), plus the old ordinal form «<number> дахь/дэх», and «ШТС»; a new check covers banner and UI «км/ц» (`км/ц(?!аг)`). **F9:** example changed to «хоёрдугаар». **Open question 3:** noted the PO's C1 pre-approval. The panel ACs (AC 5–8) are **unchanged in substance**. | The PO is one native reviewer, and the decisions (C4 -дугаар/-дүгээр, C7 «км/цаг», «ШТС» screen-only, «Замчлал», origin/recenter labels) must be reflected in the automated checks. Without the AC 2 fix, the PO statuses would have dropped 86 of 90 rows out of the panel inventory. |
| 2026-09-30 | PO decisions D17–D24, plus D9 from NAV-008 (`docs/requirements/decisions.md`, PO answer "all recommended", relayed by the orchestrator) | **Front matter:** priority must is PO-confirmed (D17), and status moves from `draft` to `ready`. **Context:** new bullet listing the decisions. **AC 5:** the PO is not counted among the ≥ 5 (D19), and the panel model is paid drivers through a partner plus a paid editor (D18). **AC 7:** points to AC 19. **AC 9–10:** Android first (D24), the iOS rows stay required for sign-off unless the PO splits them (Open question 6), and the D23 minimum (on-screen text plus a chime) is noted. **AC 16:** matching rules for the D22 Avoid wordings («навигаци», «Төвлөрүүлэх», `км/ц(?!аг)`, «<number> дахь/дэх» in voice). **AC 18:** requires AC 19 and lists the PO final calls. **New AC 19** (section G): disagreements between the panel and the PO wording go back to the PO, whose choice is final (D20), and AC 10, 13 and 16 still apply to the chosen wording. It is numbered after AC 18 so existing numbers stay stable. **Risks:** R1 and R7 updated, R9 added (legal gate if staging is abroad, D9). **Open questions** 1, 2, 4 and 5 marked decided, and 6 (iOS split) and 7 (partner and budget) added. **Traceability** AC5, AC9, AC10, AC16, AC18 updated and AC19 added | Status moves to `ready` because every open question that shapes the AC is decided (priority, panel model, TTS minimum, platform order). The remaining items do not change the AC: the partner and budget (Open question 7) block the start of AC 5 recruitment, Open question 6 has a default (the AC as written), and the architect's override-location decision is a technical dependency |
| 2026-10-01 | PO native review of glossary rows N1–N23 and A1–A13 ([D69](../decisions.md)) | **AC 2, AC 8, AC 19:** the new glossary status `PO-delegated … (panel pending)` is listed next to the other PO statuses. It is used for N14 and N15, whose wording the PO delegated to the team on 2026-10-01. No threshold, check or other AC changed | Without it, the two ramp rows would drop out of the panel inventory (AC 2) and could close without a panel status (AC 8). AC 19 applies because the PO delegated the wording but keeps the final call over product wording (D20) |
