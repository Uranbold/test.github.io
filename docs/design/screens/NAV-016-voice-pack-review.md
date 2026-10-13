# Screen: Voice pack review page (generator output, internal)

- **Stories:** NAV-016 (AC 4, 17, 21, 29, 33, 53); NAV-007 (AC 10 sessions use the same pack)
- **Platforms:** a static offline HTML file written by the generator (`backend/tools/voice-pack/`, owner backend-engineer) to `<output>/review/index.html`. Opened from disk (`file://`) in a desktop browser. **Never deployed, never committed** (D79, NAV-016 AC 19). Not app UI.
- **Figma / prototype:** none (the generator renders the page; see Evidence)
- **Related:** [`../navigation-ux.md`](../navigation-ux.md) §4.1 (spoken text, segments), §4.5 rule 8 (pauses), §4.7 (longest prompts)

## Purpose
Let the PO listen to every segment of one voice pack and mark each one `ok` or `bad`, then hear a few assembled prompts to judge the joins, before the first NAV-007 AC 10 session and before any public build (PO decision 2026-10-13, NAV-016 Open question 2 (b)).

## Context and goal
- **Primary task:** hear about 304 short clips in order, catch any wrong word, wrong stress or cut-off, and write it down where it was heard.
- **Moment of use:** at a desk, with headphones, one sitting of about 30–40 min per voice (BA estimate). Not while driving. Time budget per clip: one listen (≈ 1–2 s) plus one keypress.
- **Alternatives considered:**
  - (A) **One grouped table**, a play button and `ok` / `bad` on every row, "play from here" for hands-free runs, export of the filled `review.tsv`. **Chosen:** everything is visible (key, text, take, duration) next to the sound, rows can be revisited, and the PO writes the result where the sound was heard.
  - (B) A flash-card player, one segment at a time with auto-advance. Faster per clip, but hides context (the `final` / `nonFinal` pair cannot be compared side by side) and makes going back awkward. Its speed is kept in (A) through "play from here" and the keyboard.
  - (C) A plain list, results typed into `review.tsv` in a spreadsheet. Two windows and 304 rows to match by hand: error-prone. Kept only as a fallback (the TSV stays editable).

## Layout
```
┌ Header ──────────────────────────────────────────────────────────┐
│ Title · voiceId FEMALE5v2 · packVersion 1 · source chimege v1.2  │
│ 304 entries · takeMethod · join 80 ms · then 80 ms               │
│ glossaryRevision a1b2c3d4e5f6… · inventorySha256 9f8e7d6c5b4a…   │
│ [stub banner, only for source.vendor = "stub"]                   │
├ Toolbar (sticky) ────────────────────────────────────────────────┤
│ Checked 112 / 304 · bad 3 │ [Play from here] [Stop] │ Show: All │
│ Unchecked │ Bad │ nonFinal │ [Download review.tsv] │ ↓ Sample prompts │
├ Group: Distances (217) ──────────────────────────────────────────┤
│ [▶] dist.km.1_5   нэг хагас километрт      final   1.02 s  [ok][bad] [note…] │
│ …                                                                 │
├ Group: Turns (6 × 2) ────────────────────────────────────────────┤
│ [▶] instr.turn.right  баруун тийш эргэнэ үү  final     1.10 s  [ok][bad] [note…] │
│ [▶] instr.turn.right  баруун тийш эргэнэ үү  nonFinal  1.05 s  [ok][bad] [note…] │
│ … Roundabout, Depart, Arrival, Tails, Join, Fixed               │
├ Sample prompts (24) ─────────────────────────────────────────────┤
│ [▶] 6.42 s  LONGEST CHAINED (AC 29)                              │
│     display «250 метрт тойрогт ороод …»                          │
│     spoken  «хоёр зуун тавин метрт тойрогт ороод …»              │
│     dist.m.250 · instr.roundabout.exit.6 (nonFinal) · join.then · … [ok][bad] [note…] │
└──────────────────────────────────────────────────────────────────┘
```
- **Groups** in the order of the NAV-016 "Segment inventory" table (Distances, Turns, U-turn and straight, Keep, Merge, Ramps, Roundabout, Depart, Arrival, Tails, Join, Fixed), rows sorted by key then take, so a `nonFinal` take sits directly under its `final` take.
- **Spoken text** is the largest text on a row (18 px, `lang="mn"`); key and take are monospace and quieter. Duration in seconds with two decimals.
- **Fixed group with 302 entries:** one line under the group says the two GPS clips are not in this pack yet (NAV-016 AC 4).
- **Sample prompts** (last section, from `review/prompts/`, rendered with the manifest join rules, AC 17 and AC 21): first the two longest prompts of the AC 29 list (marked), then the two §4.7 measured prompts, then the coverage prompts. Each card: total duration, display text, spoken text, the segment list (key and take), play, `ok` / `bad`, note. UX asks that the coverage set includes at least one prompt per instruction group, a chained prompt with each `nonFinal` group, the chained depart, a three-digit continue-on, the approaching prompt, both side arrivals and off-route.
- Day colours only, system font stack, no external file (the page works offline, AC 17). Width: fluid, max 1,200 px; at < 720 px each row wraps to two lines (play + text on top, the rest below).

