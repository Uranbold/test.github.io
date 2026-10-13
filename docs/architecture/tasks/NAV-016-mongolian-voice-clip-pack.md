# NAV-016 task breakdown: Mongolian voice clip pack (generator, Android playback, D95 coordinated change)

- **Story:** [NAV-016](../../requirements/stories/NAV-016-mongolian-voice-clip-pack.md) (P1 / standard, D233; status `ready`, D228)
- **PO decisions in force:** D226/D227 (Chimege, `FEMALE5v2` + `MALE4v2`), D228 (build now; public release gated), D229 (`FEMALE5v2` only bundled, no setting), D230 (the PO listens per segment before the first AC 10 session and any public build), D231 («жи пи эс»), D232 (D95 preconditions met: sections D and I are in scope), D234 (the pasted token is kept, risk accepted; the custody rule is unchanged, ADR-0010 §4.5), D236 (two internal `demo` APKs, one per voice, no setting; ADR-0010 §5)
- **Design:** [ADR-0010](../adr/0010-mongolian-voice-pregenerated-clip-pack.md) (**accepted** 2026-10-13)
- **Contract (the interface between backend and mobile):** [`../voice-pack/pack-format.md`](../voice-pack/pack-format.md), [`../voice-pack/manifest.schema.json`](../voice-pack/manifest.schema.json), [`../voice-pack/inventory.tsv`](../voice-pack/inventory.tsv). Read them before starting. Neither side changes them; ask the architect.
- **`openapi.yaml`: untouched.** There is no runtime service, no gateway path and no client HTTP call. The generator calls Chimege at build time only.
- **Owner of this file:** architect. **Date:** 2026-10-13

## 0. Interfaces at a glance
```mermaid
flowchart LR
  TSV["docs/requirements/glossary-c3-spoken.tsv (BA)"] --> INV
  IT["docs/architecture/voice-pack/inventory.tsv (architect)"] --> INV
  INV["canonical inventory + inventorySha256<br/>(pack-format §2.3)"]
  INV --> GEN["backend/tools/voice-pack (B1–B11)"]
  INV --> GRADLE["Android Gradle: generateVoiceInventory (M1)"]
  CHIMEGE[(Chimege v1.2, build time only)] -. token from env .-> GEN
  GEN --> PACK["<out>/<voice>-v<n>/pack/ manifest.json + clips/*.aac<br/>(schema v1, private, D79)"]
  GEN --> REVIEW["review/ index.html, review.tsv, prompts/ (PO listen, D230)"]
  PACK -- "-Pnav.voicePackDir=…/pack" --> VERIFY["verifyVoicePack + copy to assets voicepack/ (M5)"]
  VERIFY --> APP["App: validator (M6) → decoder + trim (M7) → assembler + AudioTrack (M8)"]
  GRADLE --> APP
  APP -- "longest-prompts.tsv (prompt list, §7)" --> RENDER["voicepack render (B8) = AC 10 harness"]
```

| Interface | Producer → consumer | Defined in |
|---|---|---|
| Canonical inventory and `inventorySha256` | both sides compute it from the same two files | pack-format §2 |
| Pack directory and `manifest.json` | generator → Gradle and the app | pack-format §1.2, §3; schema |
| Clip codec and **trim rule** | generator → every decoder | pack-format §4, §5 |
| Join rules and duration formula | shared (app, review renderer, AC 10 harness, duration check) | pack-format §6 |
| Prompt list format (`key:take` tokens) | Android duration report → generator `render`; also the golden set's segment column | pack-format §7 |
| Validation checks and reason codes | generator `verify`, Gradle, app | pack-format §8 |
| Generator CLI and exit codes | backend → the PO and QA | B10 below |

---

## 1. Backend tasks (backend-engineer; only `backend/**`)
Layout (proposal; the names may change, the behaviour may not):
```
backend/tools/voice-pack/
  README.md                 how the PO runs it; token rules; custody (ADR-0010 §4.5); no vendor docs (D46)
  Dockerfile                reference image: pinned python 3.11 slim base by digest + pinned ffmpeg
  coverage-prompts.tsv      20 fixed coverage prompts in the prompt list format (pack-format §7)
  voicepack/__main__.py     CLI: inventory | build | verify | render
  voicepack/inventory.py    voicepack/textrules.py   voicepack/cache.py      voicepack/redact.py
  voicepack/synth/base.py   voicepack/synth/stub.py  voicepack/synth/chimege.py
  voicepack/audio.py        voicepack/codec.py       voicepack/manifest.py   voicepack/render.py   voicepack/review.py
  tests/fake_chimege.py     tests/test_*.py (unittest, like backend/pipeline/tests)
```
Python 3.11. The standard library plus `jsonschema` (already in `backend/scripts/requirements-dev.txt`) for `verify` and the tests. ffmpeg is the only external binary.

