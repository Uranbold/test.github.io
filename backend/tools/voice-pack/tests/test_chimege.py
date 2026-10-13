"""B3: the Chimege client against tests/fake_chimege.py (NAV-016 AC 6, 8, 9, 11, 54). Never the real API."""

from __future__ import annotations

import contextlib
import os
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

from tests.fake_chimege import FakeChimege, Reply
from tests.helpers import SMALL_ENTRIES, build_argv, ffmpeg_args, make_repo, run_cli, scratch
from voicepack import build as build_mod, inventory, redact
from voicepack.errors import ConfigError, VendorError
from voicepack.synth import chimege
from voicepack.synth.chimege import ChimegeSynthesiser

SENTINEL = "SENTINEL-c0ffee-7f3a9b-token-value"
TOOL = Path(__file__).resolve().parents[1]


class _Sleeps:
    def __init__(self):
        self.waits: list[float] = []

    def __call__(self, s):
        self.waits.append(s)


def factory(sleeps=None, timeout=0.4, token=SENTINEL):
    def make(opts):
        return ChimegeSynthesiser(opts.api_base, env={"CHIMEGE_API_TOKEN": token}, timeout=timeout,
                                  sleep=sleeps if sleeps is not None else _Sleeps())
    return make


def expected_texts(repo: Path) -> list[str]:
    inv = inventory.load(repo)
    return sorted(e.spoken + (", дараа нь" if e.take == "nonFinal" else "") for e in inv.entries)


def fail_for(text_prefix: str, reply: Reply):
    """Responder: `reply` for every request whose text equals text_prefix, else 200."""
    def r(n, text, headers):
        return reply if text == text_prefix else Reply()
    return r