## Components
| Component | Source | Notes |
|---|---|---|
| Play button | custom HTML `<button>` + one shared `<audio>` element | 44 × 44 px; one clip at a time: a new play stops the current one; the playing row is highlighted |
| Result toggle | two radio buttons styled as a segmented control | `ok` / `bad`; empty until chosen |
| Note | single-line `<input>` | free text, any script |
| Toolbar | sticky `<div>` | progress, play from here / stop, filter, download, jump link |

## States
| State | What the PO sees |
|---|---|
| First open | Checked 0 / N; every result empty; focus on the first row |
| In progress | Progress and bad count update on each mark; results kept in browser storage per pack (key = `voiceId` + `packVersion` + `inventorySha256`), so closing and reopening resumes where the PO stopped |
| Storage unavailable | a line in the toolbar: results are not kept, download before closing; the browser asks before the page closes with unsaved marks |
| Done | Checked N / N; when bad = 0 the toolbar shows the "ready" message (AC 53 needs 0 `bad` rows); otherwise the bad count links to the "Bad" filter |
| Playing from here | rows play in order with a 700 ms gap, each scrolled into view; marking does not stop the run; Stop, Esc or the end stops it |
| Clip cannot play (file missing or not decodable) | that row shows an error line and its play button is disabled; the PO marks it `bad` (the pack fails AC 39 anyway) |
| Browser cannot play the codec | a banner above the toolbar names the browsers to use (Chrome, Edge, Safari); nothing plays |
| Stub pack (`source.vendor = "stub"`) | a banner: the clips are synthetic tones, not for listening; result toggles disabled |
| Loading / offline / empty | not applicable: local static file, audio loads on demand from the same folder; the generator never writes an empty pack (AC 11) |

## Interactions
- Click ▶ or press **Space** on a focused row: play (again). **↑ / ↓**: previous / next row. **1** = `ok`, **2** = `bad` (digits, so the keys work on a Mongolian Cyrillic keyboard layout too). **Enter**: into the note field; **Esc**: out of it, or stop playback.
- **Download review.tsv:** writes the file with the AC 17 columns `key`, `take`, `spoken_mn`, `result` (`ok`, `bad` or empty), `note` (tabs and line breaks removed), UTF-8, LF, inventory order. The PO saves it over `review/review.tsv`. Sample prompt results are exported as extra rows only if the BA confirms the format (request below); until then their note is for the PO's own record.
- Filters only hide rows; they never change marks.

