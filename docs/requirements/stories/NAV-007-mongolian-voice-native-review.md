---
id: NAV-007
title: Native-speaker review of Mongolian voice guidance and turn instructions (Valhalla mn-MN + app copy, real TTS, upstream fixes)
phase: 1
priority: must   # proposed by BA; PO to confirm
size: L
needs_design: true
needs_backend: true
needs_mobile: true
status: draft
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
- PO-approved language policy: voice, banners and UI are Mongolian first, and the **glossary** (`docs/requirements/glossary.md`) fixes one approved Mongolian term per concept. Every Mongolian term there is currently `needs native review`. This story is the review that approves or revises them.
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
| F9 | `ordinal_values` «1-р»…«10-р» | Abbreviated ordinals in **voice** text | TTS may say "хоёр эр" instead of «хоёр дахь» (glossary C4) |
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
2. **Given** our `mn` resource files for every banner, voice and navigation-screen string, and every glossary row in sections 1–8 with status `needs native review`, **When** the inventory is produced, **Then** **100 %** of them are included. The number of resource keys in the inventory equals the key count of the `mn` files at the reviewed commit, which is recorded.
3. **Given** the coverage table above, **When** the sample set is built, **Then** it contains **≥ 2 rendered examples per item**. At least one uses a real Ulaanbaatar street or place name. Examples are taken from real Valhalla `mn-MN` responses on UB routes (NAV-001 reference points P1–P6 plus added points, all recorded with coordinates). Where UB has no real instance (e.g. numbered motorway exits), a template rendered with a real UB name is allowed and marked `synthetic`. The sample set has **≥ 20 real routes**.
4. **Given** findings F1–F10, **When** QA checks the raw 3.9.0 file at byte level (e.g. `jq` plus a hex dump of the affected strings), **Then** each finding is marked `confirmed` or `not reproduced` with the evidence command recorded. Only confirmed findings go into AC 13 and AC 17.

### B. Reviewer panel and written review
5. **Given** the panel is recruited, **When** its composition is recorded, **Then** it has **≥ 5 native Mongolian speakers aged 18+**, including:
   - **≥ 3** who drive in Ulaanbaatar on **≥ 4 days a week** with **≥ 2 years** of driving there
   - **≥ 1** professional taxi or delivery driver
   - **≥ 1** who drives intercity or in the countryside at least monthly
   - **≥ 1** professional editor, translator or teacher of Mongolian (the "editor")
   - both age bands 18–34 and 35+

   Reviewers are recorded only by pseudonymous ID (R1, R2, …) plus their profile attributes, and written consent is held outside the repo. No names, phone numbers or other personal data are committed.
6. **Given** the inventory (AC 1–2) and the sample set (AC 3), **When** the written review is done, **Then** every in-scope string is rated by **≥ 3 reviewers, one of whom is the editor**, on:
   - (a) understood correctly: Y/N
   - (b) naturalness: 1–5
   - (c) spelling and grammar correct: Y/N
   - (d) a proposed wording whenever (b) ≤ 3 or (c) = N
7. **Given** the ratings, **When** each string is classified, **Then** it is `accepted` only if its **median naturalness is ≥ 4**, **no reviewer** marked it misunderstood, and the editor marked (c) = Y. Otherwise it is `revise`. Every `revise` string ends with **one final wording** agreed by a majority of its reviewers **and** the editor, and that wording is re-rated under the same rule until it is `accepted`.
8. **Given** the review is complete, **When** the glossary is checked, **Then** **0** rows used by Phase 1 UI or voice still have status `needs native review`. Each such row shows `approved YYYY-MM-DD` or `revised YYYY-MM-DD`. The glossary change log lists the reviewer IDs and resolves the open conventions: decimal separator (C3), ordinal form (C4), name pattern (C5) and the alternatives flagged "for review".

### C. Real TTS on devices
9. **Given** a device matrix of **≥ 2 Android** phones (at least one budget model widely sold in Mongolia, and at least one on Android 12 or later) and **≥ 2 iOS** phones (the current and the previous major iOS version), **When** Mongolian TTS availability is checked, **Then** a report lists for each device:
   - every installed or installable TTS engine and voice for `mn` / `mn-MN`
   - whether it is present out of the box
   - whether it speaks with the network off (airplane mode)
   - what the app would do with no `mn` voice (silent, fallback voice or error)

   A device with no `mn` voice is a **recorded result, not a failure** of this AC, and it raises the TTS fallback question (Open question 4).
