---
id: NAV-016
title: Mongolian voice clip pack (Android): build-time Chimege generator, versioned private pack, segment output from the voice text generator, gapless playback with the chime fallback, plus the D95 coordinated change (Mongolian km rounding and the spoken-form layer)
phase: 1
priority: must         # triage P1 / standard, PO-confirmed 2026-10-13 (D233). BA MoSCoW mapping: under D91 NAV-005 does not reach external users before NAV-016 ships (or an AC 10-passing voice exists)
size: L
needs_design: true     # no new screen; navigation-ux §4.1 (pending block comes into force), §4.6 (Mongolian voice = pack, D80) and §4.7 (longest prompts from the spoken forms) need UX updates
needs_backend: true    # build-time generator tooling in backend/tools/voice-pack/ (ADR-0010 §4); no runtime service, no gateway or openapi.yaml change
needs_mobile: true     # Android playback and the Kotlin generator; the web voice text port of the D95 coordinated change (text only, no clips on the web)
status: in-progress    # 2026-10-13 (orchestrator): real FEMALE5v2 / MALE4v2 packs generated and demo APKs built. Was ready since 2026-10-13, D228: start now. NAV-007 AC 9 gate waived for building; ADR-0010 acceptance happens in this lane (architect). Open questions 1–5 decided 2026-10-13 (D229–D233); D234–D236 applied 2026-10-13; Open question 6 open (not blocking internal builds)
---

# NAV-016: Mongolian voice clip pack for Android

## Story
As a **UB commuter by car**, I want **turn-by-turn prompts spoken in natural Mongolian by the same voice on every Android phone, also offline and on phones with no Mongolian TTS voice**, so that **I can follow guidance by ear without reading the screen while I drive**.

Secondary personas:
- **Taxi / delivery driver:** the same voice on cheap phones without Google services (D58), and no extra data use, because nothing is downloaded at runtime.
- **Intercity / countryside driver (unpaved roads, weak signal):** prompts play with no network, including long «… километр үргэлжлүүлэн явна уу» prompts up to 990 km (D94). Nothing is spoken for 995 km or more (D102).
- **Pedestrian:** the «Явган» schedule uses the same pack.
- **Tourist (English UI):** unchanged. English keeps device TTS and its own rounding (D96).

## Context
- **Request and gates.** Spike follow-up F5 (triage log 2026-10-01, F5 row) refines the draft backlog row NAV-016. The F5 row named three start gates:
  - NAV-007 AC 9 device evidence: **waived for building** ([D228](../decisions.md)). AC 9, AC 10 and AC 11 still run on the pack before any public release, and AC 9 still confirms [D76](../decisions.md).
  - The spike F4 voice choice: **met.** Chimege `FEMALE5v2` (female) and `MALE4v2` (male) are the provisional candidates ([D226](../decisions.md)), and Chimege is the voice source ([D227](../decisions.md)).
  - ADR-0010 accepted: **done by the architect inside this lane.** ADR-0010 is `proposed` today.
- **Decisions that shape this story:**
  - [D76](../decisions.md): the clip pack is the expected fallback, with the chime plus A1 as the last resort.
  - [D79](../decisions.md): the clips are a private build artifact and are never committed.
  - [D80](../decisions.md): the pack plays before any device `mn` voice. Device TTS is not used for Mongolian once the pack exists. English keeps device TTS.
  - [D91](../decisions.md) and [D17](../decisions.md): NAV-005 does not reach external users before NAV-016 ships (or an AC 10-passing voice exists), and NAV-007 comes first.
  - [D92](../decisions.md)–[D103](../decisions.md): spoken numerals.
    - Half km is the only fraction.
    - The spoken form is a separate layer.
    - Continue-on uses whole km up to 99, then the nearest 10 km, with a bound of 999 km.
    - Rounding and the spoken layer ship with NAV-016 in one coordinated change.
    - English is unchanged.
    - Distances only.
    - The table is the BA-owned TSV.
    - A11 and «10 метрээс бага» have entries.
    - Nothing is spoken for 995 km or more.
    - Continue-on is 188 whole clips plus 1 tail clip.
  - [D87](../decisions.md): the broken-join criterion in NAV-007 AC 10.
  - [D83](../decisions.md): the reference device is the budget model.
  - [D108](../decisions.md): phone-test builds get a quick check plus the PO's phone test, and full QA runs before any public release.
  - [D180](../decisions.md): internal APKs go to the PO only, by direct file transfer.
  - [D35](../decisions.md): public repository, so no secrets.
- **Chimege API (facts relayed by the orchestrator, 2026-10-13; the vendor documents are not committed, D46):**
  - Endpoint `https://api.chimege.com/v1.2/synthesize`. WAV output at 8,000, 16,000 or 22,050 Hz.
  - Input text: Cyrillic only, no digits, at most 300 normalized characters per request.
  - 11 voices. This story uses `FEMALE5v2` and `MALE4v2` (D226).
  - The token is held by the PO, lives in the environment only, and is never in the repository.
  - *2026-10-13 ([D234](../decisions.md)):* the PO keeps the token that was pasted in chat (no rotation) and accepts the risk of security finding VP-8. The orchestrator used it from a temporary file outside the repository, deleted after use, to generate the real `FEMALE5v2` and `MALE4v2` clips. Release checklist gate C24 is recorded as risk-accepted by the PO.
- **F4 pre-screen (D226):** 8 prompts in spoken form × 11 voices at 22,050 Hz: 88/88 succeeded, every clip within NAV-007 AC 11 (single max 3.43 s, chained max 6.35 s). These were **whole-prompt** clips. **Segment joins and non-final takes were not tested**, so the join quality (D87) is first heard with this story's pack. `FEMALE3v2` and `FEMALE5v2` gave identical durations, so they may be the same voice.
- **D95 coordinated change: preconditions met ([D232](../decisions.md), 2026-10-13).** D95/D101 say the Mongolian km rounding and the spoken-form layer ship with NAV-016, in **one** change to Android, the NAV-017 web port and the regenerated voice golden set, after NAV-005 is accepted and NAV-017 is finished.
  - The PO confirmed both on 2026-10-13. NAV-005 and NAV-017 are now `done`, and D104 is applied (NAV-005 has the D91 reference and the D102 line).
  - So sections D and I are part of this story.
  - *History:* on the morning of 2026-10-13 the BA found neither acceptance recorded (NAV-005 was only treated as accepted in the QA test plan), which is why the question was asked.
- **Scope by platform.** Android playback only. iOS (NAV-015) and web clip playback come later, on the same pack format (ADR-0010 §3). **Exception, required by D101:** the NAV-017 web voice text gets the same Mongolian rounding and spoken-form layer in this story (text only, no clips), so the cross-platform golden check (NAV-017 AC 26, R10) keeps passing.
- **What exists today:**
  - Android voice text: `mobile/android/app/src/main/java/mn/navmn/app/instructions/VoiceText.kt` (digits, NAV-005 AC 32 rounding). Voice selection and output: `voice/VoiceSelection.kt` and `voice/VoiceOutput.kt` (device TTS, chime, A1). Schedule: `voiceplan/VoiceSchedule.kt`.
  - Web voice text: `web/src/guidance/voiceText.ts`.
  - Voice golden set: `tests/gpx/nav005/golden/voice-golden.tsv`.
  - Spoken table: [`glossary-c3-spoken.tsv`](../glossary-c3-spoken.tsv), 217 entries: 1 `dist.lt10`, 25 `dist.m.*`, 3 `dist.km.*`, 188 `cont.km.*`.
  - No generator, no pack and no segment output exist yet.
