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
| `make voice-pack-build-cached VOICE=FEMALE5v2 PACK_VERSION=1 OUT=<dir> CACHE=<dir>` | The REAL pack from a **complete** cache, with no token and no network (`--network none`, cache mounted read-only). Fails with exit 2, naming the missing entries, if any entry is not in the cache. See "Building from the cache" below. |
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
    [--take-method carrier-then-v1] [--prompts FILE] [--replace] [--log FILE] [--cache-only]
python -m voicepack verify DIR [--policy release|demo|debug]
python -m voicepack render --pack DIR --prompts FILE --out DIR
```

Exit codes: `0` OK; `1` pack invalid (verify, render) or internal error; `2` configuration (token, path, ffmpeg, API
base, options); `3` inventory text rule; `4` vendor; `5` audio check; `6` output (target exists without `--replace`,
or the atomic rename failed).

`verify` applies pack-format §8 in order, with the fault ownership of §8.1 (a top-level value that is not an object,
`1.0` or `1e3` where an integer is required, and any control character in a string are `MANIFEST_UNREADABLE`) and
the file rules of §8.2: `manifest.json`, `clips/` and every listed clip are checked without following links, each
clip's real path must be inside the pack directory, and the caps are 1 MiB (manifest), 256 KiB (clip) and 6 MiB
(pack), giving `MANIFEST_UNREADABLE` (V2) or `FILE_INVALID` (V9). After V10 the generator-only check
`UNLISTED_FILE` refuses anything unlisted in the pack directory, links included. `build` runs the same checks on its
own output before the rename.

`verify` needs `jsonschema` (in the image and in `backend/.venv`; otherwise `pip install -r requirements.txt`).

## The token (CHIMEGE_API_TOKEN)

- It is read **only** from the environment variable `CHIMEGE_API_TOKEN` of the shell that runs the build. Missing or
  empty: exit 2 with `CHIMEGE_API_TOKEN is not set`, and no request is sent. The generator reads it once at start-up
  and removes it from its own environment, so ffmpeg, git and the processing workers never inherit it.
- **Never paste the token into a chat, an issue, an issue comment, a pull request, a commit message or any agent
  prompt or handoff** (ADR-0010 §4.5): those texts are stored and copied. An agent session gets the token only from
  its process environment, set by the PO **before the session starts**; an agent never asks for it in conversation,
  never echoes it and never writes it on a command line (commands pass it by name only).
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
- If the token was exposed anyway (seen in a repository file, a log or an issue, or the vendor reports unexpected
  use), the PO asks the vendor for a new one.

Requests: `POST https://api.chimege.com/v1.2/synthesize`, UTF-8 text body with the content type the F4 pre-screen
used, headers `token`, `voice-id`, `speed 1`, `pitch 1`, `sample-rate 22050`. At most 2 requests at a time; 429,
500, 502, 503, 504, a timeout or a connection error are retried 3 times after 1, 2 and 4 s (or `Retry-After`, at
most 60 s). The timeout is one overall 30 s deadline per attempt (connect, headers and body together), and a
response body over 8 MB counts as a timeout too. Any other 4xx fails at once and prints the key, the `Error-Code`
(only if it is 1-16 characters of `0-9 A-Z a-z -`, otherwise "invalid Error-Code") and its meaning from the
ADR-0010 §4.1 table (4000-4008; anything else is "unknown Error-Code"); 403 stops the run. A TLS certificate failure
stops the run at once ("TLS certificate not trusted"); verification is never turned off. The only texts sent are
the inventory texts and, for `nonFinal` takes, the same text followed by «, дараа нь» (NAV-016 AC 54).

`--api-base` accepts only a loopback host, i.e. the local fake server of the tests. Such a run writes
`source.vendor` = `stub` and `source.apiVersion` = `stub` into the manifest and keys its cache entries with the vendor
value `fake` (pack-format §8.3), so a fake-server pack fails `verify --policy demo|release` with `STUB_NOT_ALLOWED`
and fake audio never satisfies a real run from a shared cache.

### Behind a TLS-intercepting proxy

Some build machines (agent environments included) reach the internet only through a proxy that re-signs TLS. The
synthesis step inside the reference image then needs extra `docker run` arguments, given **at run time only** through
the environment variable `VOICE_PACK_DOCKER_ARGS` (read by `run.sh` for `voice-pack-build` only; ADR-0010 §4.5):

```sh
export VOICE_PACK_DOCKER_ARGS='--network host -v <host path of the proxy CA bundle>:<path in the container>:ro -e SSL_CERT_FILE=<path in the container>'
make voice-pack-build VOICE=FEMALE5v2 PACK_VERSION=1 OUT=~/navmn-voice/out CACHE=~/navmn-voice/cache
unset VOICE_PACK_DOCKER_ARGS
```