### B1. Inventory builder and text rules (AC 1–5, 7)
- Read the two input files and build entries exactly as pack-format §1.1 says. Serialise canonically (§2.3) and compute `inventorySha256` and `glossaryRevision` (§2.4).
- `python -m voicepack inventory [--out FILE]` writes the canonical bytes and prints both hashes.
- Text rules from pack-format §2.2, with the Avoid-term check following the NAV-007 AC 16 rules (the glossary "Avoid" terms and the D22 patterns). On any violation: exit 3, naming the key and the rule (`digit`, `latin`, `zero-width`, `charset`, `length`, `nfc`, `avoid`), and send 0 requests.
- **Checks:**
  - 304 entries (265 `final` + 39 `nonFinal`)
  - two runs give byte-identical output
  - the hash equals the value printed in pack-format §2.3 for the current inputs
  - one changed character in either input changes `inventorySha256`; a status-column-only TSV change does not (it changes `glossaryRevision`)
  - each rule has a failing fixture

### B2. Synthesiser interface and stub (AC 16)
- `Synthesiser.synthesize(request_text: str, voice: str, sample_rate: int) -> bytes` (WAV).
- `StubSynthesiser` as in ADR-0010 §4.4: 70 ms per character for each comma-separated phrase, a tone frequency derived from the SHA-256 of the phrase, a speech-like envelope, a −12 dBFS peak, 100 ms silence at each end and 250 ms at each comma. Deterministic. `source.vendor` = `stub`, `apiVersion` = `stub`.
- **Checks:**
  - the stub run completes with no token set
  - with the socket layer patched to raise, the stub run still passes, so it makes 0 network calls
  - the same text gives the same bytes

### B3. Chimege client (AC 6, 8, 9, 11, 54)
- Request as summarised in ADR-0010 §4.1:
  - `POST {base}/synthesize`, `base` = `https://api.chimege.com/v1.2`
  - headers `token`, `voice-id`, `speed: 1`, `pitch: 1`, `sample-rate: 22050`
  - body: the request text in UTF-8
  - content type: the value the orchestrator confirms from the F4 pre-screen (open item), kept in one constant
- `--api-base` is accepted only for a loopback host (`127.0.0.1`, `::1`, `localhost`). Anything else exits 2.
- **Fake-server marker (added 2026-10-13, pack-format §8.3, security VP-5):** whenever `--api-base` is set, the manifest gets `source.vendor` = `stub` and `source.apiVersion` = `stub`, and the cache keys and `index.jsonl` use the vendor value `fake`. Only a run without `--api-base` may write `chimege`.
- **Proxy (added 2026-10-13, ADR-0010 §4.5):** use the standard library's proxy handling (`HTTPS_PROXY`) and the default verified TLS context, which honours `SSL_CERT_FILE`. Never disable verification. Never print proxy variables; `redact.py` also removes the credential part of a proxy URL.
- Token: `os.environ["CHIMEGE_API_TOKEN"]` only. Missing or empty: exit 2 with `CHIMEGE_API_TOKEN is not set` and 0 requests. Never put it in a URL, `repr`, an exception or a log line. Every stdout/stderr/log write goes through `redact.py`, which replaces the token value.
- Retry policy (ADR-0010 §4.2):
  - **retry** on 429, 500, 502, 503, 504, a timeout (30 s) or a connection error: at most 3 retries after the first attempt, waiting 1 s, 2 s, 4 s, or `Retry-After` (seconds, capped at 60 s)
  - **fail at once** on any other 4xx, naming the key and the `Error-Code` header with its meaning (4000–4008; the meanings are in the ADR-0010 §4.1 table, any other code is reported as unknown with the raw code)
  - **403** (1000–1003) aborts the run, with "CHIMEGE_API_TOKEN rejected (code 1001: missing)" and similar messages
  - **200** must be RIFF/WAVE, PCM 16-bit, mono, 22,050 Hz; otherwise the run fails with no retry
- At most 2 requests in flight (a thread pool of 2). The only request text is the inventory text plus the take-method carrier (AC 54).
- **Checks with `tests/fake_chimege.py`** (an `http.server` on 127.0.0.1 with a scripted response per request; it records headers and bodies):
  - exactly 1 request per uncached entry
  - headers and body as specified
  - 429 → retried with the waits honoured (`Retry-After` respected; time patched); 500/503/timeout → 4 attempts in total, then exit 4 with no pack
  - 400 + `Error-Code: 4005` → 1 attempt, then exit 4 naming the key and "special characters"
  - 401 → 1 attempt; 403 + 1000 → exit 4 with the token message
  - at most 2 requests are ever open at once (measured in the fake)
  - a run against the fake server writes `source.vendor` = `stub`, and `voicepack verify --policy demo` on its pack gives `STUB_NOT_ALLOWED`
  - unit test on the cache-key function: the same entry gets different keys in a fake-server run (`fake`) and a real run (`chimege`), so a shared cache directory never serves fake audio to a real run
  - with `HTTPS_PROXY=http://user:SENTINEL-PROXY@127.0.0.1:<port>` set, 0 occurrences of `SENTINEL-PROXY` in any output (same places as the token sentinel)
  - **sentinel test (AC 9):** `CHIMEGE_API_TOKEN=SENTINEL-…` across the success, 401, 429, 500 and timeout runs; afterwards a grep of stdout, stderr, the log file, the exception texts, the manifest, the cache directory (index and file names), the review files and the pack directory finds 0 occurrences

