# ADR-0010: Mongolian voice prompts are played from a pre-generated segment clip pack, not from device TTS

- **Status:** **accepted** (2026-10-13, inside the NAV-016 feature lane, as provided by [D228](../../requirements/decisions.md)).
  - *Basis:* D76 (the clip pack is the expected fallback), D80 (pack before any device `mn` voice), D226/D227 (Chimege, `FEMALE5v2` and `MALE4v2`), D228 (build now; the NAV-007 AC 9 start gate is waived for building), D229–D233 (one bundled voice, the PO's per-segment listen, the «жи пи эс» spoken form, the D95 preconditions met, P1).
  - *Still gated, not by this ADR:* public release. NAV-007 AC 9, AC 10 and AC 11 must run on the pack, Chimege's written output terms must be recorded, and the PO's per-segment listen must be done (D228, D230, NAV-016 AC 53). AC 9 still confirms D76. If it finds a usable device `mn` voice, nothing changes, because D80 plays the pack first.
- **Date:** 2026-10-01 (proposed); 2026-10-13 (accepted, revised for NAV-016)
- **Stories:** NAV-016 (implementation), NAV-005 (AC 32–40, behaviour changes listed in NAV-016), NAV-007 (AC 9–13), NAV-017 (web text port of D95/D101), NAV-015 (iOS, later), a later web clip story.
- **Evidence:** [spike `mongolian-voice-tts`](../spikes/mongolian-voice-tts.md); the F4 pre-screen (D226); the codec measurement of 2026-10-13 (§3.3).
- **Contract:** [`../voice-pack/pack-format.md`](../voice-pack/pack-format.md), [`../voice-pack/manifest.schema.json`](../voice-pack/manifest.schema.json), [`../voice-pack/inventory.tsv`](../voice-pack/inventory.tsv). **`openapi.yaml` is unchanged.**
- **Task breakdown:** [`../tasks/NAV-016-mongolian-voice-clip-pack.md`](../tasks/NAV-016-mongolian-voice-clip-pack.md).

## Revision history
| Date | Change |
|---|---|
| 2026-10-01 | Proposed. Revised after the spike's skeptic review (Gemini-TTS, eSpeak NG, non-final takes, D80), then for F3 (D92–D100: distance keys are the companion TSV's keys, D94 continue-on as whole-phrase segments) |
| 2026-10-13 | **Accepted** for D226–D233. Vendor Chimege, `FEMALE5v2` bundled. Final key names (`tail.approach` replaces the illustrative `tail.arrive`; `instr.straight` for `continue`; `fixed.*`). `nonFinal` takes in pack v1, made by the `carrier-then-v1` method. Two pause constants. Codec AAC-LC in raw ADTS with a manifest trim rule. Decode-to-PCM playback through one `AudioTrack`. Validation at preview open. Custody of the pack and cache. Our own summary of the Chimege API. AC 10 harness. The contract files moved to `docs/architecture/voice-pack/` |
| 2026-10-13 (integration review) | Decisions on the engineers' open items, no format change (`schemaVersion` stays 1): Chimege `Error-Code` meanings (§4.1); cache key `takeMethod` = `none` for `final` takes (§4.2); unmapped prompts play the chime on every platform, the web included, and never speak the display text (§2); the stub-model duration check only reports, the check on a real pack is enforced (§7); the device onset check uses the edge-margin/fade window (§7); debug builds downgrade only the compiled-inventory comparison of V7 (§5). Precedence of V2/V3/V4/V8 and the generator-only `UNLISTED_FILE` check are now spelled out in pack-format §8 |

## Context
- **No device voice.** No first-party engine ships a Mongolian voice. eSpeak NG has a GPL-3.0, formant-quality one that users may install themselves (spike §2). The web demo has only Edge desktop's online voices, which are excluded by D78.
- **The prompt set is closed.** Instruction parts, C4 ordinals, the enumerated spoken distance table (glossary C3a, D99), A11–A13 and fixed phrases. No street names (C5).
- **Voice source decided.** Chimege (Ulaanbaatar), voices `FEMALE5v2` and `MALE4v2` (D226, D227). The F4 pre-screen synthesised whole prompts only (88/88 OK, 22,050 Hz WAV). Joins and non-final takes are first heard with this pack.
- **Constraints:**
  - nothing route-derived leaves the device
  - offline and tunnels (a prompt starts ≤ 1 s after its trigger and is dropped after 3 s)
  - phones without Google services (D58)
  - public repository (D35): no token, no vendor documents (D46), no clips (D79)
  - glossary-exact wording (NAV-007 AC 12–13)
  - APK growth ≤ 2.0 MB for one voice (NAV-016 AC 51)
  - minSdk 26

