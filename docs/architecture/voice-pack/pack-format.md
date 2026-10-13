# Voice clip pack format v1 (contract)

- **Owner:** architect. **Date:** 2026-10-13. **Stories:** NAV-016 (Android, generator); later NAV-015 (iOS) and a web clip story.
- **Decision record:** [ADR-0010](../adr/0010-mongolian-voice-pregenerated-clip-pack.md) (accepted 2026-10-13).
- **Files in this directory (all contract, all architect-owned):**
  - `manifest.schema.json`: JSON Schema (draft 2020-12) of `manifest.json`, `schemaVersion` 1
  - `inventory.tsv`: the 48 non-distance segment keys (the 217 distance keys come from the BA-owned [`glossary-c3-spoken.tsv`](../../requirements/glossary-c3-spoken.tsv))
  - this file: the rules JSON Schema cannot express
- **Who implements what:** the generator (`backend/tools/voice-pack/`, backend-engineer) **writes** packs that satisfy this file; the Android app and its Gradle build (mobile-engineer) **read and verify** them. Neither side changes this file on its own (CLAUDE.md rule 2, applied to this contract). A change goes through the architect, and a breaking change bumps `schemaVersion`.
- **Not in `openapi.yaml`.** Packs are bundled at build time. No HTTP operation serves them (ADR-0010 §5), so `openapi.yaml` is unchanged.
- **Clarifications** (none changes the format or `schemaVersion`):
  - 2026-10-13, after the integration review: §8.1 says which check owns which fault (V2/V3/V4/V8), the integer and control-character reading rules, the generator-only `UNLISTED_FILE` check, and the `debug` rule for V7.
  - 2026-10-13, security VP-4/VP-5 and QA follow-ups: §8.2 adds file-type, location and size rules for pack files, checked in V2 and V9 with the new reason code `FILE_INVALID` (a validation change, not a manifest change, so `schemaVersion` stays 1). §8.3 marks packs built against a fake server as `source.vendor` = `stub`.
  - 2026-10-13, backend fix round (MALE4v2 run): §4 states that `silenceThresholdDbfs` is a level of the delivered (post-gain) clip, and defines windows and active-speech RMS exactly. §4 lets a **peak-limited** clip sit up to 3 dB below the pack median instead of 2 dB (the only relaxation; every pack that passed before still passes, and the processing parameters and manifest bytes are unchanged). §8.2 states the codes for a file that changes size between V9 and V10, and for an empty or missing `clips/`.
  - 2026-10-13, backend commit 1901557 (MALE4v2 `fixed.gpsRestored`): §4 states the **edge re-trim**: when the delivered PCM's leading or trailing silence exceeds the 40 ms limit, the region is re-derived from the delivered PCM and the trim → fades → gain fixed point is run again, at most 4 times. A clip within the limit is never re-trimmed, so `FEMALE5v2` pack v1 (manifest `6023abbe…80b9`) is byte-identical. §4 also states that the ffmpeg pin is the **release** (a build suffix such as `-static` passes and writes the same `codec.encoder`). No limit, order or parameter changes; `processing` and `schemaVersion` are unchanged.

Key words: **must** = required for a valid pack or a conforming client.

## 1. Directory layout

### 1.1 Inputs (in the repository)
| File | Owner | Role |
|---|---|---|
| `docs/requirements/glossary-c3-spoken.tsv` | BA | Distance segments: column `key` and `spoken_mn` (217 rows today). Read as UTF-8, tab-separated, first line is the header. Every row is one `final` entry |
| `docs/architecture/voice-pack/inventory.tsv` | architect | Non-distance segments. Columns `key`, `nonfinal` (`yes`/`no`), `spoken_mn`, `source` (human note: the Android `mn` resource it equals; ignored by programs). Every row gives one `final` entry, plus one `nonFinal` entry when `nonfinal` = `yes` (48 rows: 48 `final` + 39 `nonFinal`) |

Total today: 217 + 48 = **265 `final` + 39 `nonFinal` = 304 entries per voice** (NAV-016 AC 4). Changes to the wording come from the glossary (BA) and the NAV-007 panel. The architect updates `inventory.tsv` in the same change, and the Android JVM test (§8, AC 2) fails if it drifts from the `mn` resources.