- **Status of the Mongolian text.** Every segment's spoken text is glossary wording that is `PO-approved … (panel pending)` or `needs native review`. The numerals and table entries are all `needs native review`. The NAV-007 panel can still change any of them, and each change means regenerating and re-listening to the pack (ADR-0010 Consequences).

### Terms used in this story (team-internal, no UI text)
- **Segment:** one audio clip for one phrase, identified by a **key**, for example `dist.m.300` or `instr.turn.right`.
- **Take:** `final` (sentence-final intonation) or `nonFinal` (continuing intonation, used for `{first}` before «дараа нь», spike §3.2).
- **Inventory:** the complete, deterministic list of (key, take, spoken text) that a pack must contain.
- **Pack:** the audio files of one voice plus `manifest.json`.
- **Prompt:** one utterance from the voice text generator. It plays as an ordered list of at most 4 segments.

### Segment inventory (binding; the spoken text is quoted from the glossary)
Spoken texts are the glossary wording in lower case (the generator compares case-insensitively with the glossary and the `mn` resources). Distance spoken texts are copied from `spoken_mn` in the TSV and never composed (C3a).

| Group | Keys | Spoken text (`mn`) | Count | `nonFinal` take |
|---|---|---|---|---|
| Distances (TSV) | every `key` of `glossary-c3-spoken.tsv` | its `spoken_mn`, for example `dist.m.300` «гурван зуун метрт», `dist.km.1_5` «нэг хагас километрт», `cont.km.120` «зуун хорин километр», `dist.lt10` «арван метрээс бага» | 217 | no |
| Turns | `instr.turn.left`, `.right`, `.slightLeft`, `.slightRight`, `.sharpLeft`, `.sharpRight` | «зүүн тийш эргэнэ үү», «баруун тийш эргэнэ үү», «бага зэрэг зүүн тийш эргэнэ үү», «бага зэрэг баруун тийш эргэнэ үү», «огцом зүүн тийш эргэнэ үү», «огцом баруун тийш эргэнэ үү» | 6 | yes |
| U-turn, straight | `instr.uturn`, `instr.straight` | «буцаж эргэнэ үү», «чигээрээ явна уу» | 2 | yes |
| Keep | `instr.keep.left`, `instr.keep.right` | «зүүн талаа барина уу», «баруун талаа барина уу» (fork `straight` uses `instr.straight`) | 2 | yes |
| Merge | `instr.merge`, `.left`, `.right` | «замд нийлнэ үү», «зүүн талаас замд нийлнэ үү», «баруун талаас замд нийлнэ үү» | 3 | yes |
| Ramps | `instr.onRamp`, `.left`, `.right`; `instr.offRamp`, `.left`, `.right` | «орох зам руу эргэнэ үү», «зүүн талын орох зам руу эргэнэ үү», «баруун талын орох зам руу эргэнэ үү», «гарах зам руу эргэнэ үү», «зүүн талын гарах зам руу эргэнэ үү», «баруун талын гарах зам руу эргэнэ үү» | 6 | yes |
| Roundabout | `instr.roundabout.exit.1` … `.10`, `instr.roundabout.enter`, `instr.roundabout.leave` | «тойрогт ороод нэгдүгээр гарцаар гарна уу» … «тойрогт ороод аравдугаар гарцаар гарна уу» (C4 words нэгдүгээр, хоёрдугаар, гуравдугаар, дөрөвдүгээр, тавдугаар, зургаадугаар, долоодугаар, наймдугаар, есдүгээр, аравдугаар; whole phrase per exit, so no join inside it), «тойрогт орно уу», «тойргоос гарна уу» | 12 | yes |
| Depart | `instr.depart.n`, `.ne`, `.e`, `.se`, `.s`, `.sw`, `.w`, `.nw` | «хойд зүг рүү явна уу», «зүүн хойд зүг рүү явна уу», «зүүн зүг рүү явна уу», «зүүн өмнө зүг рүү явна уу», «өмнө зүг рүү явна уу», «баруун өмнө зүг рүү явна уу», «баруун зүг рүү явна уу», «баруун хойд зүг рүү явна уу» | 8 | yes |
| Arrival | `instr.arrive`, `.left`, `.right` | «та очих газартаа ирлээ», «таны очих газар зүүн талд байна», «таны очих газар баруун талд байна» | 3 | no (`arrive` is never `{first}`, navigation-ux §4.4) |
| Tails | `tail.approach` (A11), `tail.continue` (A12) | «очих газартаа хүрнэ», «үргэлжлүүлэн явна уу» | 2 | no |
| Join | `join.then` (A13) | «дараа нь» | 1 | no |
| Fixed | `fixed.offRoute`, `fixed.gpsLost`, `fixed.gpsRestored` | «та маршрутаас гарлаа», «жи пи эс дохио тасарлаа», «жи пи эс дохио сэргэлээ». "GPS" is spoken «жи пи эс» (glossary section 7 "GPS (spoken form)", [D231](../decisions.md)). The voice text keeps «GPS дохио тасарлаа» / «GPS дохио сэргэлээ» | 3 | no |

**Totals per voice:** 265 `final` + 39 `nonFinal` = **304 entries** (AC 4). ADR-0010's illustrative key `tail.arrive` for A11 is named `tail.approach` here, so that it is not confused with the arrival `instr.arrive`. The architect aligns ADR-0010 decision 2.

### Glossary terms
1. **Spoken (Cyrillic) form of "GPS": added 2026-10-13** as «жи пи эс» (glossary section 7, `needs native review`, [D231](../decisions.md); the PO pre-reviewed and chose it). It is used only in the spoken layer.
2. **Voice setting labels** (for example a "Voice" row and "Female" / "Male" options): **not needed now.** There is no setting ([D229](../decisions.md)). They are added only if the PO chooses a setting after the NAV-007 panel.

No term is needed for "clip pack", "segment" or "take": they are team-internal and never shown.

## Acceptance criteria

### A. Segment inventory
1. **Given** the repository, **When** the inventory is built, **Then**:
   - it is generated deterministically from two sources: `glossary-c3-spoken.tsv` for the 217 distance keys, and one machine-readable inventory file for the non-distance keys (location: architect, for example `backend/tools/voice-pack/inventory.tsv`)
   - the result equals the "Segment inventory" table above: same keys, takes and spoken texts
   - two runs on the same commit give byte-identical inventory output (UTF-8, LF, sorted by key then take)
2. **Given** the inventory, **When** it is checked, **Then**:
   - every non-distance spoken text equals the Android `mn` resource value for the same phrase, compared case-insensitively. Those resources are already glossary-checked (NAV-005 AC 61). The one exception: in `fixed.gpsLost` / `fixed.gpsRestored` the token "GPS" is replaced by the glossary spoken form «жи пи эс» (D231).
   - every distance spoken text equals `spoken_mn` of the same `key` in the TSV, byte for byte
   - the check fails on any difference and names the key
3. **Given** every spoken text in the inventory, **When** it is scanned, **Then** it has:
   - **0** characters `[0-9]`
   - **0** Latin letters `[A-Za-z]`
   - **0** zero-width characters U+200B–U+200D or U+FEFF
   - **0** Avoid terms (NAV-007 AC 16 scan)
   - length **≤ 300** characters
   - only Cyrillic letters (including «ө», «ү», «ё»), spaces and the punctuation allowed by the vendor (the architect lists it from the Chimege documents)