## Decision

### 1. Fallback order (in force from NAV-016)
**Mongolian:** clip pack → chime + A1 notice (no pack, invalid pack, or a playback error; the rest of the session). **Device `mn` TTS is never used for Mongolian** once NAV-016 ships (D80). This amends ADR-0009 §3.4 for `mn`. **English** keeps device TTS with the chime fallback (D96). The English generator output and rounding are unchanged.

### 2. The voice text generator returns display text, spoken text and segments
One pure function per platform (Kotlin `VoiceText`; TypeScript `voiceText.ts`; Swift later) returns:
- **display text:** digits. It feeds the golden set (NAV-005 AC 40).
- **spoken text:** the C3a spoken forms, with "GPS" as «жи пи эс» (D93, D231).
- **segments:** an ordered list of (key, take). Empty in English.

The Mongolian rounding changes in the same change (D95, D232):
- prefixes: D92 (1 / 1,5 / whole km from 1,750 m)
- continue-on: D94 (whole km below 99,500 m, then nearest 10 km, up to 990 km)
- D102: no Mongolian continue-on prompt at 995 km or more

**Segment keys (binding; the inventory is in [`inventory.tsv`](../voice-pack/inventory.tsv) plus the TSV):**
| Generator input | Key |
|---|---|
| distance prefix / approaching metres / continue-on value | the `key` of [`glossary-c3-spoken.tsv`](../../requirements/glossary-c3-spoken.tsv): `dist.m.N`, `dist.km.1`, `dist.km.1_5`, `dist.km.2`, `cont.km.N`, `dist.lt10` |
| `ManeuverKey` with id `X` (ADR-0008), except the two rows below | `instr.X`, for example `instr.turn.slightRight`, `instr.keep.left`, `instr.onRamp.right`, `instr.depart.nw`, `instr.arrive.left` |
| `ManeuverKey.CONTINUE` (`continue`, also fork `straight`) | `instr.straight` |
| `ROUNDABOUT_EXIT` with n in 1..10 | `instr.roundabout.exit.N`. A whole phrase per exit, so there is no join inside the ordinal phrase. Exit > 10 or missing gives `instr.roundabout.enter`, as the text does today |
| A11 tail, A12 tail, A13 «дараа нь» | `tail.approach`, `tail.continue`, `join.then` |
| off-route, GPS lost, GPS restored | `fixed.offRoute`, `fixed.gpsLost`, `fixed.gpsRestored` |

**Takes:** `nonFinal` exists for every instruction key that can stand as `{first}` before «дараа нь». That is 39 keys: turns, U-turn, straight, keep, merge, ramps, the 12 roundabout keys and the 8 depart keys. `instr.arrive*`, the tails, `join.then`, `fixed.*` and the distances have `final` only. **A `{first}` instruction always uses its `nonFinal` take, and every other position uses `final`.** That gives 304 entries per voice. The prompt shapes are:
- `[prefix] instr`
- `[prefix] instr(nonFinal) join.then instr`
- `dist.m.N tail.approach`
- `cont.km.N tail.continue`
- `fixed.*`

A prompt has at most 4 segments. A JVM domain test proves that every output maps to an inventory entry (NAV-016 AC 27–28). At runtime, an output that does not map plays the chime and never a partial prompt.
- **Unmapped prompts (decided 2026-10-13, integration review):** the AC 27 domain stops at 2,000 m, so some runtime outputs can still fall outside the inventory. Known case: walking catch-up, restore or call catch-up prompts for a manoeuvre ≥ 2,500 m ahead. These round to «3 километрт» or more, and there is no `dist.km.3`+ key. The rule is the same on every platform: **that one prompt plays the chime**. It is not a session fallback, so there is no A1 notice and later prompts still play from the pack. The banner keeps its display text.
  - **Android:** already does this (`VoiceOutput`, "a segment has no entry").
  - **Web (`voiceText.ts` + the demo player):** a Mongolian prompt with any segment missing from the inventory must never be spoken. The current fallback to the lower-cased display text speaks digits and breaks AC 25. The web plays its navigation-ux §4.8 chime for that prompt. If no chime path can play, the prompt is skipped. English is unchanged.
  - Bounded `dist.km.N` rows above 2 km, plus extending the AC 27 domain to cover those catch-up distances, are a later BA follow-up through triage. Until then the chime is the accepted interim behaviour.