### 1.2 Generator output (never in the repository, D79)
```
<out>/<voiceId>-v<packVersion>/          one run, written to a temporary sibling and renamed at the end
  pack/                                  THE PACK: the only directory a client build may read (nav.voicePackDir)
    manifest.json
    clips/<key>.<take>.aac               one file per entry, e.g. clips/dist.m.300.final.aac, clips/instr.turn.right.nonFinal.aac
  review/                                local listening only; never bundled, deployed or committed (NAV-016 AC 17)
    index.html                           offline page: key, take, spoken text, <audio> pointing at ../pack/clips/…
    review.tsv                           key, take, spoken_mn, result (empty, then ok/bad), note
    prompts/<id>.wav                     rendered prompts (§6), 16-bit PCM WAV at codec.sampleRateHz
    prompts.tsv                          the prompt list that was rendered (§7) with durationMs
```
- `<out>` and the cache directory must be outside the repository, or inside it only at a path that `git check-ignore` reports as ignored. The generator refuses any other path before it sends a request.
- A pack directory must contain only `manifest.json` and the `clips/` files listed in it.
- Every file in a pack is a **regular file** inside the pack directory (no symbolic links), within the size caps of §8.2. The generator writes only regular files and never creates links.

## 2. Inventory, `inventorySha256` and `glossaryRevision`

### 2.1 Entries
An entry is the triple (`key`, `take`, `spokenText`), with `take` ∈ {`final`, `nonFinal`}.

### 2.2 Text rules (checked before any request; NAV-016 AC 3, AC 7)
Every `spokenText` must:
- match `^[а-яёөү]+( [а-яёөү]+)*$`. That means lower-case Mongolian Cyrillic letters (U+0430–U+044F, U+0451 ё, U+04AF ү, U+04E9 ө) and single spaces only. No leading or trailing space, no digits, no Latin, no punctuation, no zero-width characters.
  - This is stricter than the vendor's allowed set (ADR-0010 §4.1). The only punctuation that ever reaches the vendor is the comma added by the take method.
- be in Unicode NFC
- be 2–300 characters long. The request text after the take method (for example `<spokenText>, дараа нь`) must also be ≤ 300 characters
- contain no Avoid term (the NAV-007 AC 16 matching rules)

A distance entry's `spokenText` must equal its TSV `spoken_mn` byte for byte. A non-distance entry's `spokenText` must equal the lower-cased Android `mn` resource phrase. The one exception: the token "GPS" is replaced by the glossary spoken form «жи пи эс» (D231).

### 2.3 Canonical serialisation
1. Build all entries from §1.1.
2. Sort by `key`, then by `take`, comparing the UTF-8 bytes (ASCII order: `final` < `nonFinal`). Duplicate (`key`, `take`) pairs are an error.
3. Write each entry as `<key>\t<take>\t<spokenText>\n`. No header, UTF-8 without BOM, LF line endings, and an LF after the last line too.

`inventorySha256` = the lowercase hex SHA-256 of those bytes.
- The generator writes it to the manifest.
- The Android build computes it from the same two input files and compiles it into the app.
- A client recomputes it from the manifest `entries`, and it must match both values.