### B4. Cache (AC 10)
- `--cache DIR` (default `$NAV_VOICE_PACK_CACHE`, else `~/.cache/navmn/voice-pack`). Refused if it is inside the repository and `git check-ignore` does not report it as ignored.
- Key: the SHA-256 of the canonical JSON {vendor, apiVersion, voice, sampleRateHz, speed, pitch, takeMethod, requestText}. Value: the raw WAV. Write to a temporary file, then rename. `index.jsonl` holds the key fields per entry and never the token.
- **Checks:**
  - a second run with an unchanged inventory sends 0 requests, and the pack is byte-identical
  - changing the spoken text of `instr.turn.right` sends exactly 2 requests (`final` and `nonFinal`); changing `dist.m.300` sends 1
  - changing the voice sends 304

### B5. Audio processing and the `nonFinal` cut (AC 12, 14)
- `audio.py`: WAV I/O, 10 ms window RMS, trim with a 5 ms margin, 10 ms linear fades, one gain per clip to −20 dBFS active RMS with a −1 dBFS peak ceiling, round-half-even to 16-bit. Checks as in pack-format §4. A failing clip fails the run (exit 5), with the key and the rule named.
- `carrier-then-v1` cut and self-check as in ADR-0010 §4.3 (rule name `nonFinal-boundary`).
- **Checks on synthetic WAV fixtures:**
  - leading and trailing silence ≤ 40 ms after processing
  - the fade has no first-sample step
  - peak ≤ −1 dBFS
  - a clip with 600 ms of internal silence fails
  - a 100 ms clip and a 4,100 ms clip fail
  - an outlier at +3 dB from the median fails
  - *added 2026-10-13 (pack-format §4 level band):* a peak-limited clip (delivered peak ≥ −1.1 dBFS, active RMS below −20 dBFS) at −2.5 dB from the median passes and is named in the run output; a peak-limited clip at −3.1 dB fails with `rms-outlier`; a clip that is **not** peak-limited (peak −3 dBFS) at −2.1 dB fails; a clip at +2.1 dB fails whether or not it is peak-limited; a near-silent clip with one full-scale click fails (its edge-silence, internal-silence or level-band check)
  - *added 2026-10-13 (pack-format §4 level reference):* a loud source (active RMS −12 dBFS, so the gain is negative) with a 60 ms soft decay that is above −45 dBFS before the gain and below it after the gain passes the 40 ms edge-silence check, because the trim uses the delivered level
  - the stub carrier is cut at the 250 ms gap
  - a carrier without a gap fails with `nonFinal-boundary`
  - every `nonFinal` SHA-256 differs from its `final`

### B6. Encoder and decode check (AC 15)
- `codec.py`: run the pinned ffmpeg: `-c:a aac -b:a {24}k -ac 1 -ar 22050 -flags +bitexact -fflags +bitexact -map_metadata -1 -f adts`.
- At start-up, **measure** the encoder delay. Encode an impulse, decode it raw and find the shift. If it is not 1,024, exit 2. Record it in `codec.encoderDelayFrames`, and write `codec.encoder` = `ffmpeg-<version>-aac`.
- For every clip:
  - decode it with ffmpeg and check that `D == encodedFrames` (ADTS: no trimming)
  - apply the trim rule (pack-format §5) and check that the length equals `frames`
  - cross-correlate with the pre-encode PCM and check that the lag is 0 ± 2 frames
- Print the total pack size. Warn above 1.9 MB, because AC 51 allows 2.0 MB of APK growth. `--bitrate-kbps` accepts 24, 28 or 32, and `--clip-rate` accepts 22050 or 16000 (ADR-0010 §3.3 escape hatch).
- **Checks:**
  - the stub pack passes all decode checks
  - two encodes of the same PCM are byte-identical
  - a deliberately wrong `encoderDelayFrames` fails the alignment check

### B7. Manifest and atomic output (AC 11, 13, 18, 19)
- Build `manifest.json` with the exact byte layout of pack-format §3. Validate it with `jsonschema` against `docs/architecture/voice-pack/manifest.schema.json`, then run checks V2–V10.
- Write everything to `<out>/.<voice>-v<n>.tmp-<random>/`, verify it, and rename it to `<out>/<voice>-v<n>/`. If the target exists, exit 6 unless `--replace` is given; with `--replace`, rename the old directory aside and delete it only after the new one is in place. On any failure the temporary directory is removed and an earlier pack is untouched.
- `--pack-version` is required. No clock, host name or user name goes into any output.
- **Checks:**
  - two stub runs give byte-identical trees (compare the SHA-256 of every file)
  - the manifest has no `T..:..` timestamp pattern, no host name (`socket.gethostname()`), no `$HOME` and no `$USER`
  - a failure injected at entry 200 leaves no pack and no manifest, and an earlier pack is unchanged
  - the schema validates every produced manifest