4. **Given** the glossary row "GPS (spoken form)" «жи пи эс» (section 7, [D231](../decisions.md)), **When** the inventory is built, **Then**:
   - it has **304** entries per voice
   - `fixed.gpsLost` has the spoken text «жи пи эс дохио тасарлаа»
   - `fixed.gpsRestored` has the spoken text «жи пи эс дохио сэргэлээ»
   - with a valid pack, the GPS lost and restored prompts play from the pack (NAV-005 AC 51–52), and their on-screen text stays «GPS дохио тасарлаа» / «GPS дохио сэргэлээ»

   If the panel revises the row, the spoken texts change with it (AC 5).
5. **Given** a change to the TSV or to any glossary wording used in the inventory, **When** the inventory is rebuilt, **Then** its `inventorySha256` changes, and an older pack is rejected by the build and by the app (AC 20, AC 46).

### B. Build-time generator (Chimege)
6. **Given** the generator in `backend/tools/voice-pack/` (ADR-0010 §4, owner backend-engineer), **When** it runs with the real synthesiser for voice `FEMALE5v2` or `MALE4v2`, **Then**:
   - it calls `https://api.chimege.com/v1.2/synthesize` **once per inventory entry** that is not in the cache (AC 10), with the entry's spoken text, the voice ID and **22,050 Hz** WAV output
   - it never calls Chimege at app runtime: **0** Chimege references in `mobile/` or `web/`
   - the request parameter names follow the vendor documents, kept outside the repository (D46); the architect records our own summary of them
7. **Given** any inventory entry that breaks AC 3 (a digit, a Latin letter, more than 300 characters or a disallowed character), **When** the generator starts, **Then** it exits non-zero **before sending any request**, names the key and the rule, and sends **0** requests.
8. **Given** the token, **When** the generator needs it, **Then**:
   - it reads it **only** from the environment variable `CHIMEGE_API_TOKEN`
   - `.env.example` lists `CHIMEGE_API_TOKEN=` with an empty value
   - **0** tracked files contain a non-empty value for that variable
   - if the variable is missing or empty, the generator exits non-zero with a message that names the variable (never a value) and sends **0** requests
9. **Given** a test run with `CHIMEGE_API_TOKEN` set to a sentinel value against a local fake Chimege server, **When** the run succeeds, and again when the server answers 401, 429, 500 and a timeout, **Then** the sentinel appears **0** times in stdout, stderr, log files, error messages, the manifest, the cache index, the review files (AC 17) and the pack directory.
10. **Given** a local cache outside the repository (path from configuration; never under a tracked path), **When** the generator runs again with an unchanged inventory and voice, **Then** it sends **0** requests and produces a byte-identical pack (AC 13).
    - **When** one spoken text changes, **Then** it sends exactly **1** request per take of that key: 1, or 2 if the key has a `nonFinal` take.
    - The cache key covers the vendor API version, voice ID, sample rate, spoken text and take method.
11. **Given** an HTTP 429, a 5xx or a timeout (30 s per request), **When** a request fails, **Then** it is retried at most **3** times in total, with back-off 1 s, 2 s and 4 s (or `Retry-After` when present). Any other 4xx fails at once without a retry. At most **2** requests run at the same time.
    - **When** an entry still fails, **Then** the run exits non-zero, writes **no** pack and no manifest to the output directory, and leaves the previous pack untouched.
12. **Given** the `nonFinal` take, **When** it is generated, **Then**:
    - it is produced by a method the architect records in ADR-0010, for example synthesising the phrase followed by «, дараа нь» and cutting at the boundary, or a trailing comma
    - the method name is written to the manifest (`takeMethod`)
    - each `nonFinal` file's sha256 differs from the `final` file of the same key
13. **Given** the same cache contents, inventory, voice and generator version, **When** the generator runs twice, **Then**:
    - every audio file and `manifest.json` are byte-identical between the runs
    - the manifest contains no timestamps or host names
    - `packVersion` is an input (for example `--pack-version 1`) and is never derived from the clock
14. **Given** the raw WAV clips, **When** they are processed, **Then** each output clip:
    - is mono
    - has leading and trailing silence (below −45 dBFS) of **≤ 40 ms**
    - has a fade-in and fade-out of **10 ms** (no click)
    - has a peak **≤ −1 dBFS**
    - has an active-speech RMS within **±2 dB** of the median of all clips in the pack (a peak-limited clip, pack-format §4, may be down to **−3 dB** below it)
    - has a duration between **150 ms** and **4,000 ms**
    - has **no** internal silence longer than **500 ms**

    A clip that breaks any of these fails the run and is named in the output.
15. **Given** a clip, **When** it is encoded, **Then**:
    - it uses one format that decodes natively on Android 8.0+ (minSdk 26), iOS and current Safari, Chrome and Edge (ADR-0010 §3; codec chosen by the mobile engineer with the architect, recorded in ADR-0010; for example AAC-LC `.m4a`)
    - the decoded PCM length matches the manifest `durationMs` within **±10 ms**, so encoder padding does not add an audible gap. The decoded length is measured with the Android decode path the player uses.
16. **Given** the stub synthesiser (for example `--synth stub`), **When** the generator runs without a token and without network, **Then**:
    - it produces a complete pack with the same inventory, manifest schema and processing
    - each clip is a deterministic synthetic tone or silence whose length is proportional to the spoken text length
    - the manifest has `source.vendor = "stub"`
    - it sends **0** network requests
    - the CI pipeline and every automated test in this story use only the stub or the fake server, never the real API
17. **Given** a generated pack, **When** the run finishes, **Then** the output directory also contains files for local listening only (never deployed or committed):
    - `review/index.html` (offline; lists every key, take, spoken text and an audio player)
    - `review/review.tsv` with the columns `key`, `take`, `spoken_mn`, `result` (`ok` / `bad`), `note`
    - `review/prompts/`, with the two longest prompts of AC 33 and 20 coverage prompts rendered with the manifest's join rules (AC 21)

    These files are written for every pack, real or stub. They are used for the per-segment listen before the first NAV-007 AC 10 session and before any public build ([D230](../decisions.md)). Internal PO builds, including the two `demo` APKs of [D236](../decisions.md), need no listen: the PO waived the PO's own listen and confirmed both voices without listening ([D235](../decisions.md)). Who does the two remaining listens is Open question 6; until it is answered, both stay required.

### C. Pack format and private artifact
18. **Given** a pack, **When** `manifest.json` is read, **Then** it holds:
    - `schemaVersion` (1)
    - `packVersion`
    - `voiceId` (for example `FEMALE5v2`)
    - `source` (`vendor`, `apiVersion`, `voice`)
    - `sourceSampleRateHz` (22050)
    - `codec`
    - `takeMethod`
    - `joinPauseMs` (default **80**)
    - `thenPauseMs` (default **80**)
    - `glossaryRevision`: sha256 of the TSV bytes plus the inventory file bytes (ADR-0010 §3)
    - `inventorySha256`
    - one entry per inventory entry, with `key`, `take`, `spokenText` (exact), `file`, `durationMs` and `sha256`

    The manifest contains no token, path outside the pack, user name, host name or timestamp.
19. **Given** the repository, **When** it is scanned, **Then**:
    - **0** tracked files have an audio extension (`.wav`, `.m4a`, `.aac`, `.mp3`, `.ogg`, `.opus`, `.flac`), except the project chime at its existing path (navigation-ux §4.8)
    - **0** tracked files are a pack `manifest.json` or `review/*`
    - the generator output directory and any local pack directory are in `.gitignore`

    This is D79, and it holds until written terms allow public redistribution.