10. **Given** the final wording synthesised on each device that has a usable `mn` voice (or on the fallback the PO chooses), at the default speech rate, played in a moving car or with **≥ 70 dB(A)** road-noise playback at the listener's position, **When** **≥ 3** driver reviewers each hear **≥ 40 prompts** in random order that cover every item in the coverage table, **Then**:
    - **≥ 95 %** of prompts are identified correctly (manoeuvre type + direction + exit number or distance where present)
    - **0** left/right or east/west confusions occur
    - **0** prompts are flagged by ≥ 2 reviewers as mispronouncing a number, unit, ordinal, "GPS" or a street name
11. **Given** any single-cue voice prompt from the sample set, **When** it is synthesised at the default rate on the reference Android device, **Then** it lasts **≤ 5.0 s**. A multi-cue («дараа нь») prompt lasts **≤ 8.0 s**. These are BA-proposed limits (at 60 km/h, 5 s ≈ 83 m) for UX to confirm in the NAV-005 voice spec.

### D. Fixes in our product
12. **Given** the agreed wording, **When** the golden announcement set (all `mn-MN` banner `primary.text` and voice `announcement` strings for the **≥ 20** reference routes from AC 3, plus the client-generated prompts) is generated from the product build, **Then** every string matches the agreed wording for its manoeuvre type, and the snapshot is committed as a regression test owned by QA.
13. **Given** the golden announcement set, **When** the automated wording checks run, **Then** all of these hold:
    - **0** Latin letters `[A-Za-z]` outside OSM-sourced names and the token `GPS`. This catches F1-type English leaks.
    - **0** `<` or `>` characters (unfilled placeholders, F2)
    - **0** characters U+200B–U+200D or U+FEFF (F4)
    - **0** occurrences of the confirmed misspellings from AC 4 (e.g. «Үргэлжүүлэн», F3)
    - every relative «зүүн» / «баруун» in a turn, keep, merge, exit or destination prompt is followed by one of the glossary forms («тийш», «талаа», «талд», «талаас», «эгнээ…»), and every cardinal direction is followed by «зүг» (F6, C2)
    - voice strings contain **0** matches of `\d\s?(м|км|км/ц)(\s|-|$)` and **0** matches of `\d+-р` (C3, C4, F9)
14. **Given** a route that triggers each confirmed defect (F1 roundabout variants 3/6/11/14, F2 variant 10, F3 `bear` 2, F5 `sharp` 1, F4 an unnamed footway), **When** `postRoute` is called with `language=mn-MN` through the product stack, **Then** the response contains the agreed Mongolian wording for each, **and** the checks in AC 13 pass for that response. If no UB route triggers a variant (marked `synthetic` in AC 3), the check is instead run on the served `mn-MN` template for that phrase ID, rendered with a real UB name.
15. **Given** the same golden routes requested with `language=en-US`, **When** the output is compared with the pre-change snapshot, **Then** it is **byte-identical**. This proves the fixes are scoped to `mn-MN`.
16. **Given** every term marked "Avoid" in the glossary Notes column, **When** the `mn` resource files and the golden announcement set are scanned, **Then** there are **0** occurrences (e.g. «зогсоол» used for waypoint, «карт», «км/ч», «траффик»).

### E. Upstream contribution
17. **Given** the agreed fixes, **When** the upstream contribution is prepared, **Then** within **10 working days** of sign-off (AC 18):
    - a pull request against `valhalla/valhalla` changing `locales/mn-MN.json` is opened
    - it contains **at least** every confirmed defect F1–F5, with style changes (F6–F10) in the same or a separate PR
    - it passes Valhalla's CI
    - it describes the review method with anonymised reviewer counts only
    - its URL is recorded in the Traceability table

    A merge is **not** required for this story to be done, because the timing is outside our control. A follow-up backlog item is created to remove our local override once we upgrade to a Valhalla release that contains the merged fix.

