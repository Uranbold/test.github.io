# Active navigation UX rules (banner, voice, camera, states)

- **Owner:** ux-designer
- **Status:** v0.7, 2026-10-03: **«Дугуй» (bicycle) column** for NAV-011 AC 26 in §4.2 (prompt schedule and the outcome band 15–150 m), §4.4 (chaining ≤ 60 m) and §8 (zoom 17.5); car and walk unchanged. v0.6, 2026-10-01: §4.1 only (spike follow-up F3, [D92–D96](../requirements/decisions.md)): display/golden text keeps digits, spoken text per glossary C3a; pending Mongolian km and continue-on rounding (applies with NAV-016, not implemented); English unchanged. v0.5, 2026-10-01: **§11 web demo-mode replay (NAV-017)** added (what applies to a simulated replay in the browser, simulated position and puck glide, web banner caps and stacked variant, browser speech and Web Audio chime, pause on hide); §1–§10 unchanged. v0.4, 2026-10-01. First version written for [NAV-005](../requirements/stories/NAV-005-active-navigation-android.md) (Android first slice, D24). v0.2 applies the PO decisions [D67](../requirements/decisions.md) (§4.1: a metre value that rounds to 1000 is spoken «1 километрт») and [D68](../requirements/decisions.md) (§4.2 rule 6, §4.4: AC 34 exempts a final `arrive` < 30 m after the previous manoeuvre). v0.3 (NAV-005-D12, D67): the catch-up path gives no approaching prompt for an `arrive` more than 500 m ahead (§4.3), and §4.1 no longer claims the approaching prompt is always ≤ 500 m without that rule. v0.4 (glossary [D69/D70](../requirements/decisions.md)): copy only, the approaching prompt A11 now says «{n} метрт очих газартаа хүрнэ» (§4.1, §4.3) and the ramp wording is «… гарах зам руу эргэнэ үү» / «… орох зам руу эргэнэ үү»; the §2.5 line-count measurement was re-run with «Баруун талын гарах зам руу эргэнэ үү» and is unchanged. iOS (NAV-015) reuses these rules unchanged except for platform controls.
- **Stories:** NAV-005 (AC 21–25, 31–39, 41–57, 62–64), NAV-011 (AC 25–26: the «Дугуй» column in §4.2, §4.4, §8), NAV-017 (§11; AC 12–35, 37–41), NAV-007 (AC 11: prompt-duration limits confirmed in §4.7; AC 12–13: the generators in §3–§4 feed the golden announcement set).
- **Related:** screen spec [`screens/NAV-005-android-navigation.md`](screens/NAV-005-android-navigation.md) (layout, components, copy keys), flow [`flows/NAV-005-android-navigation.md`](flows/NAV-005-android-navigation.md) (state machines), map style [`map-style.md` §7.4](map-style.md) (puck, guidance route line, camera tokens), tokens [`tokens.json`](tokens.json) v0.4.0 (`color.*.nav.*`, `typography.scale.nav-*`, `size.nav-*`, `motion.nav-*`). Instruction wording: [ADR-0008](../architecture/adr/0008-route-instruction-text-client-side.md) and the [glossary](../requirements/glossary.md) (binding).
- **Reserved for later stories (layout slots exist now, so nothing moves later):** lane guidance (NAV-013, §2.6), speed limit sign (NAV-014, §2.7), rich and lock-screen notification (NAV-012).

This file is the product-level rule set. It says **what** the driver sees and hears and **when**. The architect decides **how** it is delivered: [ADR-0009](../architecture/adr/0009-android-guidance-client.md) (written in parallel, accepted 2026-10-01) implements the banner, notification and voice schedule of this file in the app, on top of Ferrostar's `core`. Rules are written as outcomes that QA can check in a GPX replay.

## 1. Principles

1. **The next manoeuvre is readable in under 1 second.** One icon, one big distance, one short sentence. The banner has nothing else on it except the street name.
2. **Mongolian first.** Every layout is sized for the longest Mongolian string (Cyrillic is 20–30 % longer than English). Instruction text wraps; it is never truncated (AC 63).
3. **Never blank, never silent about a problem.** The banner always says what to do or what is happening (AC 31). Every problem (off-route, GPS lost, no network, no voice) has a visible text and, where the AC asks, a prompt or chime.
4. **No typing during guidance.** The guidance screen has no text field. Changing the destination means «Дуусгах» and starting again (search during guidance is not in this slice).
5. **No tap is needed while driving.** Reroutes, retries and recovery are automatic (AC 47–50). Taps are optional: mute, orientation, recenter, end.
6. **Familiar pattern.** Layout follows Google Maps / Waze: banner at the top, trip progress at the bottom, puck in the lower part of the map, recenter button bottom-left.

## 2. Banner

### 2.1 What the banner shows
| Part | Content | Rule |
|---|---|---|
| Manoeuvre icon | Glyph for the **upcoming** manoeuvre | NAV-004 icon table (screen spec › Content rules › Manoeuvre icons), same key as the text. Decorative (hidden from TalkBack, AC 21) |
| Distance | Distance along the route to the upcoming manoeuvre | NAV-004 AC 23 format: < 995 m → nearest 10 m, minimum «10 м»; then «1,4 км» (comma in `mn`, point in `en`), ≥ 99,950 m whole km. Number and unit joined by a no-break space. **Updated on every fix** (AC 21). Not shown in the arrival and off-route variants |
| Instruction | NAV-004 AC 27 text of the upcoming manoeuvre (ADR-0008 rule order, D56) | Up to **3 lines**, wraps at word boundaries, never truncated (AC 63). Never Valhalla text (AC 26) |
| Street name | `name` of the step **after** the manoeuvre (the step that starts at it), U+200B/U+200C/U+200D/U+FEFF removed, trimmed | **One line**, ellipsis at the end (a name, not an instruction; the full name is in the TalkBack text). Omitted when empty (AC 21, R6). Never translated (D11) |

**"Upcoming manoeuvre"** = the manoeuvre at the end of the current step (OSRM semantics: the banner of step *k* describes the manoeuvre that starts step *k + 1*). So the banner never shows `depart` during guidance: at the start it already shows the first real manoeuvre; the depart text is spoken only (§4.3, AC 35). On the last step it shows `arrive` with its distance.