20. **Given** an Android build, **When** it bundles a pack, **Then** it reads the pack only from the Gradle property `nav.voicePackDir`: a path to an unpacked pack directory, set on the command line or in an uncommitted local file, the same pattern as `nav.demoTilesFile` (D182). The build verifies:
    - every file's sha256
    - that the manifest's `inventorySha256` equals the inventory compiled into the app (AC 5)
    - that `schemaVersion` is supported

    On any mismatch it fails with a message naming the property and the first failed check. The build never downloads a pack.
21. **Given** the manifest's `joinPauseMs` and `thenPauseMs`, **When** a prompt is played or rendered, **Then**:
    - the pause after a distance prefix (or a `cont.km.*` before `tail.continue`) is `joinPauseMs`
    - the pause before and after `join.then` is `thenPauseMs`
    - there is no pause before the first segment or after the last
    - the app, the review renderer (AC 17) and any NAV-007 AC 10 harness use these same rules and values

### D. Voice text generator: segment output, spoken layer and Mongolian rounding (the D95 coordinated change; preconditions met, D232)
22. **Given** a voice trigger in the Mongolian UI, **When** the Android generator (`VoiceText`) runs, **Then**:
    - it returns, for each prompt, the **display text** (digits, unchanged role: golden set, NAV-005 AC 40), the **spoken text** (C3a, D93) and the ordered list of **segments** (key + take), all from one pure function on the JVM
    - in the English UI it returns the English text unchanged (D96) and an **empty** segment list
22a. **Given** the Mongolian distance prefix, **When** it is produced, **Then** the D92 kilometre rounding applies:
    - 995 ≤ *d* < 1,250 m → «1 километрт»
    - 1,250 ≤ *d* < 1,750 m → «1,5 километрт»
    - *d* ≥ 1,750 m → nearest whole km, half up

    The metre bands and D67 are unchanged.
22b. **Given** a continue-on prompt, **When** it is produced, **Then** the D94 rounding applies: whole km below 99,500 m, then the nearest 10 km, with the largest value 990 km. For a continue-on distance of **995 km or more**, **no** continue-on prompt is produced, and the next manoeuvre's own prompts still play (D102).
22c. **Given** a manoeuvre 1,300 m ahead in the Mongolian UI, **When** the banner and the voice text are produced, **Then** the banner keeps C3 with one decimal («1,3 км-т», unchanged), while the voice says «1,5 километрт» (spoken «нэг хагас километрт»). QA expects this difference (D92, D96).
23. **Given** these inputs, **When** generated in the Mongolian UI, **Then** the outputs are exactly:

    | Input | Display text | Spoken text | Segments (take `final` unless marked) |
    |---|---|---|---|
    | 300 m + `turn right` | «300 метрт баруун тийш эргэнэ үү» | «гурван зуун метрт баруун тийш эргэнэ үү» | `dist.m.300`, `instr.turn.right` |
    | 980 m + `turn right` | «1 километрт баруун тийш эргэнэ үү» | «нэг километрт баруун тийш эргэнэ үү» | `dist.km.1`, `instr.turn.right` |
    | 1,249 m + `fork` left | «1 километрт зүүн талаа барина уу» | «нэг километрт зүүн талаа барина уу» | `dist.km.1`, `instr.keep.left` |
    | 1,250 m + `fork` left | «1,5 километрт зүүн талаа барина уу» | «нэг хагас километрт зүүн талаа барина уу» | `dist.km.1_5`, `instr.keep.left` |
    | 1,750 m + `turn left` | «2 километрт зүүн тийш эргэнэ үү» | «хоёр километрт зүүн тийш эргэнэ үү» | `dist.km.2`, `instr.turn.left` |
    | 20 m + `turn left` | «Зүүн тийш эргэнэ үү» | «зүүн тийш эргэнэ үү» | `instr.turn.left` |
    | 200 m + roundabout `exit` 2, chained with `turn slight right` | «200 метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү» | «хоёр зуун метрт тойрогт ороод хоёрдугаар гарцаар гарна уу, дараа нь бага зэрэг баруун тийш эргэнэ үү» | `dist.m.200`, `instr.roundabout.exit.2` (**nonFinal**), `join.then`, `instr.turn.slightRight` |
    | start: depart north, chained with `turn right` | «Хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү» | «хойд зүг рүү явна уу, дараа нь баруун тийш эргэнэ үү» | `instr.depart.n` (**nonFinal**), `join.then`, `instr.turn.right` |
    | approaching, 200 m | «200 метрт очих газартаа хүрнэ» | «хоёр зуун метрт очих газартаа хүрнэ» | `dist.m.200`, `tail.approach` |
    | continue on, 2,480 m | «2 километр үргэлжлүүлэн явна уу» | «хоёр километр үргэлжлүүлэн явна уу» | `cont.km.2`, `tail.continue` |
    | continue on, 99,499 m / 99,500 m / 123,000 m / 994,999 m | «99 …» / «100 …» / «120 …» / «990 километр үргэлжлүүлэн явна уу» | «ерэн есөн километр …» / «зуун километр …» / «зуун хорин километр …» / «есөн зуун ерэн километр үргэлжлүүлэн явна уу» | `cont.km.99` / `cont.km.100` / `cont.km.120` / `cont.km.990`, then `tail.continue` |
    | continue on, 995,000 m | no prompt | — | — |
    | `arrive` | «Та очих газартаа ирлээ» | «та очих газартаа ирлээ» | `instr.arrive` |
    | off-route | «Та маршрутаас гарлаа» | «та маршрутаас гарлаа» | `fixed.offRoute` |
    | GPS lost / restored | «GPS дохио тасарлаа» / «GPS дохио сэргэлээ» | «жи пи эс дохио тасарлаа» / «жи пи эс дохио сэргэлээ» | `fixed.gpsLost` / `fixed.gpsRestored` |

    The spoken `cont.km.*` texts are the TSV `spoken_mn` values (QA checks them against the shared fixture). "…" in the table abbreviates only the repeated tail «километр үргэлжлүүлэн явна уу».
24. **Given** any Mongolian prompt, **When** its spoken text is compared with its segments, **Then** the spoken text equals the segments' spoken texts joined as follows (first letters compared case-insensitively):
    - a space after a distance prefix and after `cont.km.*`
    - «, » before `join.then` and a space after it
25. **Given** every Mongolian prompt for G1, G2, G5, G8, G9, G10 and the AC 27 fixture, **When** the spoken text is scanned, **Then** it has **0** characters `[0-9]` and **0** Latin letters (so "GPS" is always «жи пи эс», D231), and every spoken distance phrase equals a `spoken_mn` value of the TSV (NAV-005 AC 32 pending note, NAV-007 AC 13 last bullet).
26. **Given** the voice golden set (QA-owned), **When** it is regenerated for this change, **Then**:
    - the `mn` display texts follow AC 22a–22b
    - it gains a spoken-text column and a segment-list column
    - the `en` rows are byte-identical to the previous set (D96)
    - the Android and web generators both match it (AC 48)