*Value at the time of writing* (the inputs at commit `372e26e` plus this directory's `inventory.tsv`): `681d5e39b8cd21d0b97104e3c4b0f5b417388885497c2ca429a42c3edf2b8bc5`. Tests should compute it rather than hard-code it. *Re-checked 2026-10-13 at commit `4a64023`:* the generator (`voicepack inventory`), the Android build (`VoiceInventoryBuilder`) and the web (`gen-voice-inventory.mjs`) each give this value from the committed inputs, with 304 entries.

### 2.4 `glossaryRevision`
`glossaryRevision` = the lowercase hex SHA-256 of the raw bytes of `glossary-c3-spoken.tsv`, followed immediately by the raw bytes of `inventory.tsv`. It is for tracing only. A status-only TSV edit changes it but not `inventorySha256`. Clients never compare it.

## 3. `manifest.json` bytes (determinism, NAV-016 AC 13)
- It must validate against `manifest.schema.json`.
- It is UTF-8 without BOM. Cyrillic is written as is (no `\u` escapes), with 2-space indentation, LF line endings and one final LF.
- Object members appear in the order of the schema's `required` lists.
- `entries` is sorted as in §2.3.
- Integers have no decimal point. The numbers in `processing` are written as the generator's literal constants (for example `-45`, `-20`, `-1`).
- The manifest contains no timestamp, host name, user name, absolute path or token.

Same cache contents + same inventory + same voice + same options + the same generator image ⇒ byte-identical `manifest.json` and clips.

## 4. Clip audio (NAV-016 AC 14, AC 15)
- **Codec:** AAC-LC, mono, raw **ADTS** stream (`.aac`) at `codec.sampleRateHz` (default 22,050), target `codec.bitrateKbps` (default **24**). It is encoded by the ffmpeg version pinned in the generator image, with bit-exact flags and no metadata. **The pin is the release (clarified 2026-10-13):** the pinned version is the ffmpeg release number (7.0.2 for pack v1). A static or distribution build of that release (version string `7.0.2-static`, `7.0.2-0ubuntu1`) passes the pre-flight check without any override flag and writes the same `codec.encoder` (`ffmpeg-7.0.2-aac`); the run output names the build. Byte identity (§3) is guaranteed only within the reference image; between a differently built binary of the same release and the image it is expected, not proven, so a pack meant for release is built in the image. The encoder's priming is `codec.encoderDelayFrames` (1,024 for the ffmpeg `aac` encoder, measured 2026-10-13: ADTS output has exactly `ceil((frames + 1024) / 1024)` access units, and the decoded onset is shifted by 1,024 frames).
- **Processing order:** raw vendor WAV → (nonFinal only: cut, ADR-0010 §4.3) → trim → fades → gain → checks → encode. The parameters are recorded in `processing`:
  - **Level reference (clarified 2026-10-13):** `silenceThresholdDbfs` (−45) is a level of the **delivered** clip, never of the vendor WAV. "Delivered" means the 16-bit PCM after gain and rounding, which is the PCM that is encoded. Every comparison with the threshold uses that level:
    - the trim's speech region;
    - the windows that make up the active-speech RMS, both for setting the gain and for the checks;
    - the edge-silence check and the internal-silence check.

    Before the gain is applied, the generator therefore compares the signal with `silenceThresholdDbfs − G`, where `G` is the clip's gain in dB. The region depends on `G`, and `G` depends on the region. So the generator starts with `G` = 0 and repeats trim → fades → gain until the region is unchanged and `G` changes by less than 0.01 dB, for at most 8 rounds. The last round's region and gain are delivered, and the checks then run on the delivered PCM. The samples are trimmed, faded and gained once each, in that order. (Observed 2026-10-13 on an uncut scan: one `nonFinal` carrier reached the 8-round cap; as stated here, the last round was delivered and the checks decided. The cap is unchanged.) (Reason: Chimege delivers about −17 to −10 dBFS active RMS, so `G` is negative. With a raw-level threshold, 50–70 ms of soft word decay would fall below −45 dBFS after the gain and fail the 40 ms edge-silence check.)
  - **Windows and active RMS:** a window is `floor(rate / 100)` frames (220 at 22,050 Hz). Windows start at the first sample of the signal being measured, and the last window may be shorter. dBFS is relative to 32,768. A window is **active** if its RMS is strictly above the threshold. **Active-speech RMS** = the square root of (the sum of squares over all active windows ÷ their total frame count).
  - **Trim:** the speech region runs from the first to the last active window. Keep `edgeMarginMs` (5) of the original signal before and after it.
  - **Edge re-trim (clarified 2026-10-13, backend commit 1901557; no limit or order change):** the speech region is found before the fades, and the 10 ms fade-out (or fade-in) covers half of the region's edge window. A short blip just above the threshold after a gap is therefore active when the region is found but silent in the delivered PCM. (MALE4v2 `fixed.gpsRestored`, reproducible across vendor takes: about 50 ms at −46 to −52 dBFS after the last word, then one 10 ms window 0.6 dB above the threshold, leaving up to 90 ms of trailing silence in the delivered PCM.) So, after the fixed point above has converged, the generator measures the delivered PCM's leading and trailing silence. If either exceeds the 40 ms limit of the checks, the region is **re-derived from the delivered PCM's first and last active windows** (mapped back to the original signal, still with `edgeMarginMs` on each side), and the trim → fades → gain fixed point is run again from that region. This is repeated at most **4** times; if the edge is still over the limit, the edge-silence check fails the run as before. The samples are still trimmed, faded and gained once each, in that order, from the original signal: a re-trim restarts from the raw (or cut) vendor PCM with a smaller region, it never trims the already faded or gained PCM. A clip whose delivered edges are within the limit is **never** re-trimmed, so every pack that passed before is byte-identical (`FEMALE5v2` pack v1, manifest `6023abbe…80b9`, is unchanged). The run output names every re-trimmed clip with its key and take (`edge re-trim: <key> (<take>)`) so the PO's listen (D230) can look at it; that output is not a failure. `processing` has no new member, since the re-trim changes no parameter and is fully determined by the ones recorded.
    - *Observation, not a rule (2026-10-13):* an "exact" rule that required the post-fade edges to be within `edgeMarginMs` + `fadeMs` would tighten about 33 `FEMALE5v2` v1 clips that have 20–40 ms edges of this shape. It is **not** adopted for pack v1: those clips are within the limit and already listened to. It is a candidate for a future `packVersion` and would be decided then.
  - **Fades:** linear `fadeMs` (10) fade-in at the first sample and fade-out at the last sample.
  - **Gain:** one gain per clip, so that the active-speech RMS reaches `targetRmsDbfs` (−20). The gain is lowered if the sample peak would exceed `peakCeilingDbfs` (−1). No limiter, no compression and no dither: plain round-half-even to 16-bit.
- **Checks, each failing the run with the key named.** All are measured on the delivered PCM:
  - mono
  - leading and trailing silence ≤ 40 ms
  - peak ≤ −1 dBFS
  - active RMS within the **level band** below
  - 150 ms ≤ duration ≤ 4,000 ms
  - no internal run of windows below the threshold longer than 500 ms
  - decode check (§5) passes with the pinned ffmpeg decoder
  - for a `nonFinal` entry: its file `sha256` differs from the `final` file of the same key
- **Level band (decided 2026-10-13, backend fix round):**
  - **Pack median:** the median of the delivered active RMS (dBFS) over **all** entries of the pack, both takes. With an even count it is the mean of the two middle values. Deviation = clip active RMS − pack median.
  - **Peak-limited clip:** a clip whose delivered sample peak is ≥ `peakCeilingDbfs` − 0.1 dB (≥ −1.1 dBFS) **and** whose delivered active RMS is below `targetRmsDbfs`. This is the case where the ceiling capped the gain. The clip could only get louder through a limiter, which this format forbids. It is determined from the delivered PCM, never from a flag set during processing, so `verify`-style tools and QA can recompute it.
  - **Rule:** every clip has a deviation ≤ +2 dB. A clip that is not peak-limited has a deviation ≥ −2 dB. A peak-limited clip has a deviation ≥ **−3 dB** (the floor). A peak-limited clip below the floor fails the run with the rule `rms-outlier`, like any other outlier. A near-silent clip with an isolated click reaches the ceiling, but its crest factor puts it far below the floor, so it still fails.
  - **Reporting:** the run output names every peak-limited clip whose deviation is below −2 dB, with its key, take and deviation, so the PO's listen (D230) can look at it. That output is not a failure.
  - **Why** (MALE4v2, 2026-10-13): `dist.m.70` `final` has a 21.03 dB crest factor. With the −1 dBFS ceiling it can reach only −22.03 dBFS active RMS, which is −2.03 dB from the median. 13 MALE4v2 clips are peak-limited, and the next worst is −1.48 dB. Lowering `targetRmsDbfs` would change every delivered `FEMALE5v2` clip and invalidate pack v1 (manifest SHA-256 `6023abbe…80b9`) and its listen. A limiter would change the vendor speech and break the "no limiter" rule. Re-synthesising the entry would need changed text or a bypassed cache, and it gives no guarantee of a lower crest factor. This rule only relaxes the old ±2 dB band for clips that cannot be made louder, so every pack that passed before still passes, and `processing` and `schemaVersion` are unchanged.
- `frames` = the processed PCM length. `encodedFrames` = the number of ADTS access units × 1,024. `durationMs` = round-half-up(`frames` × 1000 / `codec.sampleRateHz`).

## 5. Trim rule (every decoder, every platform)
ADTS has no edit list, so platforms do not trim. The client trims, using the manifest. For one entry, with `P` = `encoderDelayFrames`, `F` = `frames`, `R` = `encodedFrames`:
1. Decode the whole file. Let `D` be the number of output frames, at the decoder's output rate `r_out`. If the output has 2 channels, use channel 0.
2. `k = r_out / codec.sampleRateHz` must be exactly 1 or 2. 2 covers decoders that apply implicit-SBR upsampling. Otherwise: `DECODE_FAILED`. Scale `P`, `F` and `R` by `k`.
3. `missing = R − D`. If −2 ≤ `missing` < 0, treat it as 0. If 0 ≤ `missing` ≤ `P`, then `start = P − missing`. This covers decoders that output the priming frame and decoders that drop the first access unit. Otherwise: `DECODE_FAILED`.
4. Take `F` frames from `start`. If fewer than `F` remain, pad with zeros only if the shortfall is ≤ 10 ms at `r_out`. Otherwise: `DECODE_FAILED`.

The result is exactly `F` frames. The decoded length therefore equals `durationMs` within rounding, so encoder padding never adds a gap (AC 15).
- **Android:** framework `MediaExtractor` + `MediaCodec`. Set `encoder-delay` and `encoder-padding` to 0 on the track format before `configure()`, so no platform skip-cut applies.
- **Clients whose decoder resamples** (web `decodeAudioData`) apply steps 3–4 in clip-rate units: `D_clip = round(D × sampleRateHz / r_out)`, with ±2 frames tolerance on `missing`.

## 6. Join rules, prompt assembly and duration (NAV-016 AC 21, AC 24, AC 29)
A prompt is an ordered list of 1–4 entries.
- **Pause between consecutive entries a → b:** `thenPauseMs` if a or b is `join.then`, otherwise `joinPauseMs`. In the generator domain "otherwise" happens only after a `dist.*` prefix and between `cont.km.*` and `tail.continue`.
- No pause before the first entry or after the last.
- **Pause frames** = round-half-up(pause ms × `r_out` / 1000). For 80 ms at 22,050 Hz that is 1,764.
- **PCM:** trimmed clip 1, then pause, then trimmed clip 2, and so on, as one buffer played in one audio session. A device lead-in (NAV-012 AC 33, Bluetooth) may be prepended. It is not part of the join rules or the duration.
- **Measured silence at a join (AC 33):** the inserted pause plus the two clips' edge margins (2 × `edgeMarginMs` = 10 ms) plus any faded speech that falls below the threshold. That stays within AC 33's ±20 ms of the configured pause.
- **Prompt duration (AC 29, NAV-007 AC 11):** `promptMs` = Σ `durationMs` + Σ pause ms. The frame-exact PCM length differs by at most 1 ms per entry, and tests allow that.
- **Spoken text (AC 24):** the `spokenText` values joined with one space, except a comma directly before `join.then`, giving «…, дараа нь …». The Android generator's spoken output must equal this.

The Android player, the generator's review renderer and any NAV-007 AC 10 harness must use exactly these rules and the manifest's pause values.

## 7. Prompt list format (shared by the review renderer, the Android duration report, the AC 10 harness and the golden set)
- TSV, UTF-8, LF, header line first. Required columns: `id` and `segments`. Extra columns are allowed (for example `durationMs`, `kind`, `input`).
- `id`: `[A-Za-z0-9._-]{1,64}`. It is used as the file name `prompts/<id>.wav`.
- `segments`: space-separated tokens `<key>:<take>`, for example `dist.m.200:final instr.roundabout.exit.2:nonFinal join.then:final instr.turn.slightRight:final`. An empty list is written as `-`.

The QA voice golden set (`tests/gpx/nav005/golden/voice-golden.tsv`, AC 26) uses the same token notation in its new segment column.

## 8. Validation (client runtime, build time and `voicepack verify`)
Checks run in this order. The first failure gives the reason code. Android logs reason codes only in debug builds and never with coordinates (NAV-005 AC 67).

| # | Check | Reason code |
|---|---|---|
| V1 | `manifest.json` present (no pack bundled) | `NO_PACK` |
| V2 | `manifest.json` is a regular file within the size cap (§8.2); `schemaVersion` readable and in the client's supported set (Android: {1}) | `SCHEMA_UNSUPPORTED` (`MANIFEST_UNREADABLE` if not JSON, not a regular file, or over the cap) |
| V3 | the rest of the manifest parses as schema v1 (types, patterns, ranges) | `MANIFEST_UNREADABLE` |
| V4 | `codec` = AAC-LC / ADTS / mono / 16,000 or 22,050 Hz | `CODEC_UNSUPPORTED` |
| V5 | release and demo builds: `source.vendor` ≠ `stub` | `STUB_NOT_ALLOWED` |
| V6 | release and demo builds: `voiceId` ∈ {`FEMALE5v2`, `MALE4v2`} | `VOICE_NOT_ALLOWED` |
| V7 | `entries` sorted and unique; the canonical hash recomputed from `entries` = `inventorySha256` = the compiled value | `INVENTORY_MISMATCH` |
| V8 | every `file` matches the pattern (no `..`, no absolute path); `durationMs` matches `frames`; `frames` ≤ `encodedFrames` − `encoderDelayFrames` | `ENTRY_INVALID` |
| V9 | every `file` exists; each is a regular file whose real path is inside the pack directory, within the per-clip cap; the total pack size is within the pack cap (§8.2) | `FILE_MISSING` (absent); `FILE_INVALID` (link, not a regular file, outside the pack directory, or over a cap) |
| V10 | every file's SHA-256 = `sha256` | `FILE_HASH_MISMATCH` |
| (play) | trim rule failure or decoder error | `DECODE_FAILED` |
| (play) | `AudioTrack` error during a prompt | `PLAYBACK_FAILED` |

- **Build time (Gradle, NAV-016 AC 20):** V2–V10 against `nav.voicePackDir`, plus the variant rules of AC 50. The failure message names `nav.voicePackDir` and the first failed check.
  - `debug` only (AC 46): if the **only** V7 failure is that `inventorySha256` differs from the compiled value, the build warns, bundles the pack and goes on to V8–V10. The app rejects that pack at runtime (`INVENTORY_MISMATCH`, fallback). A V7 failure in the manifest's own consistency (entries unsorted or duplicated, or entries that do not hash to the manifest's `inventorySha256`) fails the `debug` build like any other check.