class ChimegeBuildTest(unittest.TestCase):
    def setUp(self):
        self.repo = make_repo(scratch() / "repo")
        self.out = scratch() / "out"
        self.cache = scratch() / "cache"

    def argv(self, fake, extra=None):
        return build_argv(self.repo, self.out, synth="chimege",
                          extra=["--api-base", fake.base, "--cache", str(self.cache), *(extra or [])])

    def assert_no_pack(self):
        self.assertFalse(list(self.out.rglob("manifest.json")), "no manifest may be written")
        self.assertFalse((self.out / "FEMALE5v2-v1").exists())
        self.assertEqual([p.name for p in self.out.iterdir()] if self.out.exists() else [], [], "no temporary directory left")

    def test_one_request_per_entry_headers_body_concurrency(self):
        with FakeChimege(default_delay=0.05) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 0, so + se)
        self.assertEqual(len(fake.requests), SMALL_ENTRIES)
        self.assertEqual(sorted(fake.texts()), expected_texts(self.repo))
        for r in fake.requests:
            self.assertEqual(r.path, "/v1.2/synthesize")
            self.assertEqual(r.headers["token"], SENTINEL)
            self.assertEqual(r.headers["voice-id"], "FEMALE5v2")
            self.assertEqual(r.headers["speed"], "1")
            self.assertEqual(r.headers["pitch"], "1")
            self.assertEqual(r.headers["sample-rate"], "22050")
            self.assertEqual(r.headers["content-type"], chimege.CONTENT_TYPE)
            self.assertEqual(chimege.CONTENT_TYPE, "plain/text")
        self.assertEqual(fake.max_in_flight, 2, "at most 2 in flight, and the pool uses both")
        self.assertIn(f"HTTP requests sent {SMALL_ENTRIES}", so)

    def test_only_inventory_text_and_carrier_are_sent(self):
        with FakeChimege() as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 0, se)
        spoken = {e.spoken for e in inventory.load(self.repo).entries}
        for t in fake.texts():
            self.assertTrue(t in spoken or (t.endswith(", дараа нь") and t[: -len(", дараа нь")] in spoken), t)

    def test_5xx_and_timeout_four_attempts_then_exit_4_and_no_pack(self):
        target = "буцаж эргэнэ үү"  # instr.uturn final
        for name, reply in (("500", Reply(500)), ("503", Reply(503)), ("502", Reply(502)), ("timeout", Reply(200, delay=1.2))):
            with self.subTest(name):
                self.out = scratch() / "out"
                sleeps = _Sleeps()
                with FakeChimege(fail_for(target, reply)) as fake:
                    rc, so, se = run_cli(self.argv(fake), synth_factory=factory(sleeps, timeout=0.3))
                self.assertEqual(rc, 4, so + se)
                self.assertEqual(fake.texts().count(target), 4)
                self.assertEqual(sleeps.waits, [1, 2, 4])
                self.assertIn("instr.uturn (final)", se)
                self.assertIn("after 4 attempts", se)
                self.assert_no_pack()

    def test_400_4005_one_attempt_names_key_and_meaning(self):
        target = "буцаж эргэнэ үү"
        with FakeChimege(fail_for(target, Reply(400, headers={"Error-Code": "4005"}))) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 4)
        self.assertEqual(fake.texts().count(target), 1)
        self.assertIn("instr.uturn (final)", se)
        self.assertIn("special characters", se)
        self.assert_no_pack()

    def test_401_one_attempt(self):
        target = "буцаж эргэнэ үү"
        with FakeChimege(fail_for(target, Reply(401))) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 4)
        self.assertEqual(fake.texts().count(target), 1)
        self.assert_no_pack()

    def test_403_aborts_with_token_message(self):
        with FakeChimege(lambda n, t, h: Reply(403, headers={"Error-Code": "1000"})) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 4)
        self.assertIn("CHIMEGE_API_TOKEN rejected (code 1000: invalid)", se)
        self.assertLessEqual(len(fake.requests), 2, "the run stops: only the requests already in flight")
        self.assert_no_pack()
        with FakeChimege(lambda n, t, h: Reply(403, headers={"Error-Code": "1001"})) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertIn("CHIMEGE_API_TOKEN rejected (code 1001: missing)", se)

    def test_bad_wav_on_200_fails_without_retry(self):
        from voicepack.audio import write_wav

        bad = write_wav([0, 1, 2, 3] * 1000, 44100)  # wrong rate
        target = "буцаж эргэнэ үү"
        with FakeChimege(fail_for(target, Reply(200, body=bad))) as fake:
            rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
        self.assertEqual(rc, 4)
        self.assertEqual(fake.texts().count(target), 1)
        self.assertIn("44100 Hz", se)

    def test_redirect_is_not_followed(self):
        with FakeChimege() as other:
            loc = other.base + "/synthesize"
            with FakeChimege(lambda n, t, h: Reply(307, headers={"Location": loc})) as fake:
                rc, so, se = run_cli(self.argv(fake), synth_factory=factory())
            self.assertEqual(rc, 4)
            self.assertEqual(len(other.requests), 0, "the token header must never follow a redirect")

    def test_missing_or_empty_token_exit_2_zero_requests(self):
        for value in (None, ""):
            with self.subTest(value=value):
                env = {k: v for k, v in os.environ.items() if k != "CHIMEGE_API_TOKEN"}
                if value is not None:
                    env["CHIMEGE_API_TOKEN"] = value
                with FakeChimege() as fake, mock.patch.dict(os.environ, env, clear=True):
                    rc, so, se = run_cli(self.argv(fake))  # default factory: os.environ
                self.assertEqual(rc, 2)
                self.assertIn("CHIMEGE_API_TOKEN is not set", se)
                self.assertEqual(len(fake.requests), 0)

    def test_api_base_loopback_only(self):
        for base in ("http://voice.invalid/v1.2", "https://api.example.invalid/v1.2", "ftp://127.0.0.1/x",
                     "http://user:pw@127.0.0.1:9/v1.2"):
            with self.subTest(base=base):
                with mock.patch.dict(os.environ, {"CHIMEGE_API_TOKEN": SENTINEL}):
                    rc, so, se = run_cli(build_argv(self.repo, self.out, synth="chimege",
                                                    extra=["--api-base", base, "--cache", str(self.cache)]))
                self.assertEqual(rc, 2)
                self.assertIn("loopback", se)
        for ok in ("http://127.0.0.1:1/v1.2", "http://[::1]:1/v1.2", "http://localhost:1/v1.2"):
            self.assertTrue(chimege.check_api_base(ok)[1])
        self.assertEqual(chimege.check_api_base(None), (chimege.API_BASE, False))