### E. Domain and duration checks (JVM)
27. **Given** the generator domain, **When** the domain test enumerates it, **Then** every produced segment (key and take) exists in the inventory, and every `dist.*` / `cont.km.*` key exists in the TSV. The test fails, naming the input, on any unmapped key or any spoken text that breaks AC 24. The domain covers:
    - costings `auto`, `pedestrian`, `bicycle`
    - every AC 27 manoeuvre row, including roundabout `exit` 1–10, > 10 and missing
    - the prompt kinds early, main, now and catch-up
    - *d* from 0 to 2,000 m in 1 m steps, plus the boundary values 29.99, 30, 94.99, 95, 974.99, 975, 994.99, 995, 1,249.99, 1,250, 1,749.99 and 1,750 m
    - every chainable `{first}` × every non-`arrive` `{second}`, at one *d* per prefix key
    - approaching at 30–500 m
    - continue-on from 2,000 m to 1,000,000 m in 10 m steps, plus 99,499, 99,500, 994,999 and 995,000 m
    - the fixed prompts
28. **Given** the inventory, **When** the domain test ends, **Then** every non-distance inventory entry was produced at least once. An orphan entry fails the test. Distance keys may be unused (for example `dist.lt10`).
29. **Given** a pack manifest (a real pack when `nav.voicePackDir` is set; in CI the Kotlin stub manifest at 70 ms per character, ADR-0010 §7), **When** the duration check runs over the **whole** generator domain, **Then** it computes each prompt's duration as the sum of the segment `durationMs` values plus the AC 21 pauses, against the limits **≤ 5,000 ms** for a single-cue prompt and **≤ 8,000 ms** for a chained prompt (NAV-007 AC 11, clip-pack rule).
    - In every case it writes the 10 longest single and the 10 longest chained prompts with their durations (`longest-prompts.tsv`, in the AC 10 harness prompt-list format).
    - **Stub manifest (`source.vendor` = `stub`): report only.** The check prints every prompt over a limit with its excess and does **not** fail. The 70 ms per character model is not speech, so it cannot prove or disprove AC 11.
    - **Real pack (`source.vendor` ≠ `stub`): enforced.** In any variant, the check fails if any prompt exceeds a limit and names the first one.
    - **`demo` and `release` builds:** the check runs before the build (`checkVoicePackDurations` before `preDemoBuild` / `preReleaseBuild`) and the build fails if any prompt of the bundled pack exceeds a limit (these variants accept only a real pack, AC 50).
30. **Given** the domain test and the duration check, **When** they run in `./gradlew :app:testDebugUnitTest`, **Then** together they finish in **≤ 120 s** on the CI machine.

### F. Android playback
31. **Given** a build with a valid real pack, the Mongolian UI and voice on, **When** a prompt triggers, **Then** its segments play **in order**, with the AC 21 pauses, through one audio session for the whole prompt. The first segment starts **≤ 1 s** after the trigger (NAV-005 AC 34), checked with a fake clock on the JVM.
32. **Given** the same build, **When** the device reports a usable `mn` / `mn-MN` TTS voice (a fake TTS in the test), **Then** **0** Mongolian utterances go to `TextToSpeech`, and the pack plays (D80).
33. **Given** the two longest prompts by spoken duration in the real pack (a single one and a chained one, from the AC 29 list; navigation-ux §4.7 re-derived from the spoken forms, NAV-007 AC 11), **When** they play on the reference Android device (D83), **Then**:
    - the measured durations meet AC 29's limits
    - the silence measured at each join equals the configured pause **±20 ms**
    - no click is audible

    Real-device check: listed as not verified until a device run (AC 57).
34. **Given** a prompt, **When** audio plays, **Then**:
    - audio usage is `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`
    - transient focus with ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) is requested **once per prompt**, not per segment, and released **≤ 1 s** after the last segment ends
    - if focus is denied, the **whole** prompt is skipped, not queued (NAV-005 AC 36)
35. **Given** guidance, **When** the existing playback rules apply, **Then** they hold for pack prompts exactly as for TTS prompts (navigation-ux §4.5):
    - one prompt at a time
    - a prompt that cannot start within **3 s** of its trigger is dropped
    - mute («Дууг хаах») stops playback within **1 s** and suppresses all prompts until «Дууг нээх»
    - off-route stops the current prompt and plays `fixed.offRoute`
    - arrival waits at most 3 s for the current prompt
    - a language switch stops the current prompt
    - «Дуусгах» stops any audio within **2 s**
    - a phone call stops a playing prompt, and one catch-up prompt follows the call (NAV-012 §12.6)
    - audio output changes follow NAV-012 §12.5
36. **Given** a prompt, **When** any of its segments cannot be loaded or decoded **before** it starts, **Then** the prompt plays the chime instead: never a partial prompt, never a missing word. **When** decoding fails **during** a prompt, **Then** playback stops within **1 s**, and AC 43 applies.
37. **Given** guidance with the network off (airplane mode), **When** prompts trigger, **Then** they play from the pack, and the session makes **0** voice-related network requests (NAV-005 AC 65 unchanged).
38. **Given** the English UI, **When** guidance runs, **Then** English uses device TTS with the chime fallback, unchanged (NAV-005 AC 38–39 for English, D96). The pack is not used, and A1 is not shown.
39. **Given** the route preview opens, **When** the voice decision is prepared (navigation-ux §4.6 timing), **Then** the pack is validated: manifest readable, `schemaVersion` supported, `inventorySha256` matches the app, every file present with a matching sha256. This takes **≤ 1 s** on the reference device (real device: not verified, AC 57; the JVM test checks the logic). If validation has not finished at «Эхлэх», the depart prompt waits up to **2 s** and then plays the chime.
40. **Given** a NAV-012 restore after process death, **When** guidance resumes, **Then** the pack is validated again (AC 39) with **0** network requests, and the following prompts play from the pack.

### G. Fallback (chime plus A1)
41. **Given** a build without a pack (debug only, AC 50), or a pack that fails AC 39 for any reason, **When** guidance runs in the Mongolian UI, **Then** for the whole session:
    - every prompt (manoeuvre, off-route, GPS, arrival) plays the chime (≤ 1 s, navigation-ux §4.8)
    - the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» (A1) is shown once per session for at least **5 s** without covering the banner or the attribution (NAV-005 AC 39)
    - device `mn` TTS is **not** used (ADR-0010 decision 1)
42. **Given** a valid pack, **When** guidance runs in the Mongolian UI, **Then** A1 is **not** shown.
43. **Given** a playback error during a session (AC 36), **When** it happens, **Then** within **1 s** the rest of the session uses the AC 41 fallback, and A1 is shown if it was not shown yet.
44. **Given** a corrupted pack in a JVM/Robolectric test, **When** the decision runs, **Then** the result is "fallback" with a reason code, logged only in debug builds and without coordinates (NAV-005 AC 67). The corruptions tested are:
    - one byte changed in one audio file
    - one file deleted
    - the manifest truncated
    - `inventorySha256` changed
    - `schemaVersion` 99
    - a stub pack in a `release` / `demo` variant

### H. Voice choice
45. **Given** this story, **When** the app is built, **Then** **one** voice is bundled: the one in `nav.voicePackDir`. The default pack for every PO build is **`FEMALE5v2`**. A `MALE4v2` pack is generated by the same command, and an internal build with it is made by pointing `nav.voicePackDir` at it (for the PO and for the NAV-007 AC 10 sessions on both D226 voices). **No** voice setting is shown in the UI ([D229](../decisions.md)). Whether drivers get a female/male setting is decided after the NAV-007 panel has rated both voices, through a change on this story. For the PO, both voices are delivered now as **two separate internal `demo` APKs**, one per voice, each bundling only its own pack ([D236](../decisions.md)).
46. **Given** a pack whose `voiceId` is not `FEMALE5v2` or `MALE4v2`, or whose `inventorySha256` does not match the app, **When** a `release` or `demo` build runs, **Then** it fails (AC 20). A `debug` build plays the fallback (AC 41).