- Host networking is needed when the proxy listens on the host's loopback interface. `run.sh` already passes the
  proxy variables into the container by name (`-e HTTPS_PROXY -e NO_PROXY`), never by value.
- `SSL_CERT_FILE` **adds** the proxy's CA for Python's default verified TLS context; certificate and host-name
  verification stay on. The bundle is mounted read-only, never baked into the image and never copied into `OUT`, the
  cache or the repository. Never use an unverified context, `PYTHONHTTPSVERIFY=0` or an "insecure" switch.
- Commit nothing about the proxy: no host name, port, CA path or credential in the repository, the Makefile or
  `.env.example`. A credential inside a proxy URL is redacted from every output like the token.
- The proxy decrypts the request, so it sees the token. Run synthesis through it only if it belongs to the same
  environment the PO already trusts with the token.
- Once the cache is complete, build packs with `make voice-pack-build-cached` instead: no proxy, no network, no token.

### Building from the cache

`make voice-pack-build-cached VOICE=… PACK_VERSION=… OUT=… CACHE=…` (CLI: `build --synth chimege --cache-only`)
builds the real pack from vendor audio that an earlier `voice-pack-build` already cached. It runs with
`--network none`, mounts the cache read-only, passes no token and ignores `VOICE_PACK_DOCKER_ARGS`; the generator
loads no network code for it. It first checks that every one of the 304 entries is in the cache under the real
vendor's key (`chimege`, `v1.2`) and stops with exit 2 and 0 requests if any is missing. The pack is the same as a
`voice-pack-build` from that cache would give (`source.vendor` = `chimege`). Every run that would send a request
still needs the token.

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
of another version needs `--ffmpeg-any-version` and gives different bytes. The pin is the **release**: a static or
distribution build of 7.0.2 (version string `7.0.2-static`, `7.0.2-0ubuntu1`) passes the check without the flag and
writes the same `codec.encoder` (`ffmpeg-7.0.2-aac`); the run output names the build (`ffmpeg 7.0.2 (build
7.0.2-static)`). Byte identity between such a build and the image is expected for the same release, not proven here.

## The PO's listen (D230)

Open `review/index.html` from disk in Chrome, Edge or Safari, with headphones. Every entry has a row: play (Space),
`ok` (key 1) / `bad` (key 2), a note (Enter; Esc leaves the field), ↑/↓ to move, "Play from here" for hands-free
runs (Esc or Stop ends it). Marks are kept in the browser per pack; "Download review.tsv" exports
`key, take, spoken_mn, result, note`, which the PO saves over `review/review.tsv`. The last section has sample
prompts with the manifest's join rules: the longest single and chained prompt (from `PROMPTS=`, the Android
`longest-prompts.tsv`, or else the generator's own superset), the two pre-screen prompts and 20 coverage prompts
(`coverage-prompts.tsv`). A stub pack shows a banner and cannot be marked. A public build needs 0 `bad` rows. The page carries a
Content-Security-Policy that lets it run only its own inline script and style (by hash) and play only the local
files next to it: no network access, no other script.

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
  2 dB of the pack median. Exit 5 names the key and the rule. The -45 dBFS threshold of the trim and of the active
  RMS is the level of the **delivered** clip, after the gain, which is where the checks and the AC 33 join measurement
  look. The vendor's audio is louder than the target (gain about -1 to -11 dB), so the speech region and the gain are
  found together (a fixed point, 2-3 rounds); the sample operations keep the order trim, fades, gain.
  **Edge re-trim:** the region is found before the fades, and the 10 ms fade covers half of the region's edge window,
  so a breath or noise blip just above the threshold after a gap (MALE4v2 `fixed.gpsRestored`: 50 ms at -46 to -52
  dBFS, then one 10 ms window 0.6 dB above the threshold) is active before the fade-out and silent after it, leaving
  90 ms of trailing silence. When the delivered PCM's leading or trailing silence exceeds the 40 ms limit, the
  region is re-derived from the delivered PCM's first and last active windows and the fixed point is run again (at
  most 4 times; the run output names every re-trimmed clip: "edge re-trim: <key> (<take>)"). A clip within the limit
  is never re-trimmed, so packs that passed before are byte-identical (FEMALE5v2 v1 has 33 clips with 20-40 ms edges
  of this shape).
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
| `voicepack/reposcan.py` | repo-wide scans run by CI: AC 8 token values, AC 19 audio files, pack manifests and review pages anywhere in the tree |

## Not verified in the agent environment

The vendor's TLS, redirect and error behaviour beyond the fake server, and the PO's listen. The real vendor audio of
both voices was synthesised once (2026-10-13, through a TLS-intercepting proxy as described above); packs are built
from that cache with `make voice-pack-build-cached`.