- **Runtime (AC 39, AC 40):** V1–V10 at route-preview open and after a restore, with 0 network requests.

### 8.1 Which check owns which fault (normative, clarified 2026-10-13; no format change)
The first failing check in the order V1 → V10 gives the reason code. The generator's `verify`, the Gradle guard and the Android validator must give **the same code for the same pack**. Each check owns these faults:

| Check | Owns |
|---|---|
| V2 `MANIFEST_UNREADABLE` | the bytes are not UTF-8 JSON, or the top-level JSON value is **not an object** (for example an array) |
| V2 `SCHEMA_UNSUPPORTED` | an object without `schemaVersion`, or a `schemaVersion` that is not an integer in the supported set (`"1"`, `1.0` and `true` are not supported) |
| V3 `MANIFEST_UNREADABLE` | every other rule of `manifest.schema.json` (members present and no extra members, types, patterns, enums, ranges), **except** the faults listed under V4 and V8. That includes `codec.bitrateKbps`, `codec.encoderDelayFrames`, `codec.encoder`, a missing `codec` member, `source.vendor` and the `durationMs` range |
| V4 `CODEC_UNSUPPORTED` | the **values** of `codec.name`, `codec.container`, `codec.fileExtension`, `codec.channels` and `codec.sampleRateHz` (wrong const, wrong enum value, or wrong type for these five) |
| V8 `ENTRY_INVALID` | the `entries[].file` pattern (a `file` that is not a string is V3), plus the V8 rules in the table above |