### I. Web port of the coordinated change (D101; text only, no clips)
47. **Given** `web/src/guidance/voiceText.ts`, **When** the D95 change lands, **Then**:
    - the web Mongolian voice text follows AC 22a–22c byte for byte with Android for every golden row
    - a Mongolian browser utterance, when a local Mongolian voice exists (D78), receives the spoken text (AC 24 rules, from the same TSV)
    - English is unchanged
48. **Given** the regenerated golden set (AC 26), **When** the NAV-017 cross-platform golden check runs, **Then** it passes with **0** differences in the display texts, and the web spoken texts equal the golden spoken column.
49. **Given** the web build, **When** it is inspected, **Then** it contains **0** audio clips from the pack and **0** references to a pack directory (web clip playback is a later web story, D79 note), and NAV-017 AC 42 (0 backend requests) still holds.

### J. Builds, release gates and privacy
50. **Given** the build variants, **When** they are built, **Then**:
    - `debug`: the pack is optional. Without one, or with the stub pack, the app plays the fallback (AC 41).
    - `demo` (NAV-019): a valid real pack (not stub) is required, unless `-Pnav.voicePackDir=none` is passed explicitly (then the fallback plays). A missing or invalid value fails with a message naming the property.
    - `release`: a valid real pack is required, with no `none` option.
51. **Given** a build with one bundled voice, **When** the APK size is compared with the same build without a pack, **Then** the difference is **≤ 2.0 MB**.
52. **Given** any build, **When** the APK and the Gradle configuration are inspected, **Then** they contain **0** occurrences of `CHIMEGE_API_TOKEN` and **0** token values. The app never needs the token.
53. **Given** a public build is prepared (any distribution beyond the PO and the team: store, website or outside testers), **When** the release checklist is run, **Then** all of these hold. Until they do, pack builds go only to the PO by direct file transfer (D228, D180) and are never published.
    - `decisions.md` has a D row recording Chimege's written output terms, allowing in-app redistribution and stating the data location (D226, D228)
    - the NAV-007 sign-off record lists the AC 9 device matrix, the AC 10 listening scores on this pack version and voice, and the AC 11 duration evidence (D228)
    - a native per-segment listen for that pack version and voice is complete with **0** `bad` rows in `review.tsv` (ADR-0010 §4, [D230](../decisions.md)). The same listen is also done before the first NAV-007 AC 10 session. The PO waived the PO's own listen for internal builds ([D235](../decisions.md)); this public-build gate is not waived. Who listens is Open question 6 (D230 named the PO).

    The release checklist gate C24 (token rotation after the 2026-10-13 chat exposure, VP-8) is recorded as risk-accepted by the PO ([D234](../decisions.md)). It is not an AC of this story; the security engineer may still flag it at the public-release gate.
54. **Given** guidance, **When** prompts play, **Then** no route-derived data leaves the device for voice, because the pack plays locally. At build time the generator sends Chimege **only** inventory spoken texts (glossary wording and numerals, no coordinates, street names or user data).

### K. Verification
55. **Given** the light process for internal phone-test builds (D108), **When** QA and the orchestrator check this story, **Then** the required automated checks are:
    - the generator tests: AC 1–5, 7–14 and 16 with the stub synthesiser and fake server; AC 15 decoded-length check on the stub pack
    - `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`: AC 22–31, 34–36, 39–46, run with the stub pack and a fake TTS
    - `:app:lintDebug` and `:app:assembleDebug`
    - the web unit tests and the NAV-017 golden check (AC 47–49)
    - the repository scans (AC 19, AC 8, AC 52)
56. **Given** the two real packs, `FEMALE5v2` and `MALE4v2`, generated with the PO's token (by the PO, or by an agent session the PO gave the token to, [D234](../decisions.md); the token never in a tracked file, AC 8), **When** each is built into its own `demo` APK for the PO ([D236](../decisions.md)) and both pass the build checks (AC 20, AC 29 enforced on the real pack), **Then** the PO's phone check on the **`FEMALE5v2`** APK (the default pack) covers:
    - 3 NAV-019 replays (R1, R2, R3) with voice on
    - every prompt plays complete in Mongolian, with no missing word, no chime instead of a prompt (except the ADR-0010 §2 unmapped catch-up case) and no audible gap at a join
    - mute and unmute
    - one phone call during a prompt
    - Bluetooth output, if available
    - airplane mode

    The `MALE4v2` APK needs no separate phone check (BA scoping: it differs only in the bundled pack, which passes the same build checks); the PO may install it at will.

    This is a playback check, not a voice-quality listen. The PO confirmed both voices without listening and waived the per-segment listen for internal builds ([D235](../decisions.md)); voice quality is judged by the NAV-007 panel (AC 10). The PO reports the result. Until then these items are not verified.
57. **Given** this story's checks, **When** QA reports, **Then** the report lists as **not verified in this environment**:
    - real-device playback, join silence and measured durations (AC 33)
    - validation time (AC 39)
    - Bluetooth wake-up clipping (Edge cases)
    - the NAV-007 AC 9–11 runs, which are NAV-007's

## Edge cases
- **GPS lost / restored:** `fixed.gpsLost` / `fixed.gpsRestored` play from the pack, spoken «жи пи эс …» (AC 4, D231). The on-screen text keeps «GPS».
- **No network:** the pack plays fully offline (AC 37). Generation needs the network only on the build machine.
- **Off-route:** stops the current prompt and plays `fixed.offRoute` (AC 35). The catch-up prompt after a reroute comes from the pack.
- **Unpaved roads / countryside:** continue-on prompts to 990 km. At 995 km or more nothing is spoken, and the next manoeuvre's prompts still play (D102).
- **No route / no result:** no voice prompt exists for these (on-screen only, NAV-005), so the pack is unaffected.
- **Cyrillic / Latin:** Chimege rejects Latin, so "GPS" is spoken «жи пи эс» (AC 3, AC 4, D231). Street names are never spoken (C5), so OSM `name` / `name:mn` data cannot reach the pack.
- **Winter:** no specific behaviour. Prompts must stay audible over a heater fan or with windows closed. This is covered by the NAV-007 AC 10 ≥ 70 dB(A) condition, not here.
- **Phone reports an `mn` TTS voice** (for example a user-installed eSpeak NG): the pack still plays (D80, AC 32).
- **Pack from an older glossary revision:** the build fails (`release` / `demo`) or the fallback plays (`debug`). Never a pack with stale wording (AC 5, AC 20).
- **Bluetooth A2DP wake-up:** some car kits clip the first 100–300 ms of audio. Device TTS has the same issue. It is recorded in the PO phone check (AC 56). Any fix (for example a short leading silence) is a change on this story.
- **Language switch during a prompt:** the prompt stops, and the next prompt uses the new language (AC 35, AC 38).
- **Vendor outage during generation:** the run fails, and the previous pack stays (AC 11).
- **Vendor output changes** (for example the voice is retrained): the cache keeps the existing audio. A cleared cache produces new audio, which needs a new `packVersion` and a new listen.