### B8. Review page, prompt renderer, AC 10 harness (AC 17, 21; D230)
- `render.py`: decode with the trim rule, apply the pack-format §6 join rules from the manifest, and write 16-bit WAV at the clip rate.
- `python -m voicepack render --pack DIR --prompts FILE --out DIR` is the NAV-007 AC 10 harness.
- `build` writes `review/`:
  - `index.html`: offline, no external URL. One row per entry: key, take, spoken text and `<audio src="../pack/clips/…">`. «ok» / «bad» buttons and a note field, with an export that downloads `review.tsv` through a Blob. The page is team- and PO-internal, so its labels may be English; no glossary term is needed.
  - `review.tsv`: `key	take	spoken_mn	result	note`, with `result` empty.
  - `prompts/`: renders of `coverage-prompts.tsv` (20 rows: every instruction group, every `nonFinal` family in a chain, `cont.km.2/99/100/990`, `tail.approach` at 30 and 500 m, `dist.km.1_5`, both GPS prompts, off-route, one arrival side) plus `--prompts FILE` when given (the Android `longest-prompts.tsv`, M4). Without it, the generator renders its own superset maximum (the longest `[prefix] instr` and `[prefix] instr(nonFinal) join.then instr`), labelled `superset-longest-*`.
  - `prompts.tsv`: what was rendered, with `durationMs`.
- **Checks:**
  - a rendered prompt's sample count = Σ `frames` + Σ pause frames
  - the measured silence (below −45 dBFS) at each join is ≥ the configured pause and ≤ pause + 2 × (`edgeMarginMs` + `fadeMs`)
  - `index.html` contains no `http:` or `https:` URL
  - `review.tsv` has 304 rows