Two reading rules belong to V3, so that "`voicepack verify` passes" implies "the app accepts":
- **Integers:** where the schema says `integer`, the JSON number must have no fraction and no exponent part (§3). `1.0` or `1e3` is V3, even though some JSON Schema validators accept `1.0` as an integer.
- **No control characters:** no string in the manifest contains U+0000–U+001F. Patterns match the **whole** string. Python's `re` `$` also matches before a final `\n`, so the generator must not rely on that for the schema patterns.

**Generator-only check G1 `UNLISTED_FILE`** (`voicepack build` and `voicepack verify`, after V10): the pack directory holds anything other than `manifest.json`, the `clips/` directory and the files the manifest lists (§1.2). It is not a client reason code. Gradle copies only the listed files, and the app reads only listed paths, so an extra file never reaches an APK.

Conformance: a shared fixture set should exist that gives the expected code for each single-fault corruption in this table, plus at least one two-fault case (`codec.bitrateKbps` out of range **and** `codec.name` wrong → V3). Each implementation runs it in its own tests.

### 8.2 Files on disk: type, location and size (normative, added 2026-10-13, security VP-4)
A pack directory is read from a path the builder chooses (`nav.voicePackDir`, `voicepack verify DIR`). Without these rules a link inside it could make a build copy an arbitrary file from the build machine into an APK, and an oversized file could exhaust memory or the APK budget.