## Data dependencies & risks
- **Chimege written output terms (business risk, D226/D228):** in-app and public redistribution and data location. Without them, no public build carries the pack (AC 53). The terms may also limit internal storage or bundling. That is unknown until they arrive.
- **Token not rotated (business risk accepted by the PO, [D234](../decisions.md)):** the token pasted in chat on 2026-10-13 stays valid, and copies remain on the agent machine outside the repository (security finding VP-8, medium). Anyone who obtains those copies can call Chimege on the PO's account. The release checklist gate C24 is risk-accepted by the PO; the security engineer may still flag it at the public-release gate.
- **Chained prompt duration on the real pack (ADR-0010 §7):** on the stub model the longest chained prompt (`dist.m.650` + roundabout exit 6, chained) is 8,540 ms against the 8,000 ms limit. AC 29 is enforced on a real pack, so a real pack may fail the `demo` build on chained roundabout prompts. What to do then is a product question for the PO, raised when it happens.
- **Voice availability:** Chimege may change or retire `FEMALE5v2` / `MALE4v2`. The cache and the stored pack keep the current audio. Where the canonical pack and cache are kept is an architect design question (below).
- **`FEMALE3v2` / `FEMALE5v2` may be the same voice (D226):** no impact on this story. It is relevant if the PO wants a second female option later.
- **Join prosody (D87):** the pre-screen used whole prompts only. The `nonFinal` takes and the pauses are first tested with this pack. If the panel flags broken joins, pauses or takes change through a change on this story. ADR-0010 lists this as a revisit trigger.
- **Wording churn:** every glossary or TSV change after the NAV-007 panel (including a D92 overrule) means regenerating, re-listening and rebuilding. Batch panel revisions (ADR-0010 Consequences).
- **GPS spoken form «жи пи эс»** is `needs native review` (D231). If the panel revises it, the two GPS segments are regenerated.
- **Encoder padding:** AAC-type codecs add priming samples. AC 15 catches this, and the architect picks a codec and decode path that meets it.
- **OSM data:** none. The voice speaks no street names, `maxspeed` or lanes.

## Out of scope
- iOS playback (NAV-015) and web clip playback (a later web story). Both reuse this pack format.
- Spoken street names (spike F7), runtime or cloud TTS, on-device models, downloadable packs (ADR-0010 §5 "Later").
- An English pack: English keeps device TTS (D80, D96).
- Human voice talent: optional later (D77 b2).
- A user voice setting (decided after the NAV-007 panel, D229) or a speech-rate setting.
- Publishing clips anywhere public (D79).
- Changing the prompt schedule, banner text or notification text (ADR-0010 "Unchanged").

## NAV-005, NAV-017 and NAV-019 behaviour that changes when this ships (those stories' AC are not edited now; NAV-005 and NAV-017 are `done`, D232)
- **NAV-005:**
  - AC 32: the pending D92–D96 note comes into force (section D).
  - AC 33: adds the spoken-text scan (AC 25).
  - AC 38: Mongolian uses the pack, not device TTS (D80).
  - AC 39: the fallback triggers are a missing or invalid pack or a playback error (section G).
  - AC 40: the generator also returns segments.
  - AC 71: new tests.
  - AC 73: real-phone pack items.
  - D104: already applied 2026-10-13 (D232). NAV-005 has the D91 reference and the D102 line in its AC 32 note.
- **NAV-017:** the voice text port follows AC 22a–22c and the spoken layer (AC 47–48). The golden check uses the regenerated set. No clips are added (D75).
- **NAV-019:** the `demo` build bundles the pack (AC 50). The PO hears the Mongolian voice instead of the chime plus A1.
- **NAV-007:** no AC change. AC 10 (a) and AC 11 (clip pack) run on this pack. AC 13's last bullet becomes applicable.

## Architect design questions (technical, not PO decisions)
1. Accept or revise ADR-0010 for D226–D228:
   - vendor Chimege and the two voices
   - the key names in this story (`tail.approach`)
   - `nonFinal` takes in pack v1
   - two pause constants
   - validation at preview open