## Copy (mn / en)
The page is team tooling, so identifiers stay as they are: keys, `final` / `nonFinal`, `ok` / `bad`, field names and hashes (as in `review.tsv`). The spoken texts come from the manifest (glossary wording, exact). **No glossary term exists for any label below**, so the generator shows the `en` text until the BA adds rows; the labels sit in one table in the generator so the swap is data only.

| Label | mn | en |
|---|---|---|
| Page title | missing (BA) | "Voice pack review" |
| Play / Stop / Play from here | missing (BA) | "Play" / "Stop" / "Play from here" |
| Column heads: text, take, duration, result, note | missing (BA) | "Spoken text", "Take", "Duration", "Result", "Note" |
| Progress | missing (BA) | "Checked {n} / {total} · bad {b}" |
| Filters | missing (BA) | "All", "Unchecked", "Bad", "Non-final takes" |
| Download | missing (BA) | "Download review.tsv" |
| Sample prompts heading, longest marks | missing (BA) | "Sample prompts", "Longest single", "Longest chained" |
| Ready message | missing (BA) | "All segments checked, none bad" |
| Storage warning | missing (BA) | "Results are not saved in this browser. Download review.tsv before closing." |
| Stub banner | missing (BA) | "Stub pack: synthetic tones, not for listening." |
| Codec banner | missing (BA) | "This browser cannot play the clips. Use Chrome, Edge or Safari." |
| GPS note (302 entries) | missing (BA) | "GPS prompts have no clip in this pack yet." |

## Accessibility
- Text and controls on the light tokens (`color.light.ui.on-surface` on `color.light.ui.surface`, secondary text `on-surface-variant`, focus `focus-ring`; pairs already ≥ 4.5:1 in `check-contrast.mjs`); the playing-row highlight is a 4 px left bar plus a background tint, not colour alone.
- Every play button has an accessible name with the key and take ("Play instr.turn.right nonFinal"); result radios are grouped per row with the key as the group name; spoken text cells carry `lang="mn"`.
- Full keyboard use (above); targets ≥ 44 px; text zoom to 200 % reflows (rows wrap, no horizontal scroll at 720 px and up).

## Design rationale
| UX law / heuristic | How this page applies it | Deliberate trade-off |
|---|---|---|
| Fitts's law | Play is the first, largest control on each row; `ok` / `bad` sit together at the row end; keyboard shortcuts remove pointer travel for 304 rows | Mouse users travel across the row to mark; accepted, keys cover the fast path |
| Hick's law | Two results only (`ok` / `bad`); detail goes in the note | No severity scale; the note carries nuance |
| Gestalt (proximity, similarity) | Inventory groups as sections; `final` and `nonFinal` of one key adjacent; every row the same component | — |
| Serial-position effect | Segments (the gating task) first; the join check (sample prompts, the riskiest new part, D87) is the last, separate section with the longest prompts on top | Joins are heard after the segments, not before |
| Zeigarnik effect | Progress and bad count always visible; marks kept per pack and resumed | Browser storage can be missing; then an explicit warning |
| Doherty threshold | Local files, one shared audio element: playback starts well under 400 ms | — |
| Postel's law | Digit shortcuts work on Cyrillic and Latin keyboard layouts; notes accept any script | — |
| Tesler's law | The generator computes durations, totals, grouping and the longest prompts; the PO only listens and marks | — |
| Recognition over recall (Nielsen) | Key, exact spoken text, take and duration beside the sound | — |
| Error prevention (Nielsen) | Unsaved-marks prompt; stub pack cannot be marked; download keeps inventory order | — |

Nielsen heuristics checked: visibility of status (progress), error prevention and recovery (unplayable rows, codec banner) needed explicit states above; the rest pass.

## Evidence
- No prototype and no layout or contrast run: the page is rendered by the generator, and its output is checked by the backend's AC 17 test (files present, every key and take listed, offline). **Not verified:** the rendered page itself, keyboard handling, storage resume and reflow at 200 %; to check on the first stub-pack output.
- Timing: none beyond "playback starts at once" (local files).