**File rules** (every reader of a pack directory on a file system: `voicepack build` on its own output, `voicepack verify`, the Gradle guard and copy task):
- Resolve the pack directory once to its real path (`root`). The directory given may itself be reached through a link; everything below it may not.
- `manifest.json`, the `clips/` directory and every listed `file` must be checked **without following links** (`lstat`; Java `Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)` and `Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)`). `manifest.json` and every listed file must be a regular file. `clips/` must be a directory and not a link. A link, a device, a FIFO or a socket fails.
- The real path of each listed file must start with `root` + separator. (The V8 pattern already rules out `..` and absolute paths; this catches links in between.)
- Read and copy **only** the listed files, by content. The Gradle copy writes regular files into `voicepack/`; it never copies a link and never copies the directory as a tree. A file whose size changes between the check and the read fails the build.

**Size caps** (sizes in bytes, from the file system or the APK asset length):
| What | Cap | Basis |
|---|---|---|
| `manifest.json` | 1,048,576 (1 MiB) | about 80 KB expected for 304 entries; the worst case the schema allows (300-character Cyrillic texts) is about 0.3 MB |
| one clip file | 262,144 (256 KiB) | the longest clip is 4,000 ms (§4); at the schema's maximum of 64 kbit/s that is about 32 KB plus ADTS headers, so the cap leaves about 8× headroom for encoder overshoot |
| the whole pack (`manifest.json` + every listed clip) | 6,291,456 (6 MiB) | about 510 s of audio per pack (ADR-0010 §3.3): about 1.6–1.85 MB at 24 kbit/s, about 4.1 MB at 64 kbit/s. A 4 MB cap would reject schema-valid packs above about 56 kbit/s. The **delivery** budget stays NAV-016 AC 51 (≤ 2.0 MB APK growth), which is the tighter gate for every bundled build |

