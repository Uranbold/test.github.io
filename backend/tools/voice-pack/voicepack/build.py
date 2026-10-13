"""`voicepack build`: inventory -> pre-flight -> synthesis (cache) -> processing -> encode + decode check ->
manifest -> verify -> review files -> atomic rename (ADR-0010 §4.2; tasks B1-B8, B10, B11)."""

from __future__ import annotations

import hashlib
import os
import secrets
import shutil
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import audio, manifest as manifest_mod, review
from .cache import NO_TAKE_METHOD, Cache, cache_fields, cache_key, check_path_allowed, default_cache_dir
from .codec import BITRATES_KBPS, CLIP_RATES, EXPECTED_ENCODER_DELAY, Ffmpeg, encode_and_check
from .errors import AudioCheckError, ConfigError, InventoryError, OutputError, PackInvalid, RunAborted, VoicePackError
from .inventory import FINAL, NON_FINAL, Inventory, load as load_inventory, sha256_hex
from .redact import Reporter
from .synth import chimege as _chimege  # constants only: the cache-key identity of a real run
from .render import Prompt, parse_prompt_list, prompt_ms, render_prompts, write_prompts_tsv
from .textrules import TAKE_METHODS, AvoidRules, check_entries, request_text
from .verify import ALLOWED_VOICES, verify_pack

SOURCE_RATE = 22050  # requested from the vendor (NAV-016 AC 6)
SIZE_WARN_BYTES = 1_900_000  # AC 51 allows 2.0 MB of APK growth
TOOL_DIR = Path(__file__).resolve().parents[1]
COVERAGE_PROMPTS = TOOL_DIR / "coverage-prompts.tsv"


@dataclass
class BuildOptions:
    voice: str
    pack_version: int
    synth: str
    out: Path
    repo: Path
    cache: Path | None = None
    bitrate_kbps: int = 24
    clip_rate: int = 22050
    join_pause_ms: int = 80
    then_pause_ms: int = 80
    take_method: str = "carrier-then-v1"
    prompts: Path | None = None
    replace: bool = False
    api_base: str | None = None
    ffmpeg: str | None = None
    ffmpeg_any_version: bool = False
    jobs: int = 0
    cache_only: bool = False


@dataclass
class BuildResult:
    pack_dir: Path
    run_dir: Path
    manifest_sha256: str
    pack_bytes: int
    entries: int
    synthesised: int
    cache_hits: int
    requests_sent: int | None
    warnings: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class _Job:
    key: str
    fields: dict
    text: str
    label: str


# ----------------------------------------------------------------------------------------------- pre-flight
def preflight_inventory(repo: Path, take_method: str) -> Inventory:
    inv = load_inventory(repo)
    violations = check_entries(inv.entries, AvoidRules.from_repo(repo), take_method)
    if violations:
        lines = "\n  ".join(str(v) for v in violations)
        raise InventoryError(f"{len(violations)} text rule violation(s), 0 requests sent:\n  {lines}")
    return inv


def _check_options(o: BuildOptions) -> None:
    if o.voice not in ALLOWED_VOICES:
        raise ConfigError(f"--voice must be one of {', '.join(ALLOWED_VOICES)}")
    if o.pack_version < 1:
        raise ConfigError("--pack-version must be >= 1")
    if o.synth not in ("chimege", "stub"):
        raise ConfigError("--synth must be chimege or stub")
    if o.bitrate_kbps not in BITRATES_KBPS:
        raise ConfigError(f"--bitrate-kbps must be one of {BITRATES_KBPS}")
    if o.clip_rate not in CLIP_RATES:
        raise ConfigError(f"--clip-rate must be one of {CLIP_RATES}")
    for name, v in (("--join-pause-ms", o.join_pause_ms), ("--then-pause-ms", o.then_pause_ms)):
        if not (0 <= v <= 300):
            raise ConfigError(f"{name} must be 0-300")
    if o.take_method not in TAKE_METHODS:
        raise ConfigError(f"--take-method must be one of {TAKE_METHODS}")
    if o.cache_only:
        if o.synth != "chimege":
            raise ConfigError("--cache-only needs --synth chimege (it builds a real pack from cached vendor audio)")
        if o.api_base is not None:
            raise ConfigError("--cache-only and --api-base exclude each other")