2. The `nonFinal` take method that Chimege supports (AC 12).
3. Codec, bitrate and the Android decode / playback path (for example Media3 or decode-to-PCM with `AudioTrack`) that meets AC 15 and AC 33.
4. Where the canonical pack and the generator cache are kept (the PO's machine, private storage), and how the PO or an agent produces a pack without the token entering the repository or CI.
5. Our own summary of the Chimege request parameters and the allowed punctuation (AC 3, AC 6), with the vendor documents kept outside the repository (D46).
6. How a NAV-007 AC 10 harness reuses the manifest and join rules (AC 21).

## Open questions
**One open (Open question 6, non-blocking for internal builds).** Questions 1–5 were decided by the PO on 2026-10-13 ("All as recommended", relayed by the orchestrator) as the BA recommendation: Q1 → [D229](../decisions.md), Q2 → [D230](../decisions.md), Q3 → [D231](../decisions.md), Q4 → [D232](../decisions.md), Q5 → [D233](../decisions.md). The questions are kept below as asked.

1. **Voice choice for drivers. Decided: (c), [D229](../decisions.md).** The PO picked a female and a male candidate (D226), and the UX has no voice setting. Options:
   - (a) bundle `FEMALE5v2` only, no setting
   - (b) bundle both voices with a setting (about +1.3 MB; needs glossary rows and a UX spec)
   - (c) ship `FEMALE5v2` now, and decide between (a) and (b) after the NAV-007 panel has rated both voices (AC 10)

   **BA recommendation: (c).** It keeps the APK small and adds no unreviewed strings now, and the panel result shows whether a second voice is worth a setting.
2. **Who does the native per-segment listen. Decided: (b), [D230](../decisions.md)** (ADR-0010 §4: "every segment is checked by a native listener before a pack is released"), and when. Options:
   - (a) the PO, before every internal build
   - (b) the PO, before the first NAV-007 AC 10 session and before any public build, not for internal PO builds
   - (c) a NAV-007 panel member, before any public build

   **BA recommendation: (b),** using the generator's review page (AC 17). About 304 clips take roughly 30–40 minutes per voice (estimate), and internal builds are themselves listening checks.
3. **Spoken form of "GPS". Decided: (a) «жи пи эс», glossary row added, [D231](../decisions.md).** Asked as a missing glossary term for the two GPS prompts. Options:
   - (a) «жи пи эс»
   - (b) «жи пи ес»
   - (c) no GPS segment: those two prompts stay a chime with their on-screen text

   Any wording starts `needs native review`, the PO pre-reviews it and the NAV-007 panel decides (AC 10 checks "GPS"). **BA recommendation: the PO pre-reviews (a) and (b), and the chosen one is added as a glossary row; until then (c) applies (AC 4).**
4. **D95 preconditions. Decided: (a), [D232](../decisions.md); NAV-005 and NAV-017 are `done`, and D104 is applied.** NAV-005 acceptance and "NAV-017 finished" are not recorded (Context). Options:
   - (a) the PO confirms now that NAV-005 is accepted and NAV-017 is finished. The coordinated change (sections D and I) is built in this story. The BA then records the acceptance and applies D104.
   - (b) build sections A–C and E–H first with the stub segment lists, and start sections D and I only after the PO records both acceptances

   **BA recommendation: (a)** if the PO is content with both (QA already treats NAV-005 as accepted since 2026-10-01, and NAV-017's fix round passed). Under (b) the player cannot be tested end to end, because segment output and the rounding are one change (ADR-0010 decision 2).
5. **Priority / class. Decided: P1 / standard, [D233](../decisions.md).** The triage proposal is P1 / standard (F5 row). The PO's "Just go forward" (D228) started the story but did not set the class. **BA recommendation: P1 / standard** (MoSCoW must: D91 makes NAV-016 a gate for the NAV-005 external release).
6. **Who does the per-segment listen now that the PO does not listen ([D235](../decisions.md))? Open, added 2026-10-13.** D230 put the PO's listen before the first NAV-007 AC 10 session and before any public build (AC 17, AC 53, checklist C22). D235 waives the PO's own listen for internal builds but keeps the public-build gate. It does not say who listens instead. Not blocking internal builds; it blocks the first AC 10 session and any public build. Options:
   - (a) the PO still does both listens (D230 unchanged for these two points; D235 then covers internal builds only)
   - (b) the NAV-007 paid editor (D18–D20) does both listens on the review page (AC 17), and the PO keeps the final call on disagreements (D20)
   - (c) no separate listen: the NAV-007 AC 10 session is the first native listen, and before a public build the panel's AC 10 result replaces gate 3 of AC 53 (needs a change to AC 53 and checklist C22)

   **BA recommendation: (b),** the NAV-007 paid editor on the review page, with the PO's final call. It keeps a native check of every one of the 304 segments per voice before outside listeners hear the pack, matches the PO's wish not to listen, and fits the existing panel model. (c) is weaker: an AC 10 session hears a sample of prompts, not every segment.

   **When it is asked (orchestrator, 2026-10-13):** stays open and is put to the PO when the NAV-007 panel is set up, not now.

## Traceability
| AC | Screen spec | API operation | Code | Test | Issues |
|---|---|---|---|---|---|
| AC 1–5 (inventory) | — | none | `backend/tools/voice-pack/` (inventory), Android inventory hash | generator tests; inventory vs `mn` resources and TSV (QA fixture, D99) | F5 triage row |
| AC 6–17 (generator) | — | none (vendor API at build time only) | `backend/tools/voice-pack/`, `.env.example` | stub synthesiser, fake Chimege server, token sentinel test | D230, D234 (token, VP-8), D235 (AC 17) |
| AC 18–21 (pack, artifact) | — | none | manifest schema (ADR-0010 §3), Gradle `nav.voicePackDir` | repository scan, build verification tests | D79 |
| AC 22–26 (generator output, rounding) | navigation-ux §4.1 (pending block comes into force) | none | `instructions/VoiceText.kt` | JVM tests; regenerated `tests/gpx/nav005/golden/voice-golden.tsv` | D92–D103 |
| AC 27–30 (domain, duration) | navigation-ux §4.7 | none | `checkVoicePackDurations` (Gradle, before `preDemoBuild` / `preReleaseBuild`) | JVM domain test and duration check (stub manifest report-only, real pack enforced; `longest-prompts.tsv`) | NAV-007 AC 11; ADR-0010 §2 (unmapped catch-up prompts, backlog follow-up), §7 (AC 29 enforcement) |
| AC 31–40 (playback) | navigation-ux §4.5, §4.6, §12.5, §12.6 | none | `voice/` (pack player, selection) | Robolectric with fake clock and fake TTS; PO phone check (AC 56) | D80 |
| AC 41–44 (fallback) | navigation-ux §4.6, §4.8 | none | `voice/` | corruption tests | NAV-005 AC 39 |
| AC 45–46 (voice choice) | — | none | Gradle property | build tests | D226, D229, D236 |
| AC 47–49 (web port) | navigation-ux §11.5 | none | `web/src/guidance/voiceText.ts` | web unit tests, NAV-017 golden check | D101 |
| AC 50–54 (builds, gates, privacy) | — | none (`openapi.yaml` unchanged) | Gradle variants | APK size and scan tests, release checklist (C20–C24) | D228, D17, D91, D230, D234 (C24 risk-accepted), D235; Open question 6 |
| AC 55–57 (verification) | — | — | — | QA report, PO phone check on the `FEMALE5v2` demo APK | D108, D234, D235, D236 |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-10-13 | Spike follow-up F5 (triage log 2026-10-01, F5 row); PO "Just go forward" 2026-10-13 ([D228](../decisions.md)) after [D226](../decisions.md)/[D227](../decisions.md) | Created from the draft backlog row. Status `ready`. AC 1–57 (with 22a–22c) in sections A–K: segment inventory (304 entries per voice), Chimege build-time generator with stub and fake server, private pack (D79) via `nav.voicePackDir`, the D95 coordinated change (Android rounding, spoken layer, segment output, web text port, golden set), domain and duration checks, gapless Android playback, chime plus A1 fallback, a single bundled voice (`FEMALE5v2` default), release gates. Five open questions for the PO; six architect design questions; missing glossary terms listed (spoken "GPS", voice setting labels) | D228 starts NAV-016 now: the AC 9 gate is waived for building and F4 is met by D226/D227. ADR-0010 is accepted in this lane |
| 2026-10-13 | PO "All as recommended" in chat to Open questions 1–5 ([D229–D233](../decisions.md), relayed by the orchestrator) | **Open questions:** all five marked decided. **Frontmatter:** priority P1 / standard confirmed (D233). **Context:** the D95 preconditions are met (D232: NAV-005 accepted, NAV-017 finished, D104 applied), so sections D and I are in scope; section D heading updated. **Inventory:** the fixed GPS segments are spoken «жи пи эс дохио тасарлаа» / «жи пи эс дохио сэргэлээ» (new glossary row "GPS (spoken form)", D231); 304 entries per voice with no interim count; "Missing glossary terms" became "Glossary terms". **AC 2:** GPS exception to the resource match. **AC 4:** rewritten: the GPS prompts play from the pack, the on-screen text keeps «GPS», and the interim chime is removed. **AC 23:** GPS row added. **AC 25:** 0 Latin letters in spoken text. **AC 17, AC 53:** the PO does the per-segment listen before the first AC 10 session and before any public build (D230). **AC 45:** no voice setting, decided after the panel (D229). Edge cases, risks, Out of scope, the NAV-005 change list (D104 applied) and Traceability updated. No other AC changed | The PO accepted every BA recommendation |
| 2026-10-13 | PO chat 2026-10-13 "Option 2 Just use it i don't wanna regenerate the Token. About the voice you go with the FEMALE5v2 MALE4v2 Go with this choices I dont need to listen" ([D234–D236](../decisions.md), relayed by the orchestrator); ADR-0010 §7 duration enforcement (architect request) | **AC 29:** reworded to ADR-0010 §7: the stub manifest (70 ms per character) only reports (`longest-prompts.tsv`, excess printed); a real pack fails the check in any variant; `demo` and `release` run `checkVoicePackDurations` before the build and fail on any excess. Replaces "the stub pack in CI" + "release or demo build fails". **AC 17:** review files written for every pack; no listen for internal PO builds including the two D236 APKs (D235); the remaining listener is Open question 6. **AC 53:** gate 3 is "a native per-segment listen", no longer named as the PO's (D235), still required before a public build and before the first AC 10 session; note that checklist C24 is risk-accepted by the PO (D234). **AC 56:** two real packs, each in its own `demo` APK (D236), token per D234; the PO's phone check runs on the `FEMALE5v2` APK, the `MALE4v2` APK needs none (BA scoping); "every prompt heard" is now "every prompt plays complete", a playback check, not a voice-quality listen (D235); no chime except the ADR-0010 §2 unmapped catch-up case. **AC 45:** both voices delivered to the PO as two separate internal APKs, still no setting (D236). **Context:** D234 token note. **Risks:** token not rotated (D234); chained-prompt duration on the real pack (ADR-0010 §7). **Open questions:** new Open question 6 (who listens). **Traceability** updated. Backlog: follow-up row for bounded `dist.km.N` rows above 2 km (ADR-0010 §2), through triage | The PO declined the token rotation, confirmed both voices without listening and asked for both; ADR-0010 §7 made duration enforcement depend on the pack source, and the story wording had to follow it |