### 3. Pack format (contract: [`pack-format.md`](../voice-pack/pack-format.md), [`manifest.schema.json`](../voice-pack/manifest.schema.json))
1. **One pack per voice:** a directory with `manifest.json` and `clips/<key>.<take>.aac`. The manifest fields are those of NAV-016 AC 18, plus four more. Two are needed to decode exactly: `codec` and the per-entry `frames` / `encodedFrames`. Two are needed to reproduce the pack: `processing` and `codec.encoder`. There are no timestamps or hosts, and the bytes are deterministic (pack-format §3).
2. **Versioning.**
   - `schemaVersion` (Android supports exactly 1) is the format.
   - `inventorySha256` (canonical serialisation, pack-format §2.3) binds a pack to an app build's wording. Build and app both reject a mismatch.
   - `packVersion` is a human delivery identifier and is never compared.
   - `glossaryRevision` is a trace.
   - A pack is identified in records by (`voiceId`, `packVersion`, manifest SHA-256).
3. **Codec: AAC-LC, mono, 22,050 Hz, 24 kbit/s, in a raw ADTS stream (`.aac`), with a manifest trim rule** (pack-format §4–§5).
   - *Decodable everywhere:* AAC-LC in ADTS is decoded by the Android framework (`MediaExtractor`/`MediaCodec`, every API level ≥ 26), by iOS (`AVAudioFile`/`AudioFile`, ADTS type) and by Safari, Chrome and Edge (`decodeAudioData`, `audio/aac`).
   - *Why ADTS and not `.m4a`:* measured on 2026-10-13 with ffmpeg 7.0.2's `aac` encoder. The MP4 muxer writes an edit list with media time 1,024 and a segment duration in **milliseconds** (movie timescale 1000). Even ffmpeg's own decoder then trims the start but leaves 343–695 frames (16–32 ms) of end padding. How platform decoders apply edit lists varies, which is exactly the AC 15 risk. ADTS carries no edit list, so **no platform trims anything**. The client applies one rule from the manifest (`encoderDelayFrames` = 1,024, `frames`, `encodedFrames`) and gets exactly `frames` samples, whichever way the decoder treats the priming frame. ADTS also saves about 740 bytes of container per clip, about 0.22 MB per pack.
   - *Why 24 kbit/s:* the APK budget. We estimate 304 clips × 1.68 s on average, about 510 s of audio (6,908 characters at about 14 characters/s, cross-checked against the pre-screen's 15.4 characters/s chained prompt). That is about 1.6–1.85 MB at 24 kbit/s, about 2.1 MB at 28 and about 2.4 MB at 32, which would break AC 51. The bitrate is a generator option recorded in the manifest. The PO's per-segment listen judges quality. If 24 kbit/s is audibly poor, the escape hatch is `codec.sampleRateHz` 16,000 at 24–28 kbit/s: the format already allows it, so no schema change is needed.
   - Determinism: the ffmpeg aac encoder with bit-exact flags gave byte-identical output on repeated runs (measured). Byte identity across ffmpeg versions is not assumed, so the generator image pins one version.
4. **Spoken text source:** distances are copied from TSV `spoken_mn`, and everything else is the lower-cased glossary or resource wording, with "GPS" → «жи пи эс». The generator never spells a number.

### 4. Generation (build time only; `backend/tools/voice-pack/`, owner backend-engineer)
#### 4.1 Chimege API (our own summary of the vendor documents read on 2026-10-13; the documents themselves are not committed, D46)
- **Synthesis:** `POST https://api.chimege.com/v1.2/synthesize`.
  - The body is the text as UTF-8 plain text. The vendor samples label it with a non-standard plain-text content type. The generator sends the label the F4 pre-screen used (the orchestrator confirms which, see the handoff). The fake server accepts both.
  - Headers:
    - `token`: the API token
    - `voice-id`: one of 11 voices; we use `FEMALE5v2` or `MALE4v2`
    - `speed`: 0.2–4, default 1; we send 1
    - `pitch`: 0.2–6, default 1; we send 1
    - `sample-rate`: 8,000, 16,000 or 22,050, default 22,050; we send 22,050
  - Success: HTTP 200 with a WAV body (16-bit PCM, mono, 22,050 Hz in the pre-screen).
- **Errors:**
  - HTTP 400 with an `Error-Code` header. Our summary of each code, which the generator prints after the key (B3):

    | `Error-Code` | Meaning (our wording) | Can our inventory cause it? |
    |---|---|---|
    | 4000 | the request body was not received | no (a client or transport bug) |
    | 4001 | the text after vendor normalisation is shorter than 2 characters | no (pack-format §2.2: ≥ 2 characters) |
    | 4002 | the text after vendor normalisation is longer than 300 characters | no (≤ 300 characters including the carrier) |
    | 4003 | the `voice-id` is not a valid voice | only with a wrong `--voice` |
    | 4004 | the `speed` is invalid (allowed 0.2–4) | no (we send 1) |
    | 4005 | the text contains special characters the vendor does not allow | no (charset rule; only the carrier comma) |
    | 4006 | the text contains a series of upper-case letters | no (lower case only) |
    | 4007 | the `sample-rate` is invalid | no (we send 22,050) |
    | 4008 | the `pitch` is invalid (allowed 0.2–6) | no (we send 1) |

    Any other 400 code, or none, is reported as "unknown Error-Code" with the raw code. All of them fail the run at once (no retry).
  - HTTP 403 with an `Error-Code` header. The token was rejected, and the run aborts:

    | `Error-Code` | Meaning (our wording) |
    |---|---|
    | 1000 | the token is invalid |
    | 1001 | the token is missing |
    | 1002 | the token is not active |
    | 1003 | the token is suspended |

  - 500, and 503 when the service is overloaded.
- **Allowed characters:** Cyrillic letters, space and the punctuation `? ! . - ' " : ,`. Digits and abbreviations are expanded by the vendor's normaliser.
  - A separate `POST …/normalize-text` endpoint returns the normalised text. Our inventory never needs it, because we send spoken forms. The generator may offer it as an optional diagnostic that flags entries whose normalised text differs from what we sent. It is off by default and outside the cache key.
- **Rate limits:** none published. The pre-screen ran 88 requests sequentially without an error. We cap concurrency at 2.

#### 4.2 Generator behaviour
- **Inputs:** the two inventory files (pack-format §1.1), `--voice`, `--pack-version`, `--synth chimege|stub`, `--out`, `--cache`, `--bitrate-kbps` (24), `--join-pause-ms` / `--then-pause-ms` (80 / 80), and `--take-method` (`carrier-then-v1`).
- **Pre-flight, with 0 requests on failure** (AC 7, AC 8):
  - text rules (pack-format §2.2)
  - the token is present in `CHIMEGE_API_TOKEN` (real synthesiser only)
  - the output and cache paths are outside the repository or git-ignored
  - ffmpeg is the pinned version
- **Requests:** one per uncached entry, at most 2 in flight, a 30 s timeout each.
  - **Retry only** on 429, 500, 502, 503, 504, a timeout or a connection error. That is 1 attempt plus at most 3 retries, after 1 s, 2 s and 4 s, or after `Retry-After` when present (capped at 60 s).
  - Any other 4xx fails the run at once and names the key and the `Error-Code` meaning.
  - A 403 aborts the whole run, with "CHIMEGE_API_TOKEN rejected (code 100x)" and never the value.
  - A 200 response whose body is not mono 16-bit PCM WAV at the requested rate fails the run, with no retry.
- **Token hygiene:** the token is read only from the environment and sent only in the `token` header. The API base can be overridden only to a loopback host (for the fake server). The token never appears in a URL, a log line, an exception text, the cache, the manifest or the review files. A redaction filter on every output stream is the second line of defence (AC 9 sentinel test).
- **Cache** (AC 10): raw vendor WAVs, keyed by the SHA-256 of a canonical JSON of {vendor, apiVersion, voice, sampleRateHz, speed, pitch, takeMethod, requestText}. Written atomically, with an index file that holds the key fields and no token. Cleared only by hand.
  - In the cache key, `takeMethod` is the method id (for example `carrier-then-v1`) for a `nonFinal` take, and the literal `none` for a `final` take. A `final` request is the bare `spokenText`, so the method cannot change its audio. Switching the method (for example `comma-v1` on the review page) then re-requests only the `nonFinal` takes. The manifest's `takeMethod` field is unaffected: it always names the method of the run.
- **Output** (AC 11, AC 13, AC 17): everything is written to a temporary sibling directory, verified, and then renamed into place. On any failure no pack and no manifest appear, and an earlier output stays untouched.

#### 4.3 `nonFinal` take method: `carrier-then-v1` (AC 12)
1. **Request:** `<spokenText>, дараа нь`. The vendor then sees the sentence continue, so `<spokenText>` gets continuing intonation. The comma is allowed by the vendor.
2. **Cut**, on the raw WAV in 10 ms windows at −45 dBFS:
   - find the end of speech `E`
   - take the **last** silence run of ≥ 60 ms that ends at least 250 ms before `E`. «дараа нь» has no internal pause of that length, so this is the comma pause
   - cut at the start of that run
3. **Self-check, otherwise the run fails with the key named (`nonFinal-boundary`):**
   - the removed tail's active duration is 0.5–1.6 × the active duration of the `join.then` clip
   - the kept part is no longer than the `final` take + 300 ms
4. Then the normal processing (pack-format §4) runs. The `nonFinal` file must differ (SHA-256) from the `final` file.

- *Alternatives:* `comma-v1` (`<spokenText>,` with no cut) may be offered for a side-by-side comparison on the review page only. If the vendor's normaliser drops a trailing comma, it would produce the same audio as `final`, and the SHA-256 check exposes that. A delivered pack always uses `carrier-then-v1` unless this ADR is revised.
- *`join.then`* is synthesised standalone («дараа нь»). It cannot be cut from a carrier, because there is no pause after it. If the listen or AC 10 flags it, that is a revisit trigger.

#### 4.4 Stub synthesiser (AC 16)
- Same interface as the real one (text in, WAV out), with 0 network access.
- Each comma-separated phrase becomes a tone of 70 ms per character. The frequency is derived from the SHA-256 of the phrase, the amplitude envelope is speech-like, and the peak is −12 dBFS.
- 100 ms of silence at each end, and 250 ms of silence at each comma, so the `carrier-then-v1` cut is exercised.
- `source.vendor` = `stub`. CI and every automated test use only the stub or the fake server.

#### 4.5 Custody of packs, cache and token (design question 4)
- **Where it runs:** a real pack is generated only on a machine where the PO has put `CHIMEGE_API_TOKEN` into that shell's environment. That is the PO's own machine, or an agent session on a machine where the PO set it for the session. The token is **never** in a file in the repository, in `.env` files that agents commit, in CI secrets or in the Android build.
  - CI never calls Chimege. On a public repository, CI artifacts would publish the clips.
- **Canonical copies:** the generator cache (the vendor audio) and each delivered pack directory live outside the repository. The PO keeps them on that machine plus one PO-controlled private backup; the PO chooses the medium.
  - The cache is the valuable part. A retrained vendor voice would give different audio after a cache clear, and that needs a new `packVersion` and a new listen.
- **Delivery:** to the Android build via `-Pnav.voicePackDir=<…>/pack` (D182 pattern). APKs go to the PO only, by direct file transfer (D180, D228), until the public-release gates are met. The (`voiceId`, `packVersion`, manifest SHA-256) of every delivered pack is written in the QA report. That triple is not secret.

### 5. Distribution
- **NAV-016:** one pack, `FEMALE5v2` (D229), is bundled in the Android APK from `nav.voicePackDir` into the `voicepack/` assets.
  - `MALE4v2` packs are built with the same command, for internal builds only.
  - There is no voice setting (D229).
  - Bundling rules per variant (NAV-016 AC 50):
    - `debug`: optional; without a valid pack it plays the fallback. *Decided 2026-10-13:* when the only failure is that the manifest's `inventorySha256` differs from the compiled value (a pack built from older wording), the `debug` build warns and still bundles the pack. The app then rejects it at runtime with `INVENTORY_MISMATCH` and plays the fallback. This follows AC 46, which overrides AC 20 for `debug` on exactly this point. Every other V7 failure (entries unsorted or duplicated, or entries that do not hash to the manifest's own `inventorySha256`) means a corrupt manifest. Like every other check, it fails the `debug` build too (pack-format §8)
    - `demo`: a real pack is required, unless `none` is set explicitly
    - `release`: a real pack is required, with no `none` option