### F. Sign-off
18. **Given** AC 1–17 are met, **When** sign-off happens, **Then** a sign-off record exists in the QA report. It lists:
    - date
    - reviewer IDs and profile mix (AC 5)
    - Valhalla version and app build reviewed
    - device matrix (AC 9)
    - listening-test scores (AC 10)
    - the upstream PR URL
    - the PO's recorded approval

    The glossary change log references the same date.

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
| R1 | **No usable Mongolian TTS voice** on some or all target devices | Voice guidance is silent or spoken by a non-Mongolian voice. This is the biggest product risk | AC 9 measures it. Fallback is a PO decision (Open question 4) |
| R2 | **Valhalla templating has no Mongolian morphology** (F8) | Some grammatical fixes cannot be expressed in `mn-MN.json` alone | Architect decides where overrides live (request below). Reviewers prefer name-standalone patterns (C5) |
| R3 | **Valhalla locales may be compiled into the binary** | A local fix may need a custom Valhalla image, not a mounted file | Architect confirms. Backend builds it if needed |
| R4 | **Upstream acceptance and release timing** | Our override may be needed for months | Local override. Follow-up item to remove it after upgrade (AC 17) |
| R5 | **`name:mn` coverage** in OSM | Latin names read by Mongolian TTS sound wrong | Recorded in the edge case. Mapping programme later |
| R6 | **Sparse `destination`, `junction:name`, exit refs** in UB | Many phrase variants rarely trigger, so real examples are scarce | Synthetic examples allowed (AC 3) |
| R7 | **Reviewer recruitment** (drivers' time, cost) | Delays the review | PO decision on panel and budget (Open question 1) |
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

   *Recommendation: (a). The listening test needs committed drivers, and the editor is essential for spelling and grammar.*
2. **Priority and phase.** BA proposes **must, Phase 1**, finished before NAV-005 is released to any external user. Options:
   - (a) must / Phase 1
   - (b) should / Phase 1, releasing NAV-005 with known wording issues
   - (c) Phase 2

   *Recommendation: (a). F1 (English at roundabouts) and F6 (left/east ambiguity) are user-visible and safety-relevant.*
3. **Voice register.** The PO example «эргэнэ үү» implies the polite form (glossary C1). Options:
   - (a) polite -на уу throughout
   - (b) short imperative on banners, polite in voice
   - (c) let the panel choose

   *Recommendation: (a), confirmed by the panel.*
4. **TTS fallback if AC 9 finds no usable `mn` voice** on target devices. Options:
   - (a) server-side neural TTS (e.g. Piper with a Mongolian model)
   - (b) pre-recorded prompt packs for fixed phrases, with names shown only on banners
   - (c) text-only banners plus a chime, until (a) or (b) exists

   *Recommendation: decide after the AC 9 results. Keep (c) as the minimum safe behaviour.*
5. **Platform order** (already owed, backlog decision 1). This affects which devices come first in AC 9 and 10.

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
| AC5 | — | — | — | panel record (QA report, TBD) | |
| AC6 | — | — | — | review sheet (QA report, TBD) | |
| AC7 | — | — | — | review sheet (QA report, TBD) | |
| AC8 | — | — | `docs/requirements/glossary.md` | glossary status check | |
| AC9 | — | — | TTS harness or NAV-005 build (mobile, TBD) | device report (QA, TBD) | |
| AC10 | NAV-005 voice spec (TBD) | — | TTS harness or NAV-005 build (mobile, TBD) | listening test (QA, TBD) | |
| AC11 | NAV-005 voice spec (TBD) | — | — | prompt-duration test (QA, TBD) | |
| AC12 | — | `postRoute`, `getRoute` | override location per architect (TBD) | golden announcement snapshot (QA, TBD) | |
| AC13 | — | `postRoute` | — | wording lint (QA, TBD) | |
| AC14 | — | `postRoute` | override location per architect (TBD) | defect regression tests (QA, TBD) | |
| AC15 | — | `postRoute` (`language=en-US`) | — | en-US snapshot (QA, TBD) | |
| AC16 | — | — | `mn` resource files (TBD) | avoid-term scan (QA, TBD) | |
| AC17 | — | — | upstream PR (URL TBD) | Valhalla CI | |
| AC18 | — | — | — | sign-off record (QA report, TBD) | |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 | — | Created. Includes the BA desk-check findings F1–F10 against Valhalla 3.9.0 `mn-MN.json`. | PO-approved language policy (glossary terms need native approval). Follows up NAV-001 R7 ("native-speaker review is a separate task"). |
