"""CLI: python -m voicepack inventory | build | verify | render   (task B10)

Exit codes: 0 OK; 1 pack invalid (verify/render) or internal error; 2 configuration (token, path, ffmpeg, API base,
options); 3 inventory rule; 4 vendor; 5 audio check; 6 output.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from . import GENERATOR_VERSION, redact
from .cache import OUT_ENV, check_path_allowed
from .codec import BITRATES_KBPS, CLIP_RATES, Ffmpeg
from .errors import PackInvalid, VoicePackError
from .inventory import repo_root, sha256_hex
from .textrules import TAKE_METHODS
from .synth.chimege import TOKEN_ENV, ChimegeSynthesiser, take_token_from_environ
from .verify import ALLOWED_VOICES, POLICIES


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python -m voicepack", description="NAV-016 Mongolian voice clip pack generator (ADR-0010).")
    p.add_argument("--version", action="version", version=f"voicepack {GENERATOR_VERSION}")
    sub = p.add_subparsers(dest="cmd", required=True)

    def repo_arg(sp):
        sp.add_argument("--repo", type=Path, default=None, help="checkout to read the inventory inputs from (default: the one holding this tool)")

    def ff_args(sp):
        sp.add_argument("--ffmpeg", default=None, help="ffmpeg binary (default: $NAV_VOICE_PACK_FFMPEG, else ffmpeg on PATH)")
        sp.add_argument("--ffmpeg-any-version", action="store_true", help="accept an ffmpeg other than the pinned 7.0.2 (development only: no byte identity)")

    sp = sub.add_parser("inventory", help="write the canonical inventory and print inventorySha256 / glossaryRevision")
    sp.add_argument("--out", type=Path, default=None, help="file for the canonical bytes (pack-format §2.3)")
    sp.add_argument("--take-method", choices=TAKE_METHODS, default="carrier-then-v1")
    repo_arg(sp)

    sp = sub.add_parser("build", help="build one pack (+ review files) for one voice")
    sp.add_argument("--voice", required=True, choices=ALLOWED_VOICES)
    sp.add_argument("--pack-version", required=True, type=int)
    sp.add_argument("--synth", required=True, choices=("chimege", "stub"))
    sp.add_argument("--out", type=Path, default=os.environ.get(OUT_ENV) or None, help=f"output directory (default ${OUT_ENV})")
    sp.add_argument("--cache", type=Path, default=None, help="vendor WAV cache (default $NAV_VOICE_PACK_CACHE, else ~/.cache/navmn/voice-pack; stub: memory unless given)")
    sp.add_argument("--bitrate-kbps", type=int, choices=BITRATES_KBPS, default=24)
    sp.add_argument("--clip-rate", type=int, choices=CLIP_RATES, default=22050)
    sp.add_argument("--join-pause-ms", type=int, default=80)
    sp.add_argument("--then-pause-ms", type=int, default=80)
    sp.add_argument("--take-method", choices=TAKE_METHODS, default="carrier-then-v1")
    sp.add_argument("--prompts", type=Path, default=None, help="prompt list to render as well (Android longest-prompts.tsv)")
    sp.add_argument("--replace", action="store_true", help="replace an existing <voice>-v<n> output")
    sp.add_argument("--api-base", default=None, help="loopback test server only (127.0.0.1, ::1, localhost)")
    sp.add_argument("--cache-only", action="store_true",
                    help="--synth chimege from a complete cache: no token, no request (fails if any entry is missing)")
    sp.add_argument("--jobs", type=int, default=0, help="processing worker processes (default min(4, CPUs))")
    sp.add_argument("--log", default=None, help="append JSON-lines events to this file (redacted)")
    ff_args(sp)
    repo_arg(sp)

    sp = sub.add_parser("verify", help="schema + checks V1-V10 (pack-format §8) on a pack directory")
    sp.add_argument("dir", type=Path, help="the pack directory (<out>/<voice>-v<n>/pack)")
    sp.add_argument("--policy", choices=POLICIES, default="release")
    sp.add_argument("--inventory-sha256", default=None, help="expected value (default: computed from the checkout's inventory inputs)")
    repo_arg(sp)

    sp = sub.add_parser("render", help="render a prompt list from a pack (NAV-007 AC 10 harness)")
    sp.add_argument("--pack", type=Path, required=True)
    sp.add_argument("--prompts", type=Path, required=True)
    sp.add_argument("--out", type=Path, required=True)
    ff_args(sp)
    repo_arg(sp)
    return p


def _cmd_inventory(a, rep) -> int:
    from .build import preflight_inventory

    repo = a.repo or repo_root()
    inv = preflight_inventory(repo, a.take_method)
    if a.out:
        a.out.write_bytes(inv.canonical)
    rep.info(f"entries {len(inv.entries)} ({inv.n_final} final + {inv.n_non_final} nonFinal)")
    rep.info(f"inventorySha256 {inv.sha256}")
    rep.info(f"glossaryRevision {inv.glossary_revision}")
    return 0


def _cmd_build(a, rep, synth_factory=None) -> int:
    from .build import BuildOptions, run_build
    from .errors import ConfigError

    if a.out is None:
        raise ConfigError(f"--out is required (or set {OUT_ENV})")
    opts = BuildOptions(
        voice=a.voice, pack_version=a.pack_version, synth=a.synth, out=a.out, repo=a.repo or repo_root(), cache=a.cache,
        bitrate_kbps=a.bitrate_kbps, clip_rate=a.clip_rate, join_pause_ms=a.join_pause_ms, then_pause_ms=a.then_pause_ms,
        take_method=a.take_method, prompts=a.prompts, replace=a.replace, api_base=a.api_base, ffmpeg=a.ffmpeg,
        ffmpeg_any_version=a.ffmpeg_any_version, jobs=a.jobs, cache_only=a.cache_only,
    )
    r = run_build(opts, rep, synth_factory=synth_factory)
    sent = "n/a (stub)" if r.requests_sent is None else str(r.requests_sent) + (" (cache-only)" if opts.cache_only else "")
    rep.info(f"pack: {r.pack_dir}", entries=r.entries)
    rep.info(f"  voiceId {opts.voice}, packVersion {opts.pack_version}, manifest SHA-256 {r.manifest_sha256}",
             manifest_sha256=r.manifest_sha256, pack_version=opts.pack_version, voice=opts.voice)
    rep.info(f"  {r.entries} clips, pack size {r.pack_bytes} bytes ({r.pack_bytes / 1e6:.2f} MB)", pack_bytes=r.pack_bytes)
    rep.info(f"  synthesised {r.synthesised}, cache hits {r.cache_hits}, HTTP requests sent {sent}",
             synthesised=r.synthesised, cache_hits=r.cache_hits, requests_sent=r.requests_sent)
    rep.info(f"review: {r.run_dir / 'review' / 'index.html'}")
    return 0


def _cmd_verify(a, rep) -> int:
    from .build import _schema
    from .inventory import load

    repo = a.repo or repo_root()
    expected = a.inventory_sha256 or load(repo).sha256
    try:
        from .verify import verify_pack

        m = verify_pack(a.dir, policy=a.policy, expected_inventory_sha256=expected, schema=_schema(repo))
    except PackInvalid as x:
        rep.error(f"FAIL {x.reason}: {x.detail}", reason=x.reason)
        return x.exit_code
    msha = sha256_hex((a.dir / "manifest.json").read_bytes())
    rep.info(f"OK ({a.policy}): {len(m['entries'])} entries, voiceId {m['voiceId']}, packVersion {m['packVersion']}, "
             f"vendor {m['source']['vendor']}, manifest SHA-256 {msha}", manifest_sha256=msha)
    return 0


def _cmd_render(a, rep) -> int:
    from .build import _schema
    from .render import parse_prompt_list, render_prompts, write_prompts_tsv
    from .verify import verify_pack

    repo = a.repo or repo_root()
    out = check_path_allowed(a.out, repo, "--out")
    try:
        m = verify_pack(a.pack, policy="debug", schema=_schema(repo))
    except PackInvalid as x:
        rep.error(f"pack invalid: {x.reason}: {x.detail}", reason=x.reason)
        return x.exit_code
    ff = Ffmpeg(a.ffmpeg, allow_any_version=a.ffmpeg_any_version)
    prompts = parse_prompt_list(a.prompts.read_text(encoding="utf-8"), a.prompts.name)
    rows = render_prompts(a.pack, m, prompts, out, ff)
    write_prompts_tsv(out / "prompts.tsv", rows, ["id", "segments", "durationMs", "frames", "spoken", "file"])
    rep.info(f"rendered {sum(1 for r in rows if r['file'])} prompt(s) to {out}")
    return 0


def main(argv: list[str] | None = None, *, synth_factory=None) -> int:
    redact.install()
    # The token is read once and removed from os.environ before anything else runs, so no subprocess or worker
    # inherits it (security VP-6); proxy credentials are redacted like the token (ADR-0010 §4.5).
    token = take_token_from_environ()
    redact.register_proxy_credentials()
    a = _parser().parse_args(argv)
    rep = redact.Reporter(getattr(a, "log", None))
    try:
        if a.cmd == "inventory":
            return _cmd_inventory(a, rep)
        if a.cmd == "build":
            if synth_factory is None and a.synth == "chimege" and not a.cache_only:
                def synth_factory(opts, _env={TOKEN_ENV: token}):
                    return ChimegeSynthesiser(opts.api_base, env=_env)
            return _cmd_build(a, rep, synth_factory)
        if a.cmd == "verify":
            return _cmd_verify(a, rep)
        if a.cmd == "render":
            return _cmd_render(a, rep)
        return 2
    except VoicePackError as e:
        rep.error(str(e), exit_code=e.exit_code)
        return e.exit_code
    except KeyboardInterrupt:
        rep.error("interrupted")
        return 130
    except Exception as e:  # noqa: BLE001 - last resort: still redacted, never a raw traceback with values
        rep.error(f"internal error: {type(e).__name__}: {e}")
        return 1
    finally:
        rep.close()


if __name__ == "__main__":
    sys.exit(main())
