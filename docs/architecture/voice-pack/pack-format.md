# Voice clip pack format v1 (contract)

- **Owner:** architect. **Date:** 2026-10-13. **Stories:** NAV-016 (Android, generator); later NAV-015 (iOS) and a web clip story.
- **Decision record:** [ADR-0010](../adr/0010-mongolian-voice-pregenerated-clip-pack.md) (accepted 2026-10-13).
- **Files in this directory (all contract, all architect-owned):**
  - `manifest.schema.json`: JSON Schema (draft 2020-12) of `manifest.json`, `schemaVersion` 1
  - `inventory.tsv`: the 48 non-distance segment keys (the 217 distance keys come from the BA-owned [`glossary-c3-spoken.tsv`](../../requirements/glossary-c3-spoken.tsv))
  - this file: the rules JSON Schema cannot express
- **Who implements what:** the generator (`backend/tools/voice-pack/`, backend-engineer) **writes** packs that satisfy this file; the Android app and its Gradle build (mobile-engineer) **read and verify** them. Neither side changes this file on its own (CLAUDE.md rule 2, applied to this contract). A change goes through the architect, and a breaking change bumps `schemaVersion`.
- **Not in `openapi.yaml`.** Packs are bundled at build time. No HTTP operation serves them (ADR-0010 §5), so `openapi.yaml` is unchanged.

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

*Value at the time of writing* (the inputs at commit `372e26e` plus this directory's `inventory.tsv`): `681d5e39b8cd21d0b97104e3c4b0f5b417388885497c2ca429a42c3edf2b8bc5`. Tests should compute it rather than hard-code it.

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
- **Codec:** AAC-LC, mono, raw **ADTS** stream (`.aac`) at `codec.sampleRateHz` (default 22,050), target `codec.bitrateKbps` (default **24**). It is encoded by the ffmpeg version pinned in the generator image, with bit-exact flags and no metadata. The encoder's priming is `codec.encoderDelayFrames` (1,024 for the ffmpeg `aac` encoder, measured 2026-10-13: ADTS output has exactly `ceil((frames + 1024) / 1024)` access units, and the decoded onset is shifted by 1,024 frames).
- **Processing order:** raw vendor WAV → (nonFinal only: cut, ADR-0010 §4.3) → trim → fades → gain → checks → encode. The parameters are recorded in `processing`:
  - **Trim:** the speech region runs from the first to the last 10 ms window whose RMS is above `silenceThresholdDbfs` (−45). Keep `edgeMarginMs` (5) of the original signal before and after it.
  - **Fades:** linear `fadeMs` (10) fade-in at the first sample and fade-out at the last sample.
  - **Gain:** one gain per clip, so that the active-speech RMS (the RMS over the 10 ms windows above the threshold) reaches `targetRmsDbfs` (−20). The gain is lowered if the peak would exceed `peakCeilingDbfs` (−1). No limiter and no dither: plain round-half-even to 16-bit.
- **Checks, each failing the run with the key named:**
  - mono
  - leading and trailing silence ≤ 40 ms
  - peak ≤ −1 dBFS
  - active RMS within ±2 dB of the pack median
  - 150 ms ≤ duration ≤ 4,000 ms
  - no internal run of windows below the threshold longer than 500 ms
  - decode check (§5) passes with the pinned ffmpeg decoder
  - for a `nonFinal` entry: its file `sha256` differs from the `final` file of the same key
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
| V2 | `schemaVersion` readable and in the client's supported set (Android: {1}) | `SCHEMA_UNSUPPORTED` (`MANIFEST_UNREADABLE` if not JSON) |
| V3 | the rest of the manifest parses as schema v1 (types, patterns, ranges) | `MANIFEST_UNREADABLE` |
| V4 | `codec` = AAC-LC / ADTS / mono / 16,000 or 22,050 Hz | `CODEC_UNSUPPORTED` |
| V5 | release and demo builds: `source.vendor` ≠ `stub` | `STUB_NOT_ALLOWED` |
| V6 | release and demo builds: `voiceId` ∈ {`FEMALE5v2`, `MALE4v2`} | `VOICE_NOT_ALLOWED` |
| V7 | `entries` sorted and unique; the canonical hash recomputed from `entries` = `inventorySha256` = the compiled value | `INVENTORY_MISMATCH` |
| V8 | every `file` matches the pattern (no `..`, no absolute path); `durationMs` matches `frames`; `frames` ≤ `encodedFrames` − `encoderDelayFrames` | `ENTRY_INVALID` |
| V9 | every `file` exists | `FILE_MISSING` |
| V10 | every file's SHA-256 = `sha256` | `FILE_HASH_MISMATCH` |
| (play) | trim rule failure or decoder error | `DECODE_FAILED` |
| (play) | `AudioTrack` error during a prompt | `PLAYBACK_FAILED` |

- **Build time (Gradle, NAV-016 AC 20):** V2–V10 against `nav.voicePackDir`, plus the variant rules of AC 50. The failure message names `nav.voicePackDir` and the first failed check.
- **Runtime (AC 39, AC 40):** V1–V10 at route-preview open and after a restore, with 0 network requests.

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