**Where each fault is reported** (same code everywhere, §8.1 order):
- `manifest.json` absent (no-follow check): V1 `NO_PACK`. Present but a link, not a regular file, or over 1 MiB: V2 `MANIFEST_UNREADABLE`, before it is parsed. Readers read at most cap + 1 bytes.
- A listed file absent (no-follow check): V9 `FILE_MISSING`. A listed file that is a link, not a regular file, outside `root`, or over 256 KiB, or `clips/` that is a link: V9 `FILE_INVALID`. V9 checks the files in `entries` order and reports the first fault; the total-size cap is checked after every per-file check has passed, still in V9 (`FILE_INVALID`), so V10 never hashes an oversized pack.
- *Clarified 2026-10-13 (mobile fix round):*
  - **`clips/` absent or empty:** this is not a fault of `clips/` itself. Every listed file is then absent, so the result is V9 `FILE_MISSING` for the first entry in `entries` order. `clips/` gives V9 `FILE_INVALID` only if it exists and is a link or is not a directory. That check runs before the per-file checks.
  - **A file that changes between V9 and V10:** V10 reads at most the size V9 recorded + 1 bytes. If the bytes read differ in count from that size (the file grew, shrank or vanished after V9), the result is V9 `FILE_INVALID`, not `FILE_HASH_MISMATCH` or `FILE_MISSING`. The hash is compared only after the count matches. A size change that the Gradle copy sees also gives `FILE_INVALID`. On Android assets this cannot happen in practice, but a bounded read that disagrees with the asset length gives the same code.