class CacheOnlySynthesiser:
    """`--cache-only`: the Chimege identity (cache keys and manifest source = chimege / v1.2) without any network
    code and without the token. Every entry must already be in the cache; synthesize() is never reached because the
    build checks completeness first, and it refuses if it is."""

    vendor = _chimege.VENDOR
    api_version = _chimege.API_VERSION
    cache_vendor = _chimege.VENDOR  # the same keys a real run wrote
    cache_api_version = _chimege.API_VERSION
    speed = _chimege.SPEED
    pitch = _chimege.PITCH
    max_in_flight = 1
    requests_sent = 0

    def synthesize(self, request_text: str, voice: str, sample_rate: int, *, label: str = "") -> bytes:
        raise ConfigError(f"{label or 'entry'}: not in the cache, and --cache-only never sends a request")


# ----------------------------------------------------------------------------------------------- synthesis
def synthesize_all(synth, jobs: list[_Job], cache: Cache, voice: str, reporter: Reporter) -> tuple[int, int]:
    """One request per uncached request text (deduplicated by cache key), at most synth.max_in_flight at once.
    Returns (synthesised, cache hits)."""
    seen: set[str] = set()
    todo: list[_Job] = []
    for j in jobs:
        if j.key in seen:
            continue
        seen.add(j.key)
        if cache.get(j.key) is None:
            todo.append(j)
    hits = len(seen) - len(todo)
    reporter.info(f"synthesis: {len(todo)} to request, {hits} from the cache", to_request=len(todo), cache_hits=hits)
    if not todo:
        return 0, hits

    def one(j: _Job) -> None:
        data = synth.synthesize(j.text, voice, SOURCE_RATE, label=j.label)
        cache.put(j.key, j.fields, data)

    workers = max(1, int(getattr(synth, "max_in_flight", 1)))
    done = 0
    if workers == 1:
        for j in todo:
            one(j)
            done += 1
            if done % 50 == 0:
                reporter.info(f"  synthesised {done}/{len(todo)}")
        return done, hits
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(one, j) for j in todo]
        try:
            for f in as_completed(futs):
                f.result()
                done += 1
                if done % 50 == 0:
                    reporter.info(f"  synthesised {done}/{len(todo)}")
        except BaseException as first:
            ab = getattr(synth, "aborted", None)
            if ab is not None:
                ab.set()
            for f in futs:
                f.cancel()
            if isinstance(first, RunAborted):
                # a worker only noticed the abort: report the error that caused it (the 403 or TLS message)
                for f in futs:
                    if f.cancelled():
                        continue
                    e = f.exception()
                    if e is not None and not isinstance(e, RunAborted):
                        raise e from None
            raise
    return done, hits


# ----------------------------------------------------------------------------------------------- processing
def _process_job(args: tuple) -> dict:
    (key, take, raw, cut, then_ms, final_span, ff_exe, ff_version, clip_rate, bitrate, delay) = args
    label = f"{key} ({take})"
    try:
        samples = audio.require_pcm16_mono(audio.read_wav(raw), SOURCE_RATE)
    except ValueError as e:
        raise AudioCheckError(f"{label}: rule wav: {e}") from None
    if cut:
        samples = audio.cut_carrier(samples, SOURCE_RATE, label, then_ms, final_span)
    ff = Ffmpeg.trusted(ff_exe, ff_version)
    if clip_rate != SOURCE_RATE:
        samples = ff.resample(samples, SOURCE_RATE, clip_rate)
    p = audio.process(samples, clip_rate, label)
    audio.check_clip(p, label)
    enc = encode_and_check(ff, label, p.samples, clip_rate, bitrate, delay)
    return {
        "key": key,
        "take": take,
        "aac": enc.data,
        "frames": enc.frames,
        "encodedFrames": enc.encoded_frames,
        "durationMs": audio.duration_ms(enc.frames, clip_rate),
        "rms": p.active_rms_db,
        "peak": p.peak_db,
    }


def _run_jobs(fn: Callable, items: list, jobs: int) -> list:
    if jobs <= 1:
        return [fn(a) for a in items]
    ex = ProcessPoolExecutor(max_workers=jobs)
    try:
        return list(ex.map(fn, items, chunksize=4))
    finally:
        ex.shutdown(wait=True, cancel_futures=True)


