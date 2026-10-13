# Voice clip pack generator (NAV-016)

Build-time tool that turns the Mongolian voice inventory into a private, versioned clip pack for the Android app,
plus local review files for the PO's per-segment listen. Nothing here runs in the app, behind the gateway or in CI
against the vendor.

- Story: [NAV-016](../../../docs/requirements/stories/NAV-016-mongolian-voice-clip-pack.md); design:
  [ADR-0010](../../../docs/architecture/adr/0010-mongolian-voice-pregenerated-clip-pack.md); tasks B1-B11:
  [task file](../../../docs/architecture/tasks/NAV-016-mongolian-voice-clip-pack.md).
- Contract (read-only here): [pack-format.md](../../../docs/architecture/voice-pack/pack-format.md),
  [manifest.schema.json](../../../docs/architecture/voice-pack/manifest.schema.json),
  [inventory.tsv](../../../docs/architecture/voice-pack/inventory.tsv). Distance texts come from
  [glossary-c3-spoken.tsv](../../../docs/requirements/glossary-c3-spoken.tsv) (BA).
- Review page layout: [screen spec](../../../docs/design/screens/NAV-016-voice-pack-review.md) (English labels).
- No vendor document text is kept in the repository (D46). Our own summary of the API is ADR-0010 §4.1.

## Commands (run from `backend/`)

| Command | What it does |
|---|---|
| `make voice-pack-image` | Builds the reference image `navmn-voice-pack:1.0.0`: Python 3.11 slim + ffmpeg 7.0.2 + jsonschema, every base pinned as tag@sha256. Once per machine. |
| `make voice-pack-test` | Unit tests with the stub synthesiser and the local fake vendor server only. No network (`--network none`), no token. About 2.5 minutes on 4 CPUs. |
| `make voice-pack-stub OUT=<dir>` | A complete stub pack (synthetic tones) for FEMALE5v2 v1 in `<dir>/FEMALE5v2-v1/`. Options `VOICE=`, `PACK_VERSION=`, `PROMPTS=`. Replaces an earlier stub output of the same name. |
| `make voice-pack-verify DIR=<pack> [POLICY=release\|demo\|debug]` | Schema + checks V1-V10 of pack-format §8; prints the first failure's reason code. Default policy `release` (a stub pack fails it with `STUB_NOT_ALLOWED`; use `POLICY=debug`). |
| `make voice-pack-build VOICE=FEMALE5v2 PACK_VERSION=1 OUT=<dir> CACHE=<dir>` | The REAL pack via the vendor (304 requests on an empty cache). PO machine only, token in the shell environment (below). |
| `make voice-pack-render PACK=<pack> PROMPTS=<list.tsv> OUT=<dir>` | Renders a prompt list (pack-format §7) to WAV with the manifest's trim and join rules: the NAV-007 AC 10 harness. |

`OUT`, `CACHE` and render outputs must be **outside the repository** (or at a git-ignored path inside it:
`backend/tools/voice-pack/out/` and `.../cache/`); the runner and the generator refuse anything else before any
request. `VOICE_PACK_RUNNER=host` runs the same commands with this machine's Python (`backend/.venv`, created by
`make contract`) and ffmpeg (`NAV_VOICE_PACK_FFMPEG=<path>` or `ffmpeg` on `PATH`).

Behind a TLS-intercepting proxy the image build needs the proxy and its CA as a build secret, for example
`make voice-pack-image VOICE_PACK_BUILD_ARGS='--network host --build-arg HTTPS_PROXY --secret id=pip-ca,src=<ca.pem>'`.
If the default registry is rate-limited, pass `--build-arg PYTHON_IMAGE=<mirror>/library/python:3.11-slim-bookworm@sha256:<same digest>`
(and the same for `FFMPEG_IMAGE`): the digest pins the content, whatever registry serves it.

### Direct CLI