class RetryPolicyTest(unittest.TestCase):
    def synth(self, fake, sleeps):
        return ChimegeSynthesiser(fake.base, env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=0.4, sleep=sleeps)

    def test_429_retry_after_honoured(self):
        seq = {"n": 0}

        def r(n, t, h):
            seq["n"] += 1
            return Reply(429, headers={"Retry-After": "7"}) if n == 0 else Reply()

        sleeps = _Sleeps()
        with FakeChimege(r) as fake:
            self.synth(fake, sleeps).synthesize("дараа нь", "FEMALE5v2", 22050, label="join.then (final)")
        self.assertEqual(sleeps.waits, [7])
        self.assertEqual(len(fake.requests), 2)

    def test_429_without_retry_after_backoff_and_cap(self):
        sleeps = _Sleeps()
        with FakeChimege(lambda n, t, h: Reply(429) if n < 2 else Reply()) as fake:
            self.synth(fake, sleeps).synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(sleeps.waits, [1, 2])
        sleeps = _Sleeps()
        with FakeChimege(lambda n, t, h: Reply(503, headers={"Retry-After": "120"}) if n < 1 else Reply()) as fake:
            self.synth(fake, sleeps).synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(sleeps.waits, [60])

    def test_429_exhausted(self):
        sleeps = _Sleeps()
        with FakeChimege(lambda n, t, h: Reply(429)) as fake:
            with self.assertRaises(VendorError):
                self.synth(fake, sleeps).synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(len(fake.requests), 4)
        self.assertEqual(sleeps.waits, [1, 2, 4])

    def test_connection_error_retried(self):
        sleeps = _Sleeps()
        s = ChimegeSynthesiser("http://127.0.0.1:9/v1.2", env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=0.4, sleep=sleeps)
        with self.assertRaisesRegex(VendorError, "after 4 attempts"):
            s.synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(sleeps.waits, [1, 2, 4])

    def test_repr_has_no_token(self):
        s = ChimegeSynthesiser("http://127.0.0.1:9/v1.2", env={"CHIMEGE_API_TOKEN": SENTINEL})
        self.assertNotIn(SENTINEL, repr(s) + str(s) + repr(vars(s)))

    def test_missing_token_config_error(self):
        with self.assertRaisesRegex(ConfigError, "^CHIMEGE_API_TOKEN is not set$"):
            ChimegeSynthesiser(None, env={})


def _grep_tree(root: Path, needle: bytes) -> list[str]:
    hits = []
    if not root.exists():
        return hits
    for p in [root, *root.rglob("*")]:
        if needle in p.name.encode():
            hits.append(f"name {p}")
        if p.is_file() and needle in p.read_bytes():
            hits.append(f"content {p}")
    return hits