# ----------------------------------------------------------------------------------------------- review prompts
def _review_prompts(m: dict, opts: BuildOptions, reporter: Reporter) -> tuple[list[Prompt], list[str]]:
    """Order for the page: the longest single and chained (marked), the two measured prompts, the coverage set,
    then the rest of the longest lists."""
    tokens = {f"{e['key']}:{e['take']}" for e in m["entries"]}
    warnings: list[str] = []

    def usable(ps: list[Prompt], what: str, strict: bool) -> list[Prompt]:
        out = []
        for p in ps:
            missing = [t for t in p.tokens if t not in tokens]
            if missing:
                msg = f"{what} prompt {p.id}: segment(s) not in this pack: {' '.join(missing)}"
                if strict:
                    raise ConfigError(msg)
                warnings.append(msg)
                continue
            out.append(p)
        return out

    sup_s, sup_c = review.superset_longest(m)
    listed: list[Prompt] = []
    if opts.prompts is not None:
        listed = usable(parse_prompt_list(opts.prompts.read_text(encoding="utf-8"), opts.prompts.name), "--prompts", True)
        for p in listed:
            p.extra = {"kind": p.extra.get("kind") or "list", **({"display": p.extra["display"]} if p.extra.get("display") else {})}
    coverage = usable(review.coverage_prompts(COVERAGE_PROMPTS), "coverage", False)
    measured = usable([Prompt(p.id, list(p.tokens), dict(p.extra)) for p in review.MEASURED_PROMPTS], "measured", False)
    pool = listed if listed else sup_s + sup_c
    singles = [p for p in pool if not p.chained]
    chained = [p for p in pool if p.chained]
    marked = []
    for kind, ps in (("single", singles), ("chained", chained)):
        if ps:
            top = max(ps, key=lambda p: (prompt_ms(p, m), [-ord(c) for c in p.id]))
            top.extra["mark"] = kind
            marked.append(top)
    rest = [p for p in (listed + sup_s + sup_c) if p not in marked]
    ordered = marked + measured + coverage + rest
    ids = [p.id for p in ordered]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    if dup:
        raise ConfigError(f"duplicate prompt id(s) across the prompt sets: {', '.join(dup)}")
    return ordered, warnings