- G1 `UNLISTED_FILE` (generator only) also covers unlisted links in the pack directory.

**Android runtime:** the pack is read from APK assets, which hold only regular file contents written by the Gradle copy. The link and real-path rules are therefore met by construction and are not re-checked at runtime. The size caps **are** checked at runtime (asset length, or a bounded read of at most cap + 1 bytes), with the same codes.

### 8.3 Packs built against a fake server (normative, added 2026-10-13, security VP-5)
- The generator's `--api-base` accepts only a loopback host, so a run that sets it talks to a fake server (for example `tests/fake_chimege.py`), never to the vendor.
- Such a run **must** write `source.vendor` = `stub` and `source.apiVersion` = `stub`. `source.voice` and `voiceId` stay as requested. V5 then rejects the pack in `demo` and `release` builds (`STUB_NOT_ALLOWED`), exactly like a stub-synthesiser pack. The schema is unchanged.
- In its cache keys and `index.jsonl`, such a run uses the vendor value `fake` (distinct from `chimege` and `stub`). Fake audio can then never satisfy a later real request from the same cache directory.
- Only a run **without** `--api-base`, with `--synth chimege`, may write `source.vendor` = `chimege`.

## 9. Versioning and compatibility
| Field | Changes when | Who checks |
|---|---|---|
| `schemaVersion` | this contract changes incompatibly (fields, trim rule, join rules) | client: exact membership |
| `inventorySha256` | any key, take or spoken text changes (glossary wording, TSV value, GPS form) | client and build: must equal the compiled value (AC 5, AC 20, AC 46) |
| `packVersion` | the audio of a delivered pack changes for the same voice (vendor re-synthesis after a cache clear, processing, bitrate, pauses, take method) | people: delivery and listening records cite (`voiceId`, `packVersion`, manifest SHA-256) |
| `glossaryRevision` | any byte of the two inventory input files changes | nobody (trace) |

- A pack is accepted by an app build if and only if V1–V10 pass. `packVersion` is not compared, so any listened pack with the right inventory can be bundled.
- Every pack given to anyone is identified by (`voiceId`, `packVersion`, SHA-256 of `manifest.json`). That triple, never the audio, is what goes into QA reports and the NAV-007 sign-off record.

## 10. Example (excerpt; real packs have 304 entries)
```json
{
  "schemaVersion": 1,
  "packVersion": 1,
  "voiceId": "FEMALE5v2",
  "source": { "vendor": "chimege", "apiVersion": "v1.2", "voice": "FEMALE5v2" },
  "sourceSampleRateHz": 22050,
  "codec": {
    "name": "aac-lc",
    "container": "adts",
    "fileExtension": ".aac",
    "sampleRateHz": 22050,
    "channels": 1,
    "bitrateKbps": 24,
    "encoderDelayFrames": 1024,
    "encoder": "ffmpeg-7.0.2-aac"
  },
  "takeMethod": "carrier-then-v1",
  "joinPauseMs": 80,
  "thenPauseMs": 80,
  "processing": { "silenceThresholdDbfs": -45, "edgeMarginMs": 5, "fadeMs": 10, "targetRmsDbfs": -20, "peakCeilingDbfs": -1 },
  "glossaryRevision": "0000000000000000000000000000000000000000000000000000000000000000",
  "inventorySha256": "681d5e39b8cd21d0b97104e3c4b0f5b417388885497c2ca429a42c3edf2b8bc5",
  "entries": [
    { "key": "dist.m.300", "take": "final", "spokenText": "гурван зуун метрт", "file": "clips/dist.m.300.final.aac", "frames": 19845, "encodedFrames": 21504, "durationMs": 900, "sha256": "1111111111111111111111111111111111111111111111111111111111111111" },
    { "key": "instr.turn.right", "take": "nonFinal", "spokenText": "баруун тийш эргэнэ үү", "file": "clips/instr.turn.right.nonFinal.aac", "frames": 26460, "encodedFrames": 27648, "durationMs": 1200, "sha256": "2222222222222222222222222222222222222222222222222222222222222222" }
  ]
}
```
The hashes and frame counts above are placeholders. This excerpt validates against the schema.