### 2.2 When the banner changes
- **Step advance:** within **1 s** after the puck passes the manoeuvre (AC 31). No animation longer than `motion.duration-short` (150 ms cross-fade); none with reduced motion.
- **Off-route episode** (§5): the banner switches to the **recalculating variant** within 1 s of detection (AC 42) and stays there until a new route is active or the user is back on the route (AC 45–46). It never goes blank between the two (Ferrostar issue #969, R10: the client keeps the last banner state until the variant is drawn).
- **Arrival** (§7): the **arrival variant** replaces everything when arrival is detected (AC 55).
- **GPS lost** (§6): the banner keeps the last manoeuvre; the distance freezes at its last value and is drawn in `nav.on-banner-variant` (dimmed) to show it is not live. The status area shows «GPS дохио тасарлаа».
- **Language switch** (AC 60): text switches in place within 1 s; the icon and distance stay.
- **Theme switch** (AC 59): colours switch in the same frame; content stays.

### 2.3 Banner variants
| Variant | Background token | Icon | Line 1 (large) | Line 2 | Line 3 |
|---|---|---|---|---|---|
| **Manoeuvre** (default) | `nav.banner` | manoeuvre glyph | distance «300 м» (`nav-distance`) | instruction (`nav-instruction`, ≤ 3 lines) | street name (`nav-street`, `nav.on-banner-variant`) |
| **Recalculating** (off-route) | `nav.banner-reroute` | `route` glyph with a slow rotation (static with reduced motion) | «Маршрутыг дахин тооцоолж байна» (`nav-instruction`) | **secondary line** when a reroute failed (AC 48–50): «Маршрутын үйлчилгээ түр ажиллахгүй байна», «Маршрут олдсонгүй», «Алдаа гарлаа» or «Интернэт холболт алга» (`nav-street`, `nav.on-banner-reroute-variant`). **None** during a 429 wait (AC 47) | — |
| **Arrival** | `nav.banner` | `flag` | «Та очих газартаа ирлээ», or «Таны очих газар зүүн талд байна» / «… баруун талд байна» (`nav-instruction`) | street name of the `arrive` step, if any | — |

The recalculating variant uses a **neutral grey**, not an error red: leaving the route is normal driving, not a failure, and red would alarm the driver. The green manoeuvre banner returns as soon as there is a route.

### 2.4 Then strip («Дараа нь»)
When the manoeuvre **after** the upcoming one is close (the chaining condition of §4.4: ≤ **150 m** after it by car, ≤ **40 m** on foot), a strip is attached under the banner: label «Дараа нь» (glossary "Then", first letter capitalised by code) + that manoeuvre's icon (24 dp). It appears together with the chained voice prompt and disappears at the next step advance. It is part of the banner (same width, `nav.banner-then` background), so it never covers the map controls. It carries no distance and no text other than «Дараа нь»; its accessible text is «Дараа нь» + the instruction of that manoeuvre (for example «Дараа нь, Зүүн тийш эргэнэ үү»). Why: two manoeuvres 100 m apart need to be read together; the voice says it, the screen should too (Google Maps "Then" chip).

### 2.5 Size and type (portrait phone, 360 dp wide)
- Banner: full width minus 8 dp on each side (`size.nav-banner-margin`), `radius.lg` 16 dp, elevation 2, padding 16 dp. Icon `size.nav-banner-icon` **56 dp** in a 56 dp column on the left, 12 dp gap, text column on the right.
- Type (Android `sp`; the token px value = sp): distance `nav-distance` **32/40 bold**, instruction `nav-instruction` **24/30 medium**, street `nav-street` **18/24 regular**. All white-on-green or `on-banner` tokens, ≥ 4.5:1 (checked).
- **Font scale.** The **guidance overlay** text (banner instruction and street, Then strip, status messages, voice notice, trip progress, recenter label) scales with the system font scale up to an effective factor of **1.3**; the banner distance up to **1.2**. From an effective banner factor of **1.15** the banner switches to the **stacked** layout: row 1 = icon + distance (icon alone in the recalculating and arrival variants), then the instruction and street at the full banner width. The banner region is at most **50 %** of the app height in portrait and scrolls inside beyond that (only reached on small phones with large fonts).
  - Why a cap: the banner base sizes are already 1.3–2.3× the 14 sp body text, so the capped instruction (31 sp) is still more than twice the body size (WCAG 1.4.4 intent). Measured in the prototype: with the first proposal (cap 1.5) the map band at 360×640 dropped to 32 dp at 130 %; uncapped it disappears. Screens that are not used while driving (S1–S4, S7) scale without a cap.
  - Measured with the cap (Liberation Sans, close to Roboto; §10): the longest instruction «Баруун талын гарах зам руу эргэнэ үү» (36 characters, D69/D70 wording; re-measured 2026-10-01 with the same result as the previous 34-character form) takes **2 lines** at 360 and 412 dp at every font scale, 3 lines at 320 dp and in the landscape column; the portrait map band at 360×640 stays ≥ 150 dp at 100 % and ≥ 80 dp at 130–200 % in the worst case (Then strip + GPS message + recenter).
- Landscape: the banner is the top of a left column `clamp(40 %, 320 dp, 400 dp)` wide (screen spec › Layout).

### 2.6 Reserved: lane guidance (NAV-013)
A lane strip will sit **between the banner and the Then strip** (inside the banner card, `nav.banner-then` background), 40 dp high, one 32 dp lane glyph per lane, the recommended lanes in `nav.on-banner`, the others at 40 % opacity. Nothing in NAV-005 uses this slot. Glossary term «эгнээ».

### 2.7 Reserved: speed limit sign (NAV-014)
A round European-style sign (white disc, red ring, black number, as Mongolian road signs) will sit at the **bottom-left of the map band**, 56 dp, in the row above the recenter button (Google Maps position; the top of the band belongs to the status messages). Value in km/h without a unit on the sign; accessible name «Хурдны хязгаар {n} км/цаг» needs a glossary row first. Nothing in NAV-005 draws it; Ferrostar's speed-limit view stays hidden.

## 3. Instruction text (screen)
- Built by the ADR-0008 mapping (port of the web rule table, same shared fixture, AC 29). Keys and `mn`/`en` texts: NAV-004 screen spec › Copy › Manoeuvre texts; Android key names in the NAV-005 screen spec › Copy.
- The same key selects the icon (one source for text and icon).
- The notification title uses exactly the banner instruction (AC 15); the off-route and arrival variants put their line 1 there.
- Scans of AC 28 (no Latin, no placeholders, no bare «зүүн»/«баруун», no Avoid terms) apply to everything on the banner except the street-name line.

## 4. Voice guidance

### 4.1 Voice text generator (pure function, AC 32)
Input: prompt kind (§4.2), manoeuvre key and params (ADR-0008 mapping), distance *d* (m) at the trigger point, UI language. Output: one string. No device, no TTS, runs on the JVM (AC 40).

**Distance prefix** (C3, glossary A8/A9), Mongolian:
| *d* at the trigger point | Prefix | Example |
|---|---|---|
| < 30 m | none; instruction first letter upper case | «Зүүн тийш эргэнэ үү» |
| 30 ≤ *d* < 95 m | nearest 10 m + «метрт» | «60 метрт баруун тийш эргэнэ үү» |
| 95 ≤ *d* < 995 m | nearest 50 m + «метрт»; **a value that rounds to 1000** (975 ≤ *d* < 995 m, half-up rounding) is spoken in kilometres: «1 километрт» (D67) | «300 метрт баруун тийш эргэнэ үү», 960 m → «950 метрт баруун тийш эргэнэ үү», 980 m → «1 километрт баруун тийш эргэнэ үү» |
| 995 ≤ *d* < 9,950 m | km, one decimal, comma, «,0» dropped + «километрт» | «1,5 километрт зүүн талаа барина уу», «2 километрт …» |
| ≥ 9,950 m | whole km + «километрт» | «12 километрт …» |

After a prefix, the instruction starts lower case (the glossary form). **Display / golden text:** number in digits, unit spelled out (never «м», «км» after a number, AC 33); this is the string this section generates, the golden set holds and NAV-017 compares. **Spoken text** (what device TTS, browser speech and the clip pack actually say): per glossary [C3a](../requirements/glossary.md) ([D93](../requirements/decisions.md)), applies with NAV-016 (pending block below). English: "In 300 meters, turn right", "In 1.5 kilometers, keep left", "In 1 kilometer, keep left", decimal point; units spelled out.

**No «1000 метрт» (D67, AC 32).** The same distance is always spoken one way: 975–994 m and 995–1,049 m both say «1 километрт …» / "In 1 kilometer, …". No generated voice text contains «1000 метрт» or "1000 meters" (QA scans the golden set for it). Why: the early prompt at 1,000 m (§4.2) usually triggers at 975–994 m on a 1 Hz track, so without this rule the same prompt was «1000 метрт» on one drive and «1 километрт» on the next (test plan Q1). The approaching prompt (A11) is never spoken more than **500 m** ahead, so its metre value cannot reach 1000: its scheduled trigger (the `arrive` main prompt, §4.2) is ≤ 500 m, and the catch-up path gives **no** approaching prompt for an `arrive` more than 500 m ahead (§4.3, NAV-005-D12). Without that catch-up rule, a reroute or GPS restore 975–1,024 m before the destination produced «1000 метрт очих газартаа хүрнэ» (and 1,500 m «1500 метрт …»), because `arrive` has no early distance and "continue on" needs ≥ 2 km. The banner distance (§2.1, «990 м» → «1 км» at 995 m) is unchanged.

**Pending: spoken form and Mongolian km rounding (spike follow-up F3, [D92–D96](../requirements/decisions.md)). Not in force, not implemented.** These rules apply **with NAV-016**, in one coordinated change with the Android voice text, the NAV-017 web port and a regenerated golden set, after NAV-005 is accepted and NAV-017 is finished (D95). Until then, the prefix table above (= NAV-005 AC 32 as written) is the tested rule, and §4.3 and §4.7 are unchanged.
- **Spoken-form layer (D93, D99, D100).** The display / golden text keeps its digits. For audio only, every number with its unit is replaced by the **exact** `spoken_mn` entry of [`glossary-c3-spoken.tsv`](../requirements/glossary-c3-spoken.tsv), e.g. «300 метрт» → «гурван зуун метрт», «1,5 километрт» → «нэг хагас километрт». This covers the metre and km prefixes, the A11 approaching metres, continue on (A12) and «10 метрээс бага». Nothing is composed outside the table; a value with no entry is a defect. All entries are `needs native review` (NAV-007 AC 8).
- **Mongolian km rounding, voice only (D92; proposed to the NAV-007 panel, which may overrule it).** The half is the only fraction. It replaces the "one decimal" row of the prefix table for `mn`: 995 ≤ *d* < 1,250 m → «1 километрт» (same as D67 for a rounded 1000 m); 1,250 ≤ *d* < 1,750 m → «1,5 километрт»; *d* ≥ 1,750 m → nearest whole km, half up («2 километрт»). The metre bands and the ≥ 9,950 m row are unchanged. With the §4.2 schedule the km prefix never exceeds 2 km.
- **Continue on, `mn` (D94, D102).** *n* = whole km up to 99 km, then the nearest 10 km from 100 km; bound 999 km (largest spoken value 990 km). At 995 km or more the `mn` voice speaks **no** continue-on prompt (A12) (D102); the next manoeuvre's own prompts still play as usual. No new Mongolian wording, table entry or clip is added. The banner and the `en` voice are unchanged (D96).
- **Banner and English unchanged (D96).** The banner keeps one decimal («1,3 км», C3). The `en` voice keeps one-decimal km and digits ("In 1.3 kilometers, …"), so from NAV-016 on the **rounding differs by language**: the same manoeuvre can be «1,5 километрт» in `mn` and "In 1.3 kilometers" in `en`, and the `mn` voice can differ from the banner value (banner «1,3 км-т», voice «нэг хагас километрт»). QA expects the per-language difference (D96); the panel judges the banner/voice mismatch (D92). AC 34 accuracy (max(30 m, 20 %)) is met at the trigger point, worst case about 20 % at 1,250 m and 2,500 m; QA confirms it at speaking time.

**English singular / plural** (NAV-005-D4). An English spelled-out unit agrees with the number **as it is spoken**: the singular form ("meter", "kilometer") only when the formatted number is exactly `1` (no decimal part); every other value takes the plural ("1.5 kilometers", "2 kilometers", "12 kilometers"). This is the CLDR English rule (`one` = integer 1 with no visible fraction digits) applied to the **formatted string** after rounding, never to the raw distance or an integer cast of it (1,500 m formats as "1.5" → plural; 1,040 m formats as "1" → singular). It applies to every English voice template with a spelled-out unit: the metre and kilometre prefixes, "approaching" and "continue on" (copy and keys: NAV-005 screen spec › Copy › Voice texts). With the schedule in §4.2 only the kilometre prefix reaches `1` in practice (metre values start at 30; "continue on" needs ≥ 2 km). Since D67 the metre range can produce it too: a rounded 1000 m switches to the kilometre template with `{n}` = `1`, so it takes the singular ("In 1 kilometer, turn right", never "In 1 kilometers" or "In 1000 meters"). All four templates carry both forms so a later threshold change cannot reintroduce the defect. Mongolian has no number agreement here («1 километрт», «2 километрт»): the `mn` text is the same for every number and is **not** changed by this rule. Banner, notification, progress panel and preview use the abbreviations "m" / "km", which do not inflect ("1 km", "12 km"); they are unchanged.

**Instruction part** (Mongolian; English = the NAV-004 AC 27 `en` text):
| Manoeuvre | Voice text (`mn`) | Note |
|---|---|---|
| any AC 27 turn, keep, merge, ramp, U-turn, continue | the AC 27 text | «бага зэрэг баруун тийш эргэнэ үү», «буцаж эргэнэ үү» … |
| roundabout with `exit` 1–10 | «Тойрогт ороод {ordinal} гарцаар гарна уу» (A10), `{ordinal}` = C4: нэгдүгээр, хоёрдугаар, гуравдугаар, дөрөвдүгээр, тавдугаар, зургаадугаар, долоодугаар, наймдугаар, есдүгээр, аравдугаар | **differs from the banner** «Тойрог: 2-р гарц» (TTS reads «2-р» badly, C4) |
| roundabout, `exit` > 10 or missing | «Тойрогт орно уу» | |
| exit roundabout | «Тойргоос гарна уу» | "now" prompt only (§4.2) |
| depart | the AC 27 depart text, «Хойд зүг рүү явна уу» … | start only (§4.3) |
| approaching the destination | «{n} метрт очих газартаа хүрнэ» (A11) | replaces the main prompt of `arrive`; *n* by the prefix rule (metres; never more than 500 m ahead: the main prompt is ≤ 500 m and the catch-up prompt is suppressed above 500 m, §4.3) |
| arrival | «Та очих газартаа ирлээ», «Таны очих газар зүүн талд байна» / «… баруун талд байна» | AC 55 |
| continue on | «{n} километр үргэлжлүүлэн явна уу» (A12), *n* in the km format of the prefix table | §4.2 |
| chained | «{first}, дараа нь {second}» (A13); `{second}` starts lower case and has no prefix | §4.4 |
| off-route | «Та маршрутаас гарлаа» | once per episode, AC 42 |
| GPS lost / restored | «GPS дохио тасарлаа» / «GPS дохио сэргэлээ» | AC 51–52; "GPS" is the only Latin token allowed in voice |

Street names are **not** spoken in this slice (story Out of scope; C5 case-suffix problem).

English voice texts (not glossary-bound; UX wording): "In {n} meters" / "In {n} kilometers" prefix (singular "In 1 meter" / "In 1 kilometer", rule above) with a comma before the instruction ("In 300 meters, turn right"); roundabout "Enter the roundabout and take the {ordinal} exit" (first … tenth); approaching "In {n} meters, you will arrive" (singular "In 1 meter, you will arrive"); continue on "Continue for {n} kilometers" (singular "Continue for 1 kilometer"); chained "{first}, then {second}"; off-route "You have left the route"; GPS "GPS signal lost" / "GPS signal restored"; arrival as the AC 27 `en` texts.

### 4.2 Prompt schedule (when each prompt is spoken)
*v* = speed over ground, the mean of the last 5 s of good fixes. *Gap* = distance along the route from the previous manoeuvre to this one. Distances are "*d* to the manoeuvre when the prompt is triggered". Fast = *v* ≥ 70 km/h (19.4 m/s) at the moment the prompt would trigger.

| Prompt kind | Car, *v* < 70 km/h | Car, *v* ≥ 70 km/h | Walk («Явган») | Bike («Дугуй», NAV-011) |
|---|---|---|---|---|
| **Continue on** (A12) | Within 1 s after passing a manoeuvre (after its "now" prompt ended), **only if** the next manoeuvre is ≥ **2 km** away | same | never | as car: only if the next manoeuvre is ≥ **2 km** away |
| **Early** | *d* = **1,000 m**, only if gap ≥ 1,300 m | *d* = **2,000 m**, only if gap ≥ 2,500 m | never | never |
| **Main** | *d* = clamp(*v* × 12 s, **100**, **250**) m | *d* = **500 m**, only if gap ≥ 700 m; else clamp(*v* × 12 s, 100, 250) | *d* = **50 m** | *d* = clamp(*v* × 15 s, **60**, **150**) m |
| **Now** | *d* = clamp(*v* × 3 s, **15**, **80**) m | same | *d* = **15 m** | *d* = clamp(*v* × 3 s, **15**, **30**) m |

The column is chosen by the route's `costing`: `auto` → car, `pedestrian` → walk, `bicycle` → bike. The car "fast" split (70 km/h) does not apply to the bike column.

Rules:
1. Every prompt is spoken **at most once** (AC 34), and never for a manoeuvre already passed.
2. A prompt is **skipped** if another prompt for the **same** manoeuvre started less than **8 s** earlier (no back-to-back repeats in slow traffic).
3. `arrive`: no early prompt; main = approaching (A11); arrival prompt at arrival detection (§7) instead of "now". If *d* < 30 m when the main trigger is reached, it is skipped.
4. `exit roundabout`: "now" prompt only.
5. `depart`: spoken only at the start (§4.3), never after a reroute.
6. **Outcome check for QA (AC 34):** for every car manoeuvre except `depart`, at least one prompt starts while it is **20–250 m** ahead. The table guarantees it: city speeds → main (100–250 m); fast → now (58–80 m); crawling at < 5 m/s → main at 100 m.
   - **Exemption (D68):** an `arrive` whose final step (from the previous manoeuvre to the route end) is **shorter than 30 m** along the route is exempt from the 20–250 m check. It cannot be met without a new prompt form: the approaching prompt is skipped below 30 m (rule 3) and `arrive` is never chained (§4.4). Its only prompt is the arrival prompt at arrival detection (§7), produced **exactly once** (spoken, or the chime). Every other AC 34 check (at most once, start ≤ 1 s, drop after 3 s, stated distance within max(30 m, 20 %), nothing for a passed manoeuvre) applies to it unchanged, and no other manoeuvre is exempt. Example: G8, `arrive` 4 m after the roundabout exit.
7. **Implementation:** [ADR-0009](../architecture/adr/0009-android-guidance-client.md) §3.3 schedules the prompts **on the device** from this table (Valhalla's trigger points have no "now" prompt and no speed-dependent main prompt, ADR-0009 F10). The text always comes from §4.1, never from `announcement`. The distances and times here are named constants in the app; changes go through UX and the BA.
8. **Bike column («Дугуй», NAV-011 AC 26).** Why these numbers: a cyclist in UB rides in mixed traffic without cycle lanes and must look back, signal and change lane before a turn, so the main prompt comes earlier in *time* than for a car (15 s instead of 12 s) but at a shorter *distance* (60–150 m; at the G10 speed of 4.5 m/s it triggers at 67.5 m, spoken «70 метрт»). The "now" prompt stays close to the turn (15–30 m: 15 m at 4.5 m/s, 3.3 s ahead), so the two prompts are ≥ 45 m and ≥ 8 s apart at every speed up to 15 m/s (54 km/h, a fast descent), and rule 2 does not skip the now prompt in normal cycling. No early prompt (a 1 km warning is 4 min ahead at 16 km/h). Continue-on as car (≥ 2 km), so only the existing A12 forms (2 km and up, D94) are used and no new wording or C3a table entry is needed. Arrival, `arrive` rules 3 and 6 (D68), roundabout rule 4 and `depart` rule 5 apply unchanged.
   - **Outcome check for QA (AC 26, replay G10):** for every bike manoeuvre except `depart` (and the D68 `arrive` exemption), at least one prompt starts while it is **15–150 m** ahead. The table guarantees it: main is always 60–150 m, now 15–30 m. Every other AC 34 rule applies unchanged (at most once, start ≤ 1 s, distance error ≤ max(30 m, 20 %), drop after 3 s).

### 4.3 Start, reroute, GPS restore: the catch-up prompt
- **Start** (AC 35): within 2 s after «Эхлэх», the depart text, chained with the first manoeuvre when that one is within the chaining distance (§4.4). Then the normal schedule.
- **After a reroute** (AC 45): no depart text. Within 1 s after the new route is active, one **catch-up prompt** for the upcoming manoeuvre: main form with the current *d* prefix if *d* < early distance, or "continue on" if the next manoeuvre is ≥ 2 km away. Then the normal schedule.
  - **`arrive` (NAV-005-D12, D67, AC 32):** `arrive` has no early distance (§4.2), so the rule above would give the approaching prompt (A11) at any *d* under 2 km. The catch-up path therefore produces **no approaching prompt for an `arrive` more than 500 m ahead**; the approaching prompt then fires at its normal main trigger (§4.2, ≤ 500 m), under the normal rules (at most once, §4.2 rules 1–3). Everything else is unchanged: at *d* ≤ 500 m the catch-up prompt is the approaching prompt with the current *d* prefix, and at *d* ≥ 2 km it is "continue on". Examples: reroute 1,000 m before the destination → no prompt now, «250 метрт очих газартаа хүрнэ» at a 250 m main trigger; 1,500 m → no prompt now; 400 m → «400 метрт очих газартаа хүрнэ» / "In 400 meters, you will arrive"; 2,400 m → «2,4 километр үргэлжлүүлэн явна уу». No new wording or glossary term.
- **After GPS restore** (AC 52): «GPS дохио сэргэлээ», then (if the upcoming manoeuvre has had no prompt yet and is more than 20 m ahead) one catch-up prompt as above. Manoeuvres passed during the loss are never announced.

### 4.4 Chaining (A13)
- **Trigger:** manoeuvre *k + 1* is ≤ **150 m** (car) / ≤ **40 m** (walk) / ≤ **60 m** (bike «Дугуй», NAV-011: 13 s at the G10 speed of 4.5 m/s, too short to hear and act on two separate prompts) along the route after manoeuvre *k*, and *k + 1* is not `arrive`.
- **`arrive` is never chained** (D68 confirms it; there is no glossary form for "then you arrive"). A final `arrive` close behind the last manoeuvre gets the approaching prompt if *d* ≥ 30 m when it becomes the upcoming manoeuvre, and otherwise only the arrival prompt at detection (§4.2 rules 3 and 6). No Then strip is shown for it.
- **Effect:** the main and now prompts of *k* (and the depart prompt at start) become «{first}, дараа нь {second}»; `{second}` is *k + 1*'s instruction part without prefix, lower case. *k + 1* then gets only its "now" prompt (rule 2 still applies). The Then strip (§2.4) shows for the same pair.
- Never more than two manoeuvres in one prompt.

### 4.5 Playback rules
1. **One utterance at a time.** At most one prompt waits. A waiting prompt that cannot start within **3 s** of its trigger is dropped (AC 34). A newer prompt for the same manoeuvre replaces a waiting older one.
2. **Off-route** stops the current manoeuvre utterance (it belongs to the old route), clears the queue and speaks «Та маршрутаас гарлаа» (AC 42).
3. **Arrival** waits for the current utterance to finish (max 3 s), then plays; nothing plays after it (AC 55).
4. **Mute** («Дууг хаах») stops the current utterance or chime within 1 s and suppresses all prompts and chimes until «Дууг нээх» (AC 37). Voice on by default; the choice is remembered. Banners are unaffected.
5. **Audio:** `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, transient focus with ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), released within 1 s after the utterance. Focus denied (phone call) → prompt skipped, not queued (AC 36). Speech rate 1.0, pitch 1.0 (no user setting in this slice).
6. **Language switch** (AC 60): the current utterance stops; the next prompt uses the new language and voice (§4.6 re-evaluated).
7. **«Дуусгах», swipe-away, arrival end:** any utterance or chime stops within 2 s (AC 19).

### 4.6 Voice selection and the fallback (AC 38–39, D23)
- `TextToSpeech` is initialised **when the route preview opens** (not at «Эхлэх»), so the decision is ready before the depart prompt. If it is still pending at «Эхлэх», the depart prompt waits up to 2 s; if still pending, that prompt is a chime and the decision continues to its 3 s limit.
- **Usable voice** = initialised within **3 s** and `isLanguageAvailable` / `setLanguage` reports the UI language available (not `LANG_MISSING_DATA` or `LANG_NOT_SUPPORTED`). Mongolian UI → `mn` / `mn-MN` (either; prefer a `mn-MN` voice that does not require network). English UI → `en-US`, else any `en-*`.
- **Mongolian is never spoken by a non-Mongolian voice** (an English or Russian voice would mangle it).
- **Fallback (no usable voice for the UI language):**
  - every prompt (manoeuvre, off-route, GPS, arrival) plays the **chime** instead (§4.8), with the same timing and playback rules;
  - the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» (A1) appears **once per guidance session**, directly **above the trip progress panel**, for **8 s** (AC 39 asks ≥ 5 s), or until tapped; it never covers the banner, the progress panel (so «Дуусгах» stays reachable) or the attribution;
  - a TTS error during the session switches to the fallback **for the rest of the session** within 1 s (and shows the notice if it was not shown yet);
  - the voice button keeps working: «Дууг хаах» also mutes the chime.
- **English UI without an English voice** (rare: almost every Android phone has an English engine): chime fallback, **no notice** until the BA provides a language-neutral notice (A1's `en` text names Mongolian; request in the handoff).
- The engine name and voice found are logged **only** in debug builds, without coordinates (AC 38, 67).

### 4.7 Prompt duration (NAV-007 AC 11, confirmed)
UX confirms the BA limits: a single prompt **≤ 5.0 s**, a chained prompt **≤ 8.0 s** at the default rate on the reference device. Check against the schedule: the main prompt at 60 km/h triggers at 200 m; 5 s is 83 m, so it ends ≥ 110 m before the turn; the chained prompt at 80 km/h (main 250 m) ends ≥ 70 m before. The longest single prompt in the generator, «1,5 километрт бага зэрэг баруун тийш эргэнэ үү» (6 words), and the longest chained one, «200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү» (13 words), are the two cases NAV-007 AC 11 should time first.

### 4.8 Chime (D23 minimum)
- Two-tone rising chime: 880 Hz for 120 ms, 20 ms gap, 1,320 Hz for 180 ms, 10 ms fade in/out, peak −3 dBFS, total **≈ 330 ms** (≤ 1 s, AC 39). Generated by a script (sine tones, no samples), so no third-party licence; mono 16-bit 44.1 kHz OGG or WAV in the APK.
- Same audio attributes and focus rules as speech (§4.5 rule 5).
- One chime per prompt. No different chimes per kind in this slice (the screen says what happened); recorded or neural Mongolian voice is NAV-016.

## 5. Off-route and reroute (what the driver sees)
| Moment | Banner | Voice | Puck / map | Requests |
|---|---|---|---|---|
| Detected (AC 41–42) | Recalculating variant within 1 s | «Та маршрутаас гарлаа» once per episode (or chime) | Puck at the **raw** position; old route drawn in the alternative colours (map-style §7.4) | First reroute within 1 s |
| Waiting for the response | same | — | same | at most 1 in flight |
| 429 `Retry-After` (AC 47) | same, **no** secondary line | — | same | 0 for N s, then 1 automatically |
| Network error / 5xx / 12 s (AC 48) | + «Маршрутын үйлчилгээ түр ажиллахгүй байна» | — | same | automatic back-off 5, 10, 20, 30, 30 … s |
| `NoRoute` / `NoSegment` / 171 (AC 49) | + «Маршрут олдсонгүй» | — | same | next after ≥ 200 m **and** ≥ 30 s |
| Other 400 / 413 / bad 200 (AC 49) | + «Алдаа гарлаа» | — | same | same rule |
| No network (AC 50) | + «Интернэт холболт алга» (the status-area offline indicator is hidden meanwhile, so the text is not shown twice) | — | same | 0; 1 within 3 s after the network returns |
| New route (AC 45) | Manoeuvre variant within 500 ms of the response | catch-up prompt (§4.3) | new route line in the guidance colours; puck snapped | — |
| Back within 50 m of the old route first (AC 46) | Manoeuvre variant within 1 s | catch-up prompt | old route back in the guidance colours | 0 extra |

The secondary line always shows the **latest** failure reason and clears when a new request is sent. The driver never has to tap anything.

## 6. GPS loss, tunnels, poor accuracy (AC 51–53)
- **Lost:** no good fix (≤ 25 m) for **10 s**. Within 1 s: status message «GPS дохио тасарлаа» (stays while the loss lasts), spoken once (or chime); puck turns **stale** (grey, `nav.puck-stale-*`) at the last position; banner distance, remaining time, remaining distance and «Хүрэх цаг» freeze (distance dimmed, §2.2); no manoeuvre prompts; no reroute. The camera stays where it is. Guidance never ends on its own.
- **Restored:** first good fix. «GPS дохио сэргэлээ» replaces the lost message for **3 s** (`motion.nav-gps-restored`), spoken once (or chime); puck blue again; guidance resumes from the new position within 2 s; catch-up prompt (§4.3); off-route rules if > 50 m from the route.
- **Poor accuracy** (fixes > 25 m): ignored for off-route (AC 41); the puck keeps following the route snap; no message (a short urban-canyon drift is not worth a message).
- No dead reckoning in this slice (the puck does not move during a tunnel).

## 7. Arrival (AC 55–57)
- **Detection:** within **30 m** of the route end, or past it.
- Banner: arrival variant; voice: arrival text once (or chime); the approaching prompt came before it when the schedule had one.
- The **progress panel is replaced by the arrival panel**: flag icon, the destination's name (the preview's destination text: the search result name or «Сонгосон цэг») and «Хаах». No timer: it stays until «Хаах» (AC 55). Back = «Хаах».
- Within 10 s: the foreground service and guidance location updates stop; the notification is removed; the screen-on flag is cleared. No prompts and no reroutes after arrival, even if the user drives on.
- «Хаах» → map screen without a route, camera stays on the current position, bearing animates to north (300 ms, `motion.duration-medium`).
- Destination far off-road (AC 57): arrival is at the route end; nothing guides the remaining distance.

## 8. Camera and orientation (AC 23–25)
| Situation | Camera |
|---|---|
| Following, «Явах чиглэл дээшээ» (default) | Bearing = course over ground (route bearing when snapped), **pitch 45°**, puck at **70 %** of the uncovered map height from its top, horizontally centred in the uncovered area. Zoom by speed (hysteresis 5 km/h): car < 20 km/h → **17.5**, 20–50 → **17**, 50–80 → **16**, ≥ 80 → **15**; walk → **17.5**; bike («Дугуй», NAV-011) → **17.5** (a cyclist's next 150 m fit on screen at that zoom). Zoom changes ease over 1 s |
| Following, «Хойд зүг дээшээ» | Bearing 0, pitch 0, puck centred in the uncovered map area, same zoom rule |
| Each fix | Camera reaches the new position and bearing within **1 s** (`motion.nav-camera-follow`, linear easing so the motion is continuous) |
| User pans, zooms, rotates or tilts | Following stops at once; «Байршил руу буцах» appears within **300 ms**; banner, voice and progress continue; 0 requests |
| «Байршил руу буцах» tapped, or **15 s** without a map gesture (`motion.nav-recenter-timeout`, story default confirmed) | Following resumes within 1 s with the current orientation; the button disappears |
| Off-route | Follows the raw position (AC 23) |
| GPS lost | Stays at the last position; gestures still allowed |
| Arrival panel | Keeps following until «Хаах» |
| Orientation toggle | Switches within 1 s; kept for the session (not stored) |
| Reduced motion (Android "Remove animations") | Camera jumps instead of easing; the follow update still happens on every fix |

"Uncovered map area" = the map minus the banner (with Then strip) and the status messages at the top, and the voice notice, progress panel and the bottom control row (recenter left, voice and orientation right) at the bottom; in landscape also the left column. QA checks the puck centre lies inside it (AC 23).

## 9. Theme, language, configuration changes
- Theme options «Өдрийн горим», «Шөнийн горим», «Автомат» (default; follows the Android dark-theme setting, AC 58). Automatic sunrise/sunset switching is NAV-012; when it comes, «Автомат» changes meaning, no new option.
- Night uses the `night` tokens: dark banner (`nav.banner` night #123D2C), no large light areas, the white puck fill is the only bright element and is 40 dp.
- Theme, language, rotation and other configuration changes keep the route, the step, the puck, the banner and the queue state; **0** requests and **0** repeated prompts (AC 59, 60, 64). A prompt playing during a language switch stops (§4.5 rule 6).

## 10. Verification of these rules
- Prototype [`prototypes/NAV-005-guidance.html`](prototypes/NAV-005-guidance.html) and `prototypes/check-layout-nav005.mjs` check the banner line counts, the map band, overlaps and the attribution at 360×640, 412×915, 320×568 and 640×360 (landscape) dp, day/night, mn/en, font scale 100 %/130 %/200 % (624 combinations; result 2026-10-01: 0 problems in the design target; details in the screen spec › Verification).
- The voice generator examples in §4.1 equal the AC 32 examples; QA's JVM tests and the golden set (AC 40, NAV-007 AC 12) are the binding check.
- NAV-017 (§11): prototype [`prototypes/NAV-017-demo-mode.html`](prototypes/NAV-017-demo-mode.html) and `prototypes/check-layout-nav017.mjs` check the web guidance and picker layout at the iPhone viewports of NAV-017 AC 37 and with Safari's toolbars shown, day/night, mn/en, text zoom 100/200 % (672 combinations; result 2026-10-01: 0 problems in the design target; details in the screen spec › Verification). The web voice generator is checked against the same NAV-005 golden set (NAV-017 AC 26).

## 11. Web demo-mode replay (NAV-017)
Applies to the **web demo-mode build only** ([NAV-017](../requirements/stories/NAV-017-web-demo-mode-replay.md); screen spec [`screens/NAV-017-web-demo-mode.md`](screens/NAV-017-web-demo-mode.md); flow [`flows/NAV-017-web-demo-mode.md`](flows/NAV-017-web-demo-mode.md)). A recorded route is replayed with a simulated position on an iPhone in Safari. Everything in §1–§9 applies **unless this section says otherwise**, so the PO sees and hears what a driver will.

### 11.1 What applies and what does not
| Rule | In the replay? |
|---|---|
| §1 principles; §2.1–2.2, §2.5 banner (manoeuvre and arrival variants); §3 text | **yes** (web sizes: §11.3) |
| §2.3 recalculating variant; §2.4 Then strip; §2.6–2.7 reserved slots | no (no reroute; the Then strip is out of the story's scope, the chained prompt A13 is still spoken) |
| §4.1 voice texts (incl. D67); §4.2 schedule (car and walk, rules 1–6, D68); §4.4 chaining; §4.7 durations | **yes**, at **1×** only (the only replay speed) |
| §4.3 catch-up prompts | start only (depart, chained when §4.4 applies); no reroute, no GPS restore |
| §4.5 playback rules 1, 3, 4, 6 | **yes** (§11.4) |
| §4.5 rule 2 (off-route), rule 5 (Android audio focus and ducking), rule 7 | no; rule 7 is replaced by NAV-017 AC 35: stop within **1 s** |
| §4.6 voice choice and fallback; §4.8 chime | **yes, in browser terms** (§11.5) |
| §5 off-route; §6 GPS loss | no (the position is simulated; the Geolocation API is never called) |
| §7 arrival | **yes**, without the Android service parts; ends in the picker |
| §8 camera (heading-up follow, pitch 45°, zoom by speed, 1 s follow, recenter after a gesture or 15 s) | **yes**; no orientation toggle (heading-up only), no off-route or GPS-lost camera |
| §9 theme | web rule: the manual NAV-002 day/night toggle (D12), no «Автомат» |

### 11.2 Simulated position, speed and puck
- **Simulated fix *i*** = GPX track point *i*, applied when the replay clock reaches its track time (± 100 ms at 1×). The replay clock is derived from timestamps (`performance.now()`), never from counting timer ticks, so Low Power Mode or a slow frame cannot shift positions; it excludes paused time (§11.6).
- Each fix is **snapped to the route line**; distances to the manoeuvre, remaining distance and time, banner changes, prompt triggers and arrival all use the snapped fix.
- **Speed *v*** for the §4.2 schedule (fast threshold, main and now distances) = the mean speed along the route over the last 5 s of simulated fixes (the synthetic tracks carry scripted speeds, story R2). **Course** = bearing between consecutive fixes; below 1 m/s the last course is kept (NAV-017 AC 22).
- **Puck glide** (NAV-017 AC 13, screen spec Design notes 7): between two snapped fixes the puck moves along the route line over the fix interval (1 s at 1 Hz), linear by distance; it never moves backwards along the route (a fix behind the shown position makes it hold). The follow camera eases linearly over the same second (`motion.nav-camera-follow`). Reduced motion: puck and camera jump per fix.

### 11.3 Banner sizes on the web
- CSS px with the §2.5 sizes; type in `rem` with the §2.5 caps written as `min()`: distance `min(2rem, 38.4px)`, instruction `min(1.5rem, 31.2px)`, street `min(1.125rem, 23.4px)`; the badge, status text, progress text and recenter label `min(<rem>, 1.3 × px)`.
- **Web stacked variant:** from an effective text zoom of **115 %** the banner stacks as §2.5 (row 1 = icon + distance, then instruction and street at full width), with a **48 px icon and 12 px vertical padding** (Android: 56 dp and 16 dp). Measured: the Android stacked size left 61 px of map at 375×667 and 200 % in English in the worst case; the web variant leaves 77 px (screen spec › Verification). The row layout below 115 % is unchanged.
- Longest instruction «Баруун талын гарах зам руу эргэнэ үү»: 2 lines at 375, 390 and 430 px wide and in the 844×390 landscape column, at 100 % and 200 %.

### 11.4 Playback in the browser
- **Speech:** `speechSynthesis` with one utterance at a time (§4.5 rule 1): at most one waiting prompt; a prompt whose utterance has not **started** (`start` event) within **3 s** of its trigger is cancelled and dropped, not queued. Rate 1.0, pitch 1.0, volume 1.0. `utterance.voice` and `utterance.lang` are always set (§11.5). Stopping (mute, «Дуусгах», language switch, page hidden) = `speechSynthesis.cancel()` within 1 s.
- **Chime:** the §4.8 sound generated at runtime with Web Audio (two `OscillatorNode`s through a `GainNode`: 880 Hz for 120 ms, 20 ms gap, 1,320 Hz for 180 ms, 10 ms fades, peak gain 0.71 ≈ −3 dBFS, ≈ 330 ms in total). No sound file, no third-party licence. Same timing and playback rules as speech. One chime per prompt.
- **Unlock (iOS):** the first `speak` call and the `AudioContext` creation or `resume()` happen inside the «Эхлэх» activation handler (NAV-017 AC 27; the exact method is the architect's, story request 2). If iOS suspends the audio later (phone call, Control Centre) and `resume()` is refused without a gesture, the **next tap anywhere on the page** resumes it. There is no message for this (no glossary term); it is a real-iPhone check (NAV-017 AC 48).
- **Arrival** waits up to 3 s for a current utterance, then plays; nothing plays after it (§4.5 rule 3).
- **Mute** (§4.5 rule 4) is stored in `localStorage` (key named in `web/README.md`); voice is on by default and when storage is unavailable.

### 11.5 Voice choice on the web (§4.6 in browser terms)
- Decided when the picker appears and on every UI language change: **usable voice** = `window.speechSynthesis` exists and, within **3 s** (waiting for `voiceschanged` if `getVoices()` starts empty), a voice whose `lang` matches `^mn([-_]|$)` (Mongolian UI) or `^en([-_]|$)` (English UI, prefer `en-US`), case-insensitive (NAV-017 AC 28). The voice found is never stored or sent.
- **Mongolian text never goes to a non-Mongolian voice.** No usable voice → every prompt is the chime; in the Mongolian UI the A1 notice shows once per replay for 8 s (or until tapped) in the message stack above the progress panel (screen spec Layout rule 5). English UI without an English voice: chime, no notice.
- An `error` event on an utterance during the replay switches to the chime for the rest of the replay within 1 s (and shows A1 if not shown yet in the Mongolian UI).
- **Pending, not in the AC yet** ([D78](../requirements/decisions.md), through triage as spike follow-up F1): only voices with `localService === true` would count. Nothing in the UI changes; on the PO's iPhone the system voices are local.

### 11.6 Pause when the page is hidden
- `visibilitychange` to `hidden` (tab or app switch, screen lock): within 1 s the replay clock pauses, the progress values freeze, any utterance or chime stops, and no prompt is produced while hidden (NAV-017 AC 15).
- Visible again: the replay resumes within 1 s from the same position and replay time. Nothing was passed while paused, so nothing is announced late and nothing is repeated; a prompt that was cut off by the pause is not replayed. The screen wake lock is requested again (silently skipped if refused, NAV-017 AC 39). The 15 s recenter timer restarts if the camera was not following.
- No message is shown for the pause: the page was not visible.