# ----------------------------------------------------------------------------------------------- build
def run_build(opts: BuildOptions, reporter: Reporter, synth_factory: Callable | None = None) -> BuildResult:
    _check_options(opts)
    repo = opts.repo
    # 1. inventory and text rules (exit 3, 0 requests)
    inv = preflight_inventory(repo, opts.take_method)
    reporter.info(f"inventory: {len(inv.entries)} entries ({inv.n_final} final + {inv.n_non_final} nonFinal), "
                  f"inventorySha256 {inv.sha256}", entries=len(inv.entries), inventory_sha256=inv.sha256)
    if inv.n_non_final and opts.take_method == "carrier-then-v1" and inv.get("join.then", FINAL) is None:
        raise InventoryError("join.then (final) is needed for the carrier-then-v1 self-check [rule: format]")
    if opts.take_method != "carrier-then-v1":
        reporter.warn(f"take method {opts.take_method} is for a review comparison only, never for a delivered pack (ADR-0010 §4.3)")
    # 2. paths (exit 2), target (exit 6)
    out = check_path_allowed(opts.out, repo, "--out")
    name = f"{opts.voice}-v{opts.pack_version}"
    target = out / name
    if target.exists() and not opts.replace:
        raise OutputError(f"{target} exists; pass --replace to replace it, or use a new --pack-version")
    if opts.cache is not None:
        cache_dir: Path | None = check_path_allowed(opts.cache, repo, "--cache")
    elif opts.synth == "chimege":
        cache_dir = check_path_allowed(default_cache_dir(), repo, "cache")
    else:
        cache_dir = None  # stub without --cache: in memory only
    if opts.prompts is not None and not opts.prompts.is_file():
        raise ConfigError(f"--prompts {opts.prompts} not found")
    # 3. ffmpeg (exit 2)
    ff = Ffmpeg(opts.ffmpeg, allow_any_version=opts.ffmpeg_any_version)
    delay = ff.measure_encoder_delay(opts.clip_rate, opts.bitrate_kbps)
    if delay != EXPECTED_ENCODER_DELAY:
        raise ConfigError(f"measured encoder delay {delay} frames, expected {EXPECTED_ENCODER_DELAY} (ffmpeg {ff.version})")
    reporter.info(f"ffmpeg {ff.version}: aac encoder delay {delay} frames", ffmpeg=ff.version, encoder_delay=delay)
    # 4. synthesiser (token check, exit 2; --cache-only: no token, no network code)
    if opts.cache_only:
        if cache_dir is None or not (cache_dir / "wav").is_dir():
            raise ConfigError(f"--cache-only: no cache at {cache_dir} (expected a wav/ directory)")
        synth = CacheOnlySynthesiser()
    elif synth_factory is not None:
        synth = synth_factory(opts)
    elif opts.synth == "stub":
        from .synth.stub import StubSynthesiser

        synth = StubSynthesiser()
    else:
        from .synth.chimege import ChimegeSynthesiser

        synth = ChimegeSynthesiser(opts.api_base)
    cache = Cache(cache_dir, create=not opts.cache_only)
    cache_vendor = getattr(synth, "cache_vendor", synth.vendor)  # pack-format §8.3: "fake" for a fake-server run
    cache_api_version = getattr(synth, "cache_api_version", synth.api_version)
    # 5. synthesis
    jobs: list[_Job] = []
    job_of: dict[tuple[str, str], _Job] = {}
    for e in inv.entries:
        text = request_text(e, opts.take_method)
        fields = cache_fields(vendor=cache_vendor, api_version=cache_api_version, voice=opts.voice,
                              sample_rate=SOURCE_RATE, speed=synth.speed, pitch=synth.pitch,
                              take_method=opts.take_method if e.take == NON_FINAL else NO_TAKE_METHOD,
                              request_text=text)
        j = _Job(cache_key(fields), fields, text, f"{e.key} ({e.take})")
        jobs.append(j)
        job_of[(e.key, e.take)] = j
    if opts.cache_only:
        missing = sorted({j.label for j in jobs if cache.get(j.key) is None})
        if missing:
            raise ConfigError(f"--cache-only: {len(missing)} of {len(jobs)} entries are not in the cache "
                              f"(first: {', '.join(missing[:5])}); 0 requests sent")
        reporter.info(f"cache-only: all {len(jobs)} entries are in the cache; no request, no token")
    synthesised, hits = synthesize_all(synth, jobs, cache, opts.voice, reporter)
    requests_sent = getattr(synth, "requests_sent", None)
    # 6. processing, encode, decode check
    raw = {k: cache.get(j.key) for k, j in job_of.items()}
    cut = opts.take_method == "carrier-then-v1"
    then_ms = 0.0
    if cut and inv.n_non_final:
        then_raw = audio.require_pcm16_mono(audio.read_wav(raw[("join.then", FINAL)]), SOURCE_RATE)
        then_ms = audio.active_ms(then_raw, SOURCE_RATE)
    items = []
    for e in inv.entries:
        final_span = 0.0
        if e.take == NON_FINAL and cut:
            fr = audio.require_pcm16_mono(audio.read_wav(raw[(e.key, FINAL)]), SOURCE_RATE)
            final_span = audio.speech_span_ms(fr, SOURCE_RATE)
        items.append((e.key, e.take, raw[(e.key, e.take)], cut and e.take == NON_FINAL, then_ms, final_span,
                      ff.exe, ff.version, opts.clip_rate, opts.bitrate_kbps, delay))
    njobs = opts.jobs or min(4, os.cpu_count() or 1)
    reporter.info(f"processing and encoding {len(items)} clips ({njobs} worker(s))")
    results = _run_jobs(_process_job, items, njobs)
    for label, dev in audio.check_rms_outliers([(f"{r['key']} ({r['take']})", r["rms"], r["peak"]) for r in results]):
        reporter.warn(f"level band: {label} is peak-limited at {dev:+.2f} dB from the pack median (allowed down to "
                      f"-{audio.PEAK_LIMITED_FLOOR_DB:.0f} dB; listen to it, pack-format §4)", key=label, deviation_db=round(dev, 2))
    sha = {(r["key"], r["take"]): hashlib.sha256(r["aac"]).hexdigest() for r in results}
    for (k, t), h in sha.items():
        if t == NON_FINAL and sha.get((k, FINAL)) == h:
            raise AudioCheckError(f"{k} (nonFinal): rule nonFinal-identical: the nonFinal file equals the final file")
    # 7. manifest
    entries = []
    for e, r in zip(inv.entries, results, strict=True):
        entries.append({"key": e.key, "take": e.take, "spokenText": e.spoken, "file": e.file, "frames": r["frames"],
                        "encodedFrames": r["encodedFrames"], "durationMs": r["durationMs"], "sha256": sha[(e.key, e.take)]})
    m = manifest_mod.build(
        pack_version=opts.pack_version, voice=opts.voice, vendor=synth.vendor, api_version=synth.api_version,
        source_rate=SOURCE_RATE, clip_rate=opts.clip_rate, bitrate_kbps=opts.bitrate_kbps, encoder_delay=delay,
        encoder=ff.encoder_name, take_method=opts.take_method, join_pause_ms=opts.join_pause_ms,
        then_pause_ms=opts.then_pause_ms, processing=audio.PROCESSING, glossary_revision=inv.glossary_revision,
        inventory_sha256=inv.sha256, entries=entries,
    )
    mbytes = manifest_mod.dumps(m)
    # 8. write to a temporary sibling, verify, review, rename
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / f".{name}.tmp-{secrets.token_hex(6)}"
    renamed = False
    warnings: list[str] = []
    try:
        pack = tmp / "pack"
        (pack / "clips").mkdir(parents=True)
        for e, r in zip(inv.entries, results, strict=True):
            (pack / e.file).write_bytes(r["aac"])
        (pack / "manifest.json").write_bytes(mbytes)
        try:
            verify_pack(pack, policy="debug", expected_inventory_sha256=inv.sha256, schema=None if repo is None else _schema(repo))
        except PackInvalid as x:
            raise OutputError(f"the new pack failed its own verification: {x}") from None
        pack_bytes = sum(len(r["aac"]) for r in results) + len(mbytes)
        prompts, warnings = _review_prompts(m, opts, reporter)
        for w in warnings:
            reporter.warn(w)
        rows = render_prompts(pack, m, prompts, tmp / "review", ff, wav_subdir="prompts")
        for r in rows:
            chained = "join.then:" in r["segments"]
            limit = review.CHAINED_LIMIT_MS if chained else review.SINGLE_LIMIT_MS
            r["limitMs"] = limit
            r["withinLimit"] = "yes" if r["durationMs"] <= limit else "no"
            if r["durationMs"] > limit and r.get("kind", "") != "coverage":
                msg = f"prompt {r['id']} lasts {r['durationMs']} ms > {limit} ms (NAV-016 AC 29 early warning)"
                warnings.append(msg)
                reporter.warn(msg)
        write_prompts_tsv(tmp / "review" / "prompts.tsv", rows,
                          ["id", "segments", "kind", "mark", "durationMs", "limitMs", "withinLimit", "spoken", "file"])
        review.write_review(tmp / "review", m, rows)
        if target.exists():
            if not opts.replace:
                raise OutputError(f"{target} appeared during the run; nothing replaced")
            old = out / f".{name}.old-{secrets.token_hex(6)}"
            os.rename(target, old)
            try:
                os.rename(tmp, target)
            except OSError:
                os.rename(old, target)
                raise
            renamed = True
            shutil.rmtree(old, ignore_errors=True)
        else:
            try:
                os.rename(tmp, target)
            except OSError as x:
                raise OutputError(f"cannot rename the new pack into place: {x.strerror}") from None
            renamed = True
    except VoicePackError:
        raise
    except OSError as x:
        raise OutputError(f"writing the output failed: {x.strerror or x}") from None
    finally:
        if not renamed:
            shutil.rmtree(tmp, ignore_errors=True)
    if pack_bytes > SIZE_WARN_BYTES:
        msg = f"pack size {pack_bytes} bytes > {SIZE_WARN_BYTES} (NAV-016 AC 51 allows 2.0 MB of APK growth)"
        warnings.append(msg)
        reporter.warn(msg)
    return BuildResult(
        pack_dir=target / "pack", run_dir=target, manifest_sha256=sha256_hex(mbytes), pack_bytes=pack_bytes,
        entries=len(entries), synthesised=synthesised, cache_hits=hits, requests_sent=requests_sent, warnings=warnings,
    )


_schema_cache: dict[str, dict] = {}


def _schema(repo: Path) -> dict:
    from .verify import load_schema

    k = str(repo)
    if k not in _schema_cache:
        _schema_cache[k] = load_schema(repo)
    return _schema_cache[k]