```sh
cd backend/tools/voice-pack
python -m voicepack inventory [--out FILE]          # canonical inventory bytes, inventorySha256, glossaryRevision
python -m voicepack build --voice FEMALE5v2|MALE4v2 --pack-version N --synth chimege|stub --out DIR \
    [--cache DIR] [--bitrate-kbps 24|28|32] [--clip-rate 22050|16000] [--join-pause-ms 80] [--then-pause-ms 80] \
    [--take-method carrier-then-v1] [--prompts FILE] [--replace] [--log FILE]
python -m voicepack verify DIR [--policy release|demo|debug]
python -m voicepack render --pack DIR --prompts FILE --out DIR
```

Exit codes: `0` OK; `1` pack invalid (verify, render) or internal error; `2` configuration (token, path, ffmpeg, API
base, options); `3` inventory text rule; `4` vendor; `5` audio check; `6` output (target exists without `--replace`,
or the atomic rename failed).

`verify` needs `jsonschema` (in the image and in `backend/.venv`; otherwise `pip install -r requirements.txt`).

## The token (CHIMEGE_API_TOKEN)

- It is read **only** from the environment variable `CHIMEGE_API_TOKEN` of the shell that runs the build. Missing or
  empty: exit 2 with `CHIMEGE_API_TOKEN is not set`, and no request is sent.
- Put it into the shell without writing it anywhere, run the build, then remove it:

  ```sh
  read -rs CHIMEGE_API_TOKEN && export CHIMEGE_API_TOKEN     # paste, Enter; nothing is echoed or stored
  make voice-pack-build VOICE=FEMALE5v2 PACK_VERSION=1 OUT=~/navmn-voice/out CACHE=~/navmn-voice/cache
  unset CHIMEGE_API_TOKEN
  ```

- Never in a file in the repository, never in `backend/.env` (its `.env.example` key stays empty), never in CI
  secrets, never in the Android build. CI never calls the vendor: on a public repository, CI artifacts would publish
  the clips.
- The make target passes it to the container by name only (`docker run -e CHIMEGE_API_TOKEN`). The generator sends
  it only in the `token` request header, never follows a redirect, and runs every stdout, stderr and log line
  through a redaction filter. The manifest, the cache index, the review files and the log never contain it; the
  sentinel test (`tests/test_chimege.py`, NAV-016 AC 9) proves this for success, 401, 429, 500 and timeout runs.
- If the token was exposed anyway, the PO asks the vendor for a new one.

Requests: `POST https://api.chimege.com/v1.2/synthesize`, UTF-8 text body with the content type the F4 pre-screen
used, headers `token`, `voice-id`, `speed 1`, `pitch 1`, `sample-rate 22050`. At most 2 requests at a time; 429,
500, 502, 503, 504, a 30 s timeout or a connection error are retried 3 times after 1, 2 and 4 s (or `Retry-After`,
at most 60 s); any other 4xx fails at once; 403 stops the run. The only texts sent are the inventory texts and, for
`nonFinal` takes, the same text followed by «, дараа нь» (NAV-016 AC 54).

## Custody of packs, cache and token (ADR-0010 §4.5)

- A real pack is generated only on a machine where the PO has put the token into that shell: the PO's own machine,
  or an agent session where the PO set it for the session.
- **The cache is the valuable part:** it holds the vendor audio. A retrained vendor voice gives different audio
  after a cache clear, which needs a new `packVersion` and a new listen. Keep the cache and every delivered pack
  directory outside the repository, on that machine plus **one PO-controlled private backup** (the PO chooses the
  medium). The cache is cleared only by hand.
- **Delivery:** the Android build reads the pack from `-Pnav.voicePackDir=<OUT>/<voice>-v<n>/pack`. APKs with a
  pack go to the PO only, by direct file transfer, until the public-release gates are met (NAV-016 AC 53).
- Every pack given to anyone is identified by (`voiceId`, `packVersion`, SHA-256 of `manifest.json`). `build` and
  `verify` print that triple; it goes into the QA report and the NAV-007 sign-off record. It is not secret; the
  audio is.
- Nothing from `OUT` or `CACHE` is ever committed (D79): `backend/.gitignore` ignores the in-repo fallback paths
  and every `.aac`, `.m4a`, `.wav` and `review/` under this tool.

## Output