class SentinelTest(unittest.TestCase):
    """AC 9: the sentinel token appears 0 times in stdout, stderr, the log, exception texts, the manifest, the cache
    (index, file names, files), the review files and the pack directory; success, 401, 429, 500 and timeout."""

    SCENARIOS = {
        "success": (None, 0),
        "401": (Reply(401), 4),
        "429": ("429-once", 0),
        "500": (Reply(500), 4),
        "timeout": (Reply(200, delay=1.2), 4),
    }

    def run_scenario(self, name, redaction: bool):
        repo = make_repo(scratch() / "repo")
        out, cache, logdir = scratch() / "out", scratch() / "cache", scratch()
        log = logdir / "build.log"
        spec, want_rc = self.SCENARIOS[name]
        target = "буцаж эргэнэ үү"

        first_429 = set()

        def responder(n, t, h):
            if spec is None:
                return Reply()
            if spec == "429-once":
                if t == target and t not in first_429:
                    first_429.add(t)
                    return Reply(429, headers={"Retry-After": "1"})
                return Reply()
            return spec if t == target else Reply()

        argv = build_argv(repo, out, synth="chimege", extra=["--cache", str(cache), "--log", str(log)])
        patch = mock.patch.object(redact, "redact", lambda s: s) if not redaction else contextlib.nullcontext()
        with FakeChimege(responder) as fake, patch:
            argv += ["--api-base", fake.base]
            rc, so, se = run_cli(argv, synth_factory=factory(timeout=0.3))
            # the exception object itself (main prints only its text)
            exc_texts = ""
            if want_rc:
                from voicepack.build import BuildOptions
                try:
                    build_mod.run_build(
                        BuildOptions(voice="FEMALE5v2", pack_version=2, synth="chimege", out=out, repo=repo, cache=scratch() / "c2",
                                     api_base=fake.base, ffmpeg=ffmpeg_args()[1], ffmpeg_any_version=True, jobs=1),
                        redact.Reporter(None, quiet=True), synth_factory=factory(timeout=0.3))
                except Exception as e:  # noqa: BLE001
                    exc_texts = str(e) + repr(e) + repr(e.args)
                self.assertTrue(exc_texts, "the failing scenario must raise")
        self.assertEqual(rc, want_rc, so + se)
        self.assertTrue(fake.requests and all(r.headers["token"] == SENTINEL for r in fake.requests))
        needle = SENTINEL.encode()
        self.assertNotIn(SENTINEL, so)
        self.assertNotIn(SENTINEL, se)
        self.assertNotIn(SENTINEL, exc_texts)
        self.assertTrue(log.exists())
        self.assertNotIn(needle, log.read_bytes())
        self.assertEqual(_grep_tree(out, needle), [])
        self.assertEqual(_grep_tree(cache, needle), [])
        if want_rc == 0:
            self.assertTrue((out / "FEMALE5v2-v1" / "pack" / "manifest.json").exists())
            self.assertTrue((out / "FEMALE5v2-v1" / "review" / "index.html").exists())
            self.assertTrue((cache / "index.jsonl").exists())

    def test_sentinel_all_scenarios(self):
        for name in self.SCENARIOS:
            for redaction in (True, False):
                with self.subTest(name=name, redaction=redaction):
                    self.run_scenario(name, redaction)

    def test_sentinel_cli_subprocess_success(self):
        """The real CLI as a separate process, token only in its environment."""
        repo = make_repo(scratch() / "repo")
        out, cache, log = scratch() / "out", scratch() / "cache", scratch() / "build.log"
        with FakeChimege() as fake:
            env = {**os.environ, "CHIMEGE_API_TOKEN": SENTINEL, "PYTHONDONTWRITEBYTECODE": "1"}
            p = subprocess.run([sys.executable, "-m", "voicepack", *build_argv(repo, out, synth="chimege", extra=[
                "--api-base", fake.base, "--cache", str(cache), "--log", str(log)])], cwd=TOOL, env=env,
                capture_output=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr.decode())
        self.assertEqual(len(fake.requests), SMALL_ENTRIES)
        needle = SENTINEL.encode()
        self.assertNotIn(needle, p.stdout)
        self.assertNotIn(needle, p.stderr)
        self.assertNotIn(needle, log.read_bytes())
        self.assertEqual(_grep_tree(out, needle), [])
        self.assertEqual(_grep_tree(cache, needle), [])


if __name__ == "__main__":
    unittest.main()