### B9. `verify` (pack-format §8)
- `python -m voicepack verify DIR [--policy release|demo|debug]` runs schema validation and V1–V10 and prints the reason code of the first failure. The PO runs it before handing a pack to a build; QA runs it in the interop test (§3).
- **Checks:** each corruption of NAV-016 AC 44 (one byte changed, one file deleted, truncated manifest, changed `inventorySha256`, `schemaVersion` 99, stub under `--policy release`) gives the expected reason code.
- **Added 2026-10-13 (pack-format §8.1):** after V10, the generator-only check G1 `UNLISTED_FILE` (not a client code). Fault ownership exactly as in §8.1: a top-level non-object gives `MANIFEST_UNREADABLE`; `1.0` where the schema says integer gives V3; strings with control characters give V3 (whole-string matching, no reliance on Python's `$`). Add the §8.1 fixtures, including the two-fault case.
- **Added 2026-10-13 (pack-format §8.2, security VP-4):** no-follow file checks, the real-path check and the size caps (manifest 1 MiB, clip 256 KiB, pack 6 MiB) with `FILE_INVALID` in V9 and `MANIFEST_UNREADABLE` in V2; `build` applies them to its own output before the rename. Read at most cap + 1 bytes. **Fixtures:** `manifest.json` as a symlink → `MANIFEST_UNREADABLE`; a manifest over 1 MiB → `MANIFEST_UNREADABLE`; a listed clip that is a symlink to a file outside the pack (and one to a file inside it) → `FILE_INVALID`; `clips/` as a symlink → `FILE_INVALID`; a dangling symlink in place of a clip → `FILE_INVALID`; a clip of 262,145 bytes → `FILE_INVALID`; a pack whose clips are each under the cap but total over 6 MiB → `FILE_INVALID`; an unlisted symlink → G1 `UNLISTED_FILE`. Fixtures that need links are created by the test at run time, never committed.

### B10. CLI, Makefile, Docker, configuration, ignore rules (AC 8, 13, 19)
- `python -m voicepack build --voice FEMALE5v2|MALE4v2 --pack-version N --synth chimege|stub --out DIR [--cache DIR] [--bitrate-kbps 24] [--join-pause-ms 80] [--then-pause-ms 80] [--take-method carrier-then-v1] [--prompts FILE] [--replace]`
- **Exit codes:** 0 OK; 2 configuration (token, path, ffmpeg, api base); 3 inventory rule; 4 vendor; 5 audio check; 6 output.
- `backend/Makefile`:
  - `voice-pack-test`: the unittest suite; no network, no token
  - `voice-pack-stub OUT=…`
  - `voice-pack-build VOICE=… PACK_VERSION=… OUT=… CACHE=…`: runs the reference image, passes the token by name only (`-e CHIMEGE_API_TOKEN`), mounts the repository read-only and mounts OUT and CACHE
    - *Added 2026-10-13 (ADR-0010 §4.5):* appends `$(VOICE_PACK_DOCKER_ARGS)` from the environment, empty by default, for machines behind a TLS-intercepting proxy (for example host networking, `-e HTTPS_PROXY`, a read-only CA mount and `-e SSL_CERT_FILE=<mounted path>`). The Makefile, README and `.env.example` name the variable but contain **no** proxy host, port, CA path or credential. The README explains the case generically.
  - `voice-pack-verify DIR=…`
- `backend/.env.example`: `CHIMEGE_API_TOKEN=` (empty) with a one-line comment ("build machine only; never commit a value; see tools/voice-pack/README.md"), plus `NAV_VOICE_PACK_OUT=` and `NAV_VOICE_PACK_CACHE=`.
- `backend/.gitignore`: `tools/voice-pack/out/`, `tools/voice-pack/cache/`, `**/review/` under that tool, and `*.aac`, `*.m4a`, `*.wav` under `tools/voice-pack/`.
- The Dockerfile pins the base image by digest and pins the ffmpeg version. The README states that byte identity (AC 13) is guaranteed only within the reference image.
- **Checks:**
  - **AC 8 token scan (definition clarified 2026-10-13):** `git grep --untracked -n -E` for the variable name followed by optional spaces and `=` or `:`. For each hit, the *value* is the text after `=`/`:` and optional spaces, with **one** leading `"` or `'` removed, up to the next whitespace, quote or backtick. A hit is not a value if that text is empty, is `.` (the scan pattern quoted in docs), starts with `<` (a placeholder such as `<value>`), contains `…`, or is a shell or compose reference (`$NAME`, `${NAME}`). Every other hit fails the scan.
    - **Clarified 2026-10-13 (QA Q-2):** a hit whose variable name sits **inside a shell parameter expansion**, that is, directly after `${` (for example `${CHIMEGE_API_TOKEN:-}`, `${CHIMEGE_API_TOKEN:?not set}`, `${CHIMEGE_API_TOKEN-}`), is a **reference, not a value**. The `:` or `=` after the name there is an expansion operator, not an assignment, so the text after it is not read as a value. One sub-rule keeps a default from hiding a secret: for the default-value operators `:-`, `-`, `:=` and `=`, the word up to the closing `}` must be empty or itself a reference (`$NAME`, `${NAME}`). The general non-value forms above (a word that starts with `<` or contains `…`) also hold for that word. Any other word fails the scan: a literal default such as `${CHIMEGE_API_TOKEN:-abc…}` fails when `abc…` stands for any literal, like `abc123`. (Reworded 2026-10-13: this file writes the literal with `…`, so the line itself is clean, and the scan needs no documentation exception.) The words of `:?` (an error message) and `:+` (the alternative) are not values. A test fixture set for the scan covers these forms plus the existing ones A quoted value such as `="abc"` or `='abc'` **must** be found, and so must the YAML form `NAME: abc`
  - `make voice-pack-test` passes offline
  - the README contains no vendor document text, no token and no host other than the public API base
  - *added 2026-10-13 (ADR-0010 §4.5, security VP-8):* the README states the token rule: never pasted into a chat, an issue or a PR; agent sessions get it only from an environment the PO sets before the session starts

### B11. Duration sanity in the generator (supports AC 29, 33)
- Write the 10 longest superset prompts with durations into `review/prompts.tsv`. The Android domain check (M4) is authoritative; this is an early warning on the PO's machine.

**Backend definition of done:** B1–B10 pass `make voice-pack-test` with the stub and fake server only. One stub pack is produced and passes `voicepack verify --policy debug`. Not verified until the PO runs it: a real Chimege run (304 requests), the real pack size, and the listen.

---

## 2. Mobile tasks (mobile-engineer; only `mobile/**`, `web/**`)
**Rules that cut across all tasks:**
- **No Cyrillic string literal in `src/main/java`** (the `ResourcesTest` scan). Spoken texts reach the app only through a generated asset.
- **No "chimege" or "Chimege" anywhere in `mobile/` or `web/`** (AC 6). The validator checks only `source.vendor != "stub"`.

### M1. Inventory in the build (AC 1, 2, 5, 20)
- Gradle task `generateVoiceInventory` (the `repoRoot` pattern already used for `tokens.json`/`glossary.md`):
  - inputs `docs/requirements/glossary-c3-spoken.tsv` and `docs/architecture/voice-pack/inventory.tsv`
  - output: the generated asset `voice/inventory.tsv`, holding **exactly the canonical bytes** of pack-format §2.3
  - output: `BuildConfig.VOICE_INVENTORY_SHA256`
- `voice/pack/VoiceInventory.kt` parses the asset: `fun spoken(key: String, take: Take): String?`, `val sha256: String`.
- **JVM checks:**
  - the SHA-256 of the asset bytes = `BuildConfig.VOICE_INVENTORY_SHA256`
  - 304 entries
  - **AC 2:** each non-distance entry equals the lower-cased `mn` resource that `VoiceText` renders for that segment (roundabout from template + ordinal; GPS with «жи пи эс» from the inventory itself, so the only exception is the token "GPS" in the resource); each distance entry equals the TSV `spoken_mn`. A mismatch fails and names the key

### M2. `VoiceText`: rounding, spoken layer, segments (AC 22–26)
- New output type:
  ```kotlin
  enum class Take(val id: String) { FINAL("final"), NON_FINAL("nonFinal") }
  data class Segment(val key: String, val take: Take)
  data class VoicePrompt(val display: String, val spoken: String, val segments: List<Segment>)
  fun VoiceText.prompt(c: VoiceContent, lang: Lang, strings: Strings, inventory: VoiceInventory): VoicePrompt? // null = no prompt (D102)
  ```
- **Mongolian:**
  - display rounding: D92 for prefixes, D94 for continue-on; D67 and the metre bands are unchanged; no continue-on at ≥ 995,000 m (returns `null`)
  - segment keys per ADR-0010 §2 (`instr.` + `ManeuverKey.id`, `CONTINUE` → `instr.straight`, roundabout 1–10 → `instr.roundabout.exit.N`, otherwise `.enter`); `{first}` uses `NON_FINAL`
  - `spoken` = segment texts joined by pack-format §6 (a comma before `join.then`)
- **English:** display unchanged (D96), `spoken = display`, `segments = []`.
- The banner keeps C3 (AC 22c). Only the voice changes.
- `VoiceSchedule` skips a `null` prompt (D102), and the next manoeuvre's prompts still play. `SpokenPrompt` gains `spoken: String` and `segments: List<Segment>`.
- **JVM checks:**
  - every row of the AC 23 table, including the GPS row
  - the AC 22a boundaries 994.99 / 995 / 1,249.99 / 1,250 / 1,749.99 / 1,750 m
  - the AC 22b values 99,499 / 99,500 / 994,999 / 995,000 m
  - AC 24 join equality
  - AC 25: 0 digits and 0 Latin letters in any Mongolian spoken text, and every distance phrase is a TSV value
  - the English outputs are byte-identical to before (the existing `VoiceTextTest`)

### M3. Golden set support (AC 26, 48)
- `QaGpxReplayTest` (golden writer) emits two new columns, `spoken` and `segments` (pack-format §7 tokens, `-` for empty). QA regenerates `tests/gpx/nav005/golden/voice-golden.tsv` with `QA_UPDATE_GOLDEN=1` and reviews it.
- **Check:** the `en` rows are byte-identical in the old columns.

### M4. Domain test and duration check (AC 27–30)
- `VoiceDomainTest` enumerates exactly the AC 27 domain. It fails, naming the input, on any segment that is not in the inventory, any distance key that is not in the TSV, or any AC 24 break. AC 28: every non-distance inventory entry is produced at least once.
- `VoiceDurationCheck`:
  - manifest = the real pack when `nav.voicePackDir` is set; otherwise a Kotlin stub manifest at 70 ms per character (matches the B2 stub)
  - `promptMs` per pack-format §6; single ≤ 5,000 ms, chained ≤ 8,000 ms
  - writes `build/reports/voice/longest-prompts.tsv` (prompt list format, the 10 longest single and 10 longest chained, with `durationMs`)
  - fails `release`/`demo` builds on any excess
  - **decided 2026-10-13 (ADR-0010 §7):** with the stub manifest it only reports. It is enforced whenever the manifest is a real pack (`source.vendor` ≠ `stub`), in any variant, and in `checkVoicePackDurations` before `preDemoBuild`/`preReleaseBuild`
- **Check:** both run in `testDebugUnitTest` in ≤ 120 s together (AC 30). Prefer analytic enumeration per prefix key over 1 m stepping through the full schedule if time is tight; the domain coverage must not shrink.

### M5. Bundling in Gradle (AC 20, 45, 46, 50, 51, 52)
- Property `nav.voicePackDir` (env `NAV_VOICE_PACK_DIR`, the same `navSetting` helper as `nav.demoTilesFile`).
- Task `verifyVoicePack` runs checks V2–V10 (pack-format §8) in the build script against the directory. Then copy only `manifest.json` and the listed `clips/*` into generated assets `voicepack/`.
- Variant policy:
  - `debug`: optional
  - `demo`: required and real (`source.vendor != "stub"`, `voiceId` ∈ {`FEMALE5v2`, `MALE4v2`}), unless the value is exactly `none`
  - `release`: required and real; `none` is refused
- Every failure names `nav.voicePackDir` and the first failed check.
- **Files on disk (added 2026-10-13, pack-format §8.2, security VP-4):** resolve `nav.voicePackDir` to its real path once; check `manifest.json`, `clips/` and every listed file with `LinkOption.NOFOLLOW_LINKS`; require each listed file's real path under that root; enforce the caps (manifest 1 MiB, clip 256 KiB, pack 6 MiB). Codes `MANIFEST_UNREADABLE` (V2) and `FILE_INVALID` (V9). The copy writes regular files by content, only for listed paths, never a link or a directory tree.
- `debug` and V7 (decided 2026-10-13, pack-format §8): only a mismatch with the compiled `inventorySha256` is a warning (the pack is bundled and the app falls back). Unsorted or duplicate entries, or entries that do not hash to the manifest's own value, still fail the build.
- `BuildConfig.VOICE_PACK_POLICY` (`OPTIONAL` | `REQUIRED_REAL`).
- No download, ever.
- `.aac` stays uncompressed in the APK (aapt's default no-compress list; assert it).
- Add the local pack path pattern (for example `mobile/android/voicepack-local/`) to `.gitignore` under `mobile/`.
- **Checks:**
  - Gradle TestKit or a script test: a missing directory, a bad hash, a stub pack in `demo`, and `none` in `release` all fail with the property named
  - the same test: a clip replaced by a symlink to a file outside the pack, and an oversized clip, fail with `FILE_INVALID` and the property named; links are created at test time, never committed
  - a token scan: 0 occurrences of `CHIMEGE_API_TOKEN` in `mobile/` and in the APK (AC 52)
  - AC 51 size comparison with and without the stub pack (the real-pack figure is measured on the PO build)

### M6. Pack validator (AC 39, 40, 44, 46)
- `voice/pack/VoicePackValidator.kt` (pure):
  ```kotlin
  enum class PackReason { NO_PACK, SCHEMA_UNSUPPORTED, MANIFEST_UNREADABLE, CODEC_UNSUPPORTED, STUB_NOT_ALLOWED, VOICE_NOT_ALLOWED,
                          INVENTORY_MISMATCH, ENTRY_INVALID, FILE_MISSING, FILE_INVALID, FILE_HASH_MISMATCH, DECODE_FAILED, PLAYBACK_FAILED }
  sealed interface PackState { data object Pending : PackState; data class Valid(val pack: VoicePack) : PackState; data class Invalid(val reason: PackReason) : PackState }
  fun interface PackFiles { fun read(path: String): ByteArray? }   // AssetPackFiles("voicepack/") in the app
  object VoicePackValidator { fun validate(files: PackFiles, compiled: VoiceInventory, policy: PackPolicy): PackState }
  ```
- Uses kotlinx.serialization (already a dependency) and `MessageDigest` SHA-256. Runs on a background dispatcher, started from `GuidanceVoice.prepare()` (preview open) and again in a new process after a restore. 0 network.
- **Size caps at runtime (added 2026-10-13, pack-format §8.2):** `PackFiles` reads at most cap + 1 bytes (manifest 1 MiB → `MANIFEST_UNREADABLE`; clip 256 KiB or pack total 6 MiB → `FILE_INVALID`). The link and real-path rules are not re-checked on assets (met by construction).
- **JVM checks:** an oversized manifest, an oversized clip and an over-total pack give their codes; each AC 44 corruption → its reason code; a valid stub pack under `OPTIONAL` → `Valid`; a stub under `REQUIRED_REAL` → `STUB_NOT_ALLOWED`. Reason codes are logged only through `DebugLog` (debug builds) and never with coordinates.

### M7. Decoder and trim rule (AC 15, 36)
- `voice/pack/ClipDecoder.kt`:
  ```kotlin
  data class DecodedPcm(val sampleRateHz: Int, val channels: Int, val samples: ShortArray)
  interface ClipDecoder { fun decode(bytes: ByteArray): DecodedPcm }   // throws ClipDecodeException
  ```
  `MediaCodecClipDecoder`: `MediaExtractor` with a `MediaDataSource` over the bytes; `MediaCodec` (async or a sync loop); `encoder-delay` and `encoder-padding` set to 0 on the format before `configure()`.
- `ClipTrim.trim(pcm, entry, codec): ShortArray` implements pack-format §5 exactly (k = 1 or 2, channel 0, `missing` window, ≤ 10 ms zero pad).
- **Checks:**
  - JVM `ClipTrimTest` covers: the priming frame output, the first access unit dropped, k = 2, out of range → exception, short by ≤ 10 ms → padded
  - **new instrumented test** `src/androidTest/.../ClipDecoderInstrumentedTest`: decodes the B2 stub clips (generated by QA's interop step and pushed as test assets, never committed) with the real `MediaCodec` and asserts length = `frames` and onset within ±2 frames. *Not runnable in the agent environment (no emulator); listed as not verified*
    - **decided 2026-10-13 (ADR-0010 §7):** on the device the onset is checked as the first sample above −35 dBFS within [`edgeMarginMs` − 2 frames, `edgeMarginMs` + `fadeMs`]. There is no pre-encode PCM on the device; the ±2-frame correlation stays in the generator (B6). The window gates on the stub pack only. On a real pack an onset-only failure is reported, not gating. The length checks gate on every pack

### M8. Player integration and voice routing (AC 31–38, 41–43)
- `voice/VoiceRouting.kt` (pure):
  ```kotlin
  enum class Route { PACK, TTS, CHIME }
  fun route(lang: Lang, pack: PackState, ttsLocale: Locale?, sessionFallback: Boolean): Route
  // MN → PACK if Valid && !sessionFallback, else CHIME (never TTS, D80). EN → TTS if locale, else CHIME.
  ```
- `voice/pack/PromptAssembler.kt` (pure): segments → one `ShortArray` (trimmed clips + pause frames per pack-format §6 + lead-in) at the decoder's output rate. `durationMs()` uses the same rule.
- `VoiceOutput`:
  - pack branch: decode all segments on one background thread (LRU cache about 2 MB) → on main: request focus **once** → one `AudioTrack` `MODE_STATIC`, usage `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, content `SPEECH` → `finish()` at the end → abandon focus ≤ 1 s
  - any decode failure before the start → chime for this prompt, `sessionFallback = true`, `onFallback()` (A1) (AC 36, 43)
  - an `AudioTrack` error during playback → stop ≤ 1 s, same fallback
  - `stop()`, mute, off-route, the 3 s drop, language switch, «Дуусгах», calls and output changes reuse the existing paths unchanged
  - the "decision pending" wait (≤ 2 s at «Эхлэх», then chime) covers `PackState.Pending`
  - a missing segment entry → chime (never partial)
- **Robolectric/JVM checks:**
  - AC 31: first audio ≤ 1 s with a fake clock and a fake decoder, segments in order with the pauses
  - AC 32: a fake TTS reporting `mn` receives 0 Mongolian utterances
  - AC 34: focus requested once per prompt; denied → whole prompt skipped
  - AC 35: the existing rule tests pass against the pack path
  - AC 37: 0 voice network requests
  - AC 38: English uses TTS and A1 never shows in English
  - AC 41–43: fallback for the whole session, A1 once ≥ 5 s, never device `mn` TTS

### M9. Web port, text only (AC 47–49)
- Generate the canonical inventory for the web from the same two files (for example `web/scripts/gen-voice-inventory.mjs` → `web/src/guidance/voiceInventory.json`, with a vitest drift test that regenerates it in memory and compares). Use JSON, so `check:i18n` stays strict.
- `voiceText.ts`: the same D92/D94/D102 rounding for `mn`; returns `{ display, spoken, segments }` (segments recommended now, so the later web clip story needs no generator change). A Mongolian utterance (local voice only, D78) receives `spoken`. English is unchanged.
- **Unmapped Mongolian prompt (decided 2026-10-13, ADR-0010 §2):** if any segment has no inventory entry (known case: walking catch-up ≥ 2,500 m ahead), the web must **not** speak. It must not fall back to the display text, which has digits. It plays the chime for that prompt only, with no A1 and no session fallback; if no chime path can play, the prompt is skipped. Check: a walking catch-up at 3,000 m in `mn` gives 0 utterances and 1 chime, and no Mongolian utterance text ever contains a digit
- **Checks:**
  - the NAV-017 golden check passes with 0 display differences, and the web `spoken` equals the golden `spoken` column (AC 48)
  - a build scan finds 0 `.aac`/`.m4a`/`.wav` files from a pack, 0 `voicepack` references and 0 "chimege" strings in `dist*` (AC 49, AC 6)
  - NAV-017 AC 42 (0 backend requests) still passes

### M10. Order
- M1 → M2 → (M3 ∥ M4).
- M5 → M6 → M7 → M8.
- M9 after M1/M2 (shared rules), in the same change set as M2/M3 (D95 "one change").

**Mobile definition of done:** `./gradlew :app:testDebugUnitTest -Pnav.hostFerrostar=required`, `:app:lintDebug`, `:app:assembleDebug` (without a pack and with the stub pack), web `npm test`, `npm run lint`, and the golden check all pass. Not verified in the agent environment: the instrumented decoder test (M7) and everything on the device (AC 33, 39 timing, 56).

---

## 3. Cross-team checks (QA, at verification)
1. **Interop test:** `make -C backend voice-pack-stub OUT=$TMP` → `python -m voicepack verify $TMP/…/pack --policy debug` → `./gradlew :app:testDebugUnitTest -Pnav.voicePackDir=$TMP/…/pack` with a test (enabled only when the property is set) that runs `VoicePackValidator` on the real stub pack. That proves both sides compute the same canonical inventory and hashes.
2. **Reason-code parity (added 2026-10-13):** the same corrupted packs through `voicepack verify`, `VoicePackGuard` and `VoicePackValidator` give the same reason code (pack-format §8.1). The one expected difference is G1 `UNLISTED_FILE`, which is generator-only. *Added 2026-10-13:* the §8.2 size-cap fixtures give the same code in all three; the link fixtures run through `voicepack verify` and `VoicePackGuard` only (assets cannot hold links).
3. **Repository scans** (AC 19, 8, 52, 6): no tracked audio except the chime; no tracked `manifest.json` with `schemaVersion`/`inventorySha256` fields; no tracked `review/`; no non-empty `CHIMEGE_API_TOKEN=`; 0 "chimege" in `mobile/` and `web/`.
4. **PO run** (AC 56, D230): the PO runs `make voice-pack-build VOICE=FEMALE5v2 PACK_VERSION=1 …`, listens on `review/index.html`, exports `review.tsv`, then builds the `demo` APK with `-Pnav.voicePackDir`. The QA report records (`voiceId`, `packVersion`, manifest SHA-256).
   - *Updated 2026-10-13 (D236; D235 waives the PO's listen for internal builds):* the same is done for `MALE4v2`. Two separate `demo` APKs from the same source revision, one pack each, no setting. AC 51 is measured per APK, and the QA report records one triple per APK.

## 4. AC coverage map
| AC | Tasks |
|---|---|
| 1–5 | B1, M1 |
| 6 | B3, M5/M9 scans |
| 7 | B1 |
| 8–9 | B3, B10 |
| 10 | B4 |
| 11 | B3, B7 |
| 12 | B5 |
| 13 | B7, B10 |
| 14 | B5 |
| 15 | B6, M7 |
| 16 | B2 |
| 17 | B8 |
| 18 | B7 (schema) |
| 19 | B10, M5, QA scan |
| 20 | M5 |
| 21 | B8, M8 |
| 22–26 | M2, M3 |
| 27–30 | M4 |
| 31–38 | M8 |
| 39–40 | M6 |
| 41–44 | M6, M8 |
| 45–46 | M5 |
| 47–49 | M9 |
| 50–52 | M5 |
| 53 | release checklist (orchestrator, security) |
| 54 | B3 |
| 55–57 | QA |