```
<OUT>/<voice>-v<n>/
  pack/      manifest.json + clips/<key>.<take>.aac       the only directory a client build may read
  review/    index.html, review.tsv, prompts/*.wav, prompts.tsv   local listening only, never bundled or committed
```

Written to a temporary sibling, verified, then renamed. On any failure no pack and no manifest appear and an earlier
pack stays untouched. An existing `<voice>-v<n>` is replaced only with `--replace` (the old one is removed after the
new one is in place). The manifest has no timestamp, host name, user name, absolute path or token.

**Byte identity** (NAV-016 AC 13): the same cache, inventory, voice, options and generator image give byte-identical
clips and `manifest.json`. It is guaranteed only within the reference image (ffmpeg 7.0.2 pinned); a host ffmpeg
of another version needs `--ffmpeg-any-version` and gives different bytes.

## The PO's listen (D230)

Open `review/index.html` from disk in Chrome, Edge or Safari, with headphones. Every entry has a row: play (Space),
`ok` (key 1) / `bad` (key 2), a note (Enter; Esc leaves the field), ↑/↓ to move, "Play from here" for hands-free
runs (Esc or Stop ends it). Marks are kept in the browser per pack; "Download review.tsv" exports
`key, take, spoken_mn, result, note`, which the PO saves over `review/review.tsv`. The last section has sample
prompts with the manifest's join rules: the longest single and chained prompt (from `PROMPTS=`, the Android
`longest-prompts.tsv`, or else the generator's own superset), the two pre-screen prompts and 20 coverage prompts
(`coverage-prompts.tsv`). A stub pack shows a banner and cannot be marked. A public build needs 0 `bad` rows.

`review/prompts.tsv` also lists the 10 longest single and 10 longest chained prompts of the generator's superset of
prompt shapes, with `durationMs` and the AC 29 limits (5,000 / 8,000 ms). It is an early warning only; the Android
duration check over the real generator domain is authoritative. The superset ignores the schedule, so with the stub
(70 ms per character) some superset chained prompts exceed 8,000 ms.

## What the generator checks

- Text rules before any request (pack-format §2.2): `digit`, `latin`, `zero-width`, `charset`, `length`, `nfc`,
  `avoid` (glossary Avoid terms and the D22 patterns, NAV-007 AC 16). Exit 3 names the key and the rule.
- Vendor response: RIFF/WAVE, PCM 16-bit, mono, 22,050 Hz.
- `nonFinal` takes, `carrier-then-v1` (ADR-0010 §4.3): cut at the comma pause of «<text>, дараа нь», with the
  self-check (rule `nonFinal-boundary`); each `nonFinal` file must differ from its `final` file.
- Processing and checks (pack-format §4): trim with a 5 ms margin, 10 ms fades, one gain to -20 dBFS active RMS with
  a -1 dBFS peak ceiling; edge silence <= 40 ms, peak, 150-4,000 ms, internal silence <= 500 ms, active RMS within
  2 dB of the pack median. Exit 5 names the key and the rule.
- Codec (pack-format §4-§5): AAC-LC, mono, raw ADTS, 24 kbit/s by default; the encoder delay is measured at start-up
  (must be 1,024); every clip is decoded again, checked for `D == encodedFrames`, trimmed with the manifest rule to
  exactly `frames`, and aligned within 0 ± 2 frames. The pack size is printed, with a warning above 1.9 MB
  (NAV-016 AC 51 allows 2.0 MB).

## Files

| Path | Role |
|---|---|
| `voicepack/` | the generator (standard library + jsonschema; ffmpeg is the only external binary) |
| `coverage-prompts.tsv` | the 20 fixed coverage prompts of the review page |
| `tests/` | unittest suite; `tests/fake_chimege.py` is the local fake vendor server (127.0.0.1 only) |
| `Dockerfile`, `requirements.txt` | the reference image; dependencies installed with `--require-hashes` |
| `run.sh` | runner behind the make targets (path checks, mounts, the token by name) |

## Not verified in the agent environment

A real vendor run (304 requests), the real pack size, the real `nonFinal` cuts and the PO's listen. They are
verified when the PO runs `make voice-pack-build`.