- **Repository:** no clips, no pack manifests and no review files (D79, AC 19).
- **Later:** iOS (NAV-015) and a web clip story reuse this format unchanged. Downloadable packs would need a gateway path and an `openapi.yaml` entry through a new revision of this ADR.

### 6. Android playback (NAV-016 section F; owner mobile-engineer)
- **Decode path: framework `MediaExtractor` + `MediaCodec` → PCM → the trim rule (pack-format §5) → one PCM buffer per prompt (clips plus pauses plus the existing Bluetooth lead-in) → one `AudioTrack` in `MODE_STATIC`.** This is the same output path the chime already uses.
  - It is gapless and exact by construction: the joins are sample-counted, which gives AC 33's ±20 ms.
  - It adds no dependency (ADR-0018 dependency verification is untouched).
  - "Never a partial prompt" (AC 36) follows directly: every segment is decoded before playback starts, and any failure plays the chime and switches the session to the fallback (AC 43).
- **Threading and latency:**
  - decoding runs on one background thread
  - an LRU cache of decoded clips is capped at about 2 MB of PCM
  - a prompt of 4 cold segments decodes in roughly 50–200 ms on a budget phone (estimate, to be measured), well inside the 1 s start (AC 31) and the 3 s drop
- **Audio focus:** transient with ducking, requested **once per prompt** before playback and released ≤ 1 s after the last frame. If focus is denied, the whole prompt is skipped (AC 34). `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and `CONTENT_TYPE_SPEECH`. All existing NAV-005/NAV-012 rules apply unchanged: one at a time, the 3 s drop, mute, off-route, arrival, language switch, «Дуусгах», phone calls and output changes.
- **Validation:** at route-preview open (`GuidanceVoice.prepare()`), on a background thread, with checks V1–V10 (pack-format §8). It takes ≤ 1 s on the reference device. The result is held for the process and redone after process death (AC 39, AC 40).
  - The existing "decision pending → wait up to 2 s at «Эхлэх», then chime" rule now covers pack validation.
- **Rejected for Android:**
  - Media3/ExoPlayer: it would add a dependency and its own focus handling, and pauses need extra silence sources.
  - `MediaPlayer` per segment or `SoundPool`: neither gives sample-accurate joins, and both add start latency per segment.

### 7. Verification moves left; NAV-007 AC 10 harness (design question 6)
- **JVM:** the domain test (every output maps to the inventory; no orphans), the spoken-text scans, and the duration check (Σ `durationMs` + pauses, NAV-007 AC 11) over the whole domain. CI uses a Kotlin stub manifest built at 70 ms per character, and a real pack when `nav.voicePackDir` is set.
  - **Duration enforcement (decided 2026-10-13):** with the stub manifest the check **reports only**: it writes `longest-prompts.tsv` and prints the excess. 70 ms per character is a synthetic model, not speech, and AC 29's limits are about spoken audio. The check **fails** whenever the manifest is a real pack (`source.vendor` ≠ `stub`), in any variant, and always in `demo` and `release` builds (`checkVoicePackDurations` before `preDemoBuild` / `preReleaseBuild`). The story wording of AC 29 ("the stub pack in CI") should follow this; that request goes to the BA.
  - *Risk (measured on the stub model, 2026-10-13):* the longest chained prompt, `dist.m.650 instr.roundabout.exit.6:nonFinal join.then instr.roundabout.exit.6`, comes to 8,540 ms against the 8,000 ms limit. The longest single prompt is 4,790 ms against 5,000 ms. The pre-screen's 15.4 characters/s gives about 7.9 s for the same chained prompt; the §3.3 estimate of 14 characters/s gives about 8.7 s (about 118 characters of speech plus 240 ms of pauses). So the first real pack may fail the `demo` build on chained roundabout prompts. What to do then is a product question (open question in the review handoff), not a format change.
- **Generator:** text rules, processing checks, a decode check with the pinned ffmpeg, and onset alignment after the trim rule within ±2 frames, measured by cross-correlation with the pre-encode PCM. This is the authoritative onset check.
- **Device:** one instrumented decode test applies the trim rule on Android's real `MediaCodec` to a stub pack (AC 15). It runs on an emulator or a device; there is no device in the agent environment.
  - It asserts the decoder's output length shape (all access units, or the first one dropped, ±2 frames) and that the trimmed length is exactly `frames` × k.
  - **Onset (decided 2026-10-13):** the device has no pre-encode PCM, so it does not repeat the ±2-frame correlation. It checks that the first sample above −35 dBFS falls within [`edgeMarginMs` − 2 frames, `edgeMarginMs` + `fadeMs`] of the trimmed clip. That catches the failures that matter on a device: a shift by one access unit (1,024 frames, about 46 ms) or a platform skip-cut. The schema does not change.
  - The onset window gates **on the stub pack** (QA interop step), which has a known hard onset. On a real pack, a failure that is only the onset window gets reported to the architect and does not block by itself. Soft speech onsets and gain can legitimately move the −35 dBFS crossing. The length assertions gate on every pack.
- **AC 10 harness:** `voicepack render --pack <dir>/pack --prompts <list.tsv> --out <dir>`.
  - It renders each prompt of a prompt list (pack-format §7) to WAV with the same trim and join rules and the manifest's pauses.
  - The Android duration check writes its 10 longest single and 10 longest chained prompts in that list format. The review page renders those, plus 20 fixed coverage prompts.
  - NAV-007 picks and orders the session prompts (QA-owned). It does not re-implement joins.
  - On-device sessions use the internal APK with the pack (AC 56).

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| Device TTS only | No work | No `mn` voice on stock engines; chime for almost all users |
| Runtime cloud TTS through the gateway | Any text (street names later) | Network while driving, an offline fallback still needed, a contract change, per-use cost, route-derived text leaves the device |
| On-device open model | Offline, any text | MMS is non-commercial; Piper/eSpeak NG are GPL-3.0 or have no voice; our own model is an ML project; tens of MB |
| Full-utterance clips | Best prosody | Thousands of prompts (continue-on alone has 188 values) |
| "Continue on" composed from numeral clips | ~0.4 MB smaller | Joins inside numeral phrases; contradicts C3a |
| **Segment clip pack (chosen)** | Every phone, offline, no runtime data flow, JVM-testable, near-zero cost | Join prosody must pass AC 10; regenerate and re-listen on every wording change; vendor terms needed |
| *Codec:* AAC-LC in `.m4a` | Common; edit lists carry gapless information | Edit-list handling differs per platform; ms-precision segment duration; ffmpeg's own decode leaves 16–32 ms end padding (measured); +0.22 MB container |
| *Codec:* WAV / FLAC (lossless) | Exact length, trivial decode | About 22 MB / about 10 MB per voice; breaks AC 51 |
| *Codec:* Opus (Ogg) | Best quality per bit; pre-skip handled in the decoder | No native Ogg decode in iOS `AVFoundation`; Safari support only recent; breaks "one format everywhere" |
| *Codec:* MP3 | Universal | Worse quality at 24 kbit/s; the same priming problem with less reliable metadata |
| *Take method:* `final` only | Half the work | Risks the "two sentences" effect before «дараа нь» (spike §3.2); the PO kept `nonFinal` in v1 (inventory) |
| *Take method:* trailing comma, no cut | Simple | Unknown whether the vendor keeps a trailing comma's prosody; kept only as a review comparison |
| *Android player:* Media3 / `MediaPlayer` / `SoundPool` | Less code (Media3) | See §6 |

## Consequences
- **Easier:**
  - the same voice on every Android phone, offline, with no runtime traffic
  - deterministic JVM tests, including duration
  - the same pack for iOS and the web later
  - one trim rule on all platforms
- **Harder:**
  - every wording change regenerates and re-listens the pack. `inventorySha256` makes stale packs fail loudly. NAV-007 panel revisions should be batched
  - the generator depends on a pinned ffmpeg image. ffmpeg is LGPL/GPL depending on the build and is used only as a build tool. It is not distributed with the app, and the image is never published (licence flag noted, no app impact)
  - the Android playback path adds a decoder and an assembler, plus one instrumented test that needs an emulator or device
- **Unchanged:** the banner and notification text, the voice schedule (apart from the D102 suppression), the English voice path, and `openapi.yaml`.
- **Follow-ups:** F4 is done (D226/D227; the written terms are still outstanding and gate public release). F6 is done (this acceptance). F7 (street names) stays later. The web clip story and NAV-015 reuse this format.
- **Revisit triggers:**
  - the per-segment listen or NAV-007 AC 10 flags joins, the `nonFinal` cut, the standalone «дараа нь» or the 24 kbit/s quality
  - Chimege's written terms forbid bundling
  - spoken street names are requested
  - a first-party engine ships a usable `mn` voice on most phones sold in Mongolia
  - AC 51 is exceeded at 24 kbit/s (switch to 16 kHz)
