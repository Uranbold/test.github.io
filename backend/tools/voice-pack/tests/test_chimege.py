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


class ErrorCodeTest(unittest.TestCase):
    """ADR-0010 §4.1 tables; security VP-7 (Error-Code printed only when it is a short [0-9A-Za-z-] token)."""

    def synth(self, fake):
        return ChimegeSynthesiser(fake.base, env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=0.4, sleep=_Sleeps())

    def test_request_error_table_is_4000_to_4008(self):
        self.assertEqual(set(chimege.REQUEST_ERROR_CODES), {str(c) for c in range(4000, 4009)})
        self.assertIn("voice-id", chimege.REQUEST_ERROR_CODES["4003"])
        self.assertIn("special characters", chimege.REQUEST_ERROR_CODES["4005"])

    def fail_with(self, status, code) -> str:
        with FakeChimege(lambda n, t, h: Reply(status, headers={"Error-Code": code})) as fake:
            with self.assertRaises(VendorError) as cm:
                self.synth(fake).synthesize("дараа нь", "FEMALE5v2", 22050, label="join.then (final)")
        self.assertEqual(len(fake.requests), 1, "4xx fails at once")
        return str(cm.exception)

    def test_known_unknown_and_invalid_codes(self):
        self.assertIn("Error-Code 4003: the voice-id is not a valid voice", self.fail_with(400, "4003"))
        self.assertIn("Error-Code 4099: unknown Error-Code 4099", self.fail_with(400, "4099"))
        msg = self.fail_with(400, "4005 <b>x</b>")
        self.assertIn("invalid Error-Code", msg)
        self.assertNotIn("<b>", msg)
        msg = self.fail_with(403, "1000;rm")
        self.assertIn("CHIMEGE_API_TOKEN rejected (code invalid Error-Code: unknown)", msg)
        self.assertNotIn("rm", msg.replace("rejected", ""))
        self.assertIn("Error-Code " + "A" * 16 + ":", self.fail_with(400, "A" * 16))
        self.assertIn("invalid Error-Code", self.fail_with(400, "A" * 17))

    def test_tls_certificate_failure_fails_at_once(self):
        import ssl
        import urllib.error

        sleeps = _Sleeps()
        s = ChimegeSynthesiser("http://127.0.0.1:9/v1.2", env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=0.4, sleep=sleeps)
        err = urllib.error.URLError(ssl.SSLCertVerificationError(1, "certificate verify failed"))
        with mock.patch.object(s._opener, "open", side_effect=err) as op:
            with self.assertRaisesRegex(VendorError, "TLS certificate not trusted"):
                s.synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(op.call_count, 1)
        self.assertEqual(sleeps.waits, [])

    def test_default_https_context_is_verified(self):
        s = ChimegeSynthesiser(None, env={"CHIMEGE_API_TOKEN": SENTINEL})
        https = [h for h in s._opener.handlers if isinstance(h, chimege._HTTPSHandler)]
        self.assertEqual(len(https), 1)
        self.assertEqual(https[0]._context.verify_mode, __import__("ssl").CERT_REQUIRED)
        self.assertTrue(https[0]._context.check_hostname)


class DeadlineAndSizeCapTest(unittest.TestCase):
    """Security VP-2: one overall deadline per attempt and a response size cap, both a retryable timeout."""

    def test_slow_drip_body_hits_the_deadline_each_attempt(self):
        import time as _t

        sleeps = _Sleeps()
        with FakeChimege(lambda n, t, h: Reply(200, drip=0.05)) as fake:
            s = ChimegeSynthesiser(fake.base, env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=0.5, sleep=sleeps)
            t0 = _t.monotonic()
            with self.assertRaisesRegex(VendorError, r"after 4 attempts \(last: timeout"):
                s.synthesize("дараа нь", "FEMALE5v2", 22050)
            elapsed = _t.monotonic() - t0
        self.assertEqual(len(fake.requests), 4)
        self.assertEqual(sleeps.waits, [1, 2, 4])
        self.assertLess(elapsed, 4 * 0.5 + 2.0, "each attempt ends at its deadline, not when the drip stops")

    def test_response_over_the_cap_is_a_retryable_timeout(self):
        sleeps = _Sleeps()
        with FakeChimege() as fake:
            s = ChimegeSynthesiser(fake.base, env={"CHIMEGE_API_TOKEN": SENTINEL}, timeout=2, sleep=sleeps,
                                   max_response_bytes=1000)
            with self.assertRaisesRegex(VendorError, r"after 4 attempts \(last: timeout \(response over 1000 bytes\)"):
                s.synthesize("дараа нь", "FEMALE5v2", 22050)
        self.assertEqual(len(fake.requests), 4)
        self.assertEqual(chimege.MAX_RESPONSE_BYTES, 8 * 1024 * 1024)


class FakeServerMarkerTest(unittest.TestCase):
    """pack-format §8.3 (security VP-5): a run with --api-base writes source.vendor/apiVersion = stub and keys its
    cache with the vendor value fake."""

    def test_fake_server_pack_is_stub_and_rejected_in_demo(self):
        repo = make_repo(scratch() / "repo")
        out, cache = scratch() / "out", scratch() / "cache"
        with FakeChimege() as fake:
            rc, so, se = run_cli(build_argv(repo, out, synth="chimege", extra=["--api-base", fake.base, "--cache", str(cache)]),
                                 synth_factory=factory())
        self.assertEqual(rc, 0, so + se)
        import json

        m = json.loads((out / "FEMALE5v2-v1" / "pack" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(m["source"], {"vendor": "stub", "apiVersion": "stub", "voice": "FEMALE5v2"})
        rows = [json.loads(ln) for ln in (cache / "index.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual({r["vendor"] for r in rows}, {"fake"})
        rc, so, se = run_cli(["verify", str(out / "FEMALE5v2-v1" / "pack"), "--policy", "demo",
                              "--inventory-sha256", m["inventorySha256"]])
        self.assertEqual(rc, 1)
        self.assertIn("FAIL STUB_NOT_ALLOWED", so + se)

    def test_cache_keys_differ_between_fake_and_real(self):
        from voicepack.cache import cache_fields, cache_key

        fake = ChimegeSynthesiser("http://127.0.0.1:9/v1.2", env={"CHIMEGE_API_TOKEN": SENTINEL})
        real = ChimegeSynthesiser(None, env={"CHIMEGE_API_TOKEN": SENTINEL})
        self.assertEqual((fake.vendor, fake.api_version, fake.cache_vendor), ("stub", "stub", "fake"))
        self.assertEqual((real.vendor, real.api_version, real.cache_vendor), ("chimege", "v1.2", "chimege"))

        def key(s):
            return cache_key(cache_fields(vendor=s.cache_vendor, api_version=s.cache_api_version, voice="FEMALE5v2",
                                          sample_rate=22050, speed=s.speed, pitch=s.pitch, take_method="none",
                                          request_text="дараа нь"))

        self.assertNotEqual(key(fake), key(real))


class TokenScrubTest(unittest.TestCase):
    """Security VP-6: after the token is read it is gone from os.environ, and no child process gets it."""

    def test_synthesiser_removes_token_from_environ(self):
        with mock.patch.dict(os.environ, {"CHIMEGE_API_TOKEN": SENTINEL}):
            ChimegeSynthesiser("http://127.0.0.1:9/v1.2")
            self.assertNotIn("CHIMEGE_API_TOKEN", os.environ)

    def test_child_env_never_has_the_token(self):
        with mock.patch.dict(os.environ, {"CHIMEGE_API_TOKEN": SENTINEL}):
            self.assertNotIn("CHIMEGE_API_TOKEN", redact.child_env())

    def test_main_takes_the_token_before_anything_runs(self):
        seen = {}

        class Stop(Exception):
            pass

        def f(opts):
            seen["env"] = os.environ.get("CHIMEGE_API_TOKEN")
            raise Stop()

        repo = make_repo(scratch() / "repo")
        with mock.patch.dict(os.environ, {"CHIMEGE_API_TOKEN": SENTINEL}):
            rc, so, se = run_cli(build_argv(repo, scratch() / "out", synth="chimege",
                                            extra=["--cache", str(scratch() / "c")]), synth_factory=f)
        self.assertEqual(rc, 1)
        self.assertIsNone(seen["env"])
        self.assertNotIn(SENTINEL, so + se)


class RedactionLayerTest(unittest.TestCase):
    """Security VP-1: the second line of defence on its own (fails if redact.register() does nothing)."""

    def setUp(self):
        redact.clear()

    def test_main_redacts_an_exception_that_carries_the_token(self):
        repo = make_repo(scratch() / "repo")
        log = scratch() / "build.log"

        def leaky(opts):
            chimege.read_token({"CHIMEGE_API_TOKEN": SENTINEL})
            raise RuntimeError(f"boom token={SENTINEL}")

        rc, so, se = run_cli(build_argv(repo, scratch() / "out", synth="chimege",
                                        extra=["--cache", str(scratch() / "c"), "--log", str(log)]), synth_factory=leaky)
        self.assertEqual(rc, 1)
        text = so + se + log.read_text(encoding="utf-8")
        self.assertNotIn(SENTINEL, text)
        self.assertIn("boom token=[redacted]", se)
        self.assertIn("[redacted]", log.read_text(encoding="utf-8"))

    def test_redacting_stream(self):
        import io

        redact.register(SENTINEL)
        inner = io.StringIO()
        st = redact.RedactingStream(inner)
        self.assertEqual(st.write(f"a{SENTINEL}b\n"), len(f"a{SENTINEL}b\n"))
        self.assertEqual(inner.getvalue(), "a[redacted]b\n")

    def test_reporter_lines_and_log_fields(self):
        redact.register(SENTINEL)
        log = scratch() / "r.log"
        rep = redact.Reporter(str(log))
        out, err = __import__("io").StringIO(), __import__("io").StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rep.info(f"i {SENTINEL}", field=SENTINEL)
            rep.warn(f"w {SENTINEL}")
            rep.error(f"e {SENTINEL}", nested={"k": SENTINEL})
        rep.close()
        for text in (out.getvalue(), err.getvalue(), log.read_text(encoding="utf-8")):
            self.assertNotIn(SENTINEL, text)
            self.assertIn("[redacted]", text)

    def test_empty_value_is_never_registered(self):
        redact.register("")
        self.assertEqual(redact.redact("abc"), "abc")


class ProxySentinelTest(unittest.TestCase):
    """ADR-0010 §4.5 / task B3: a proxy URL's credential part is redacted like the token."""

    PROXY_SECRET = "SENTINEL-PROXY"

    def setUp(self):
        redact.clear()

    def test_proxy_credentials_never_reach_any_output(self):
        repo = make_repo(scratch() / "repo")
        proxy = f"http://user:{self.PROXY_SECRET}@127.0.0.1:9"
        needle = self.PROXY_SECRET.encode()
        with mock.patch.dict(os.environ, {"HTTPS_PROXY": proxy, "https_proxy": proxy}):
            # 1. success against the fake server (loopback never uses the proxy)
            out, cache, log = scratch() / "out", scratch() / "cache", scratch() / "b.log"
            with FakeChimege() as fake:
                rc, so, se = run_cli(build_argv(repo, out, synth="chimege", extra=[
                    "--api-base", fake.base, "--cache", str(cache), "--log", str(log)]), synth_factory=factory())
            self.assertEqual(rc, 0, se)
            for text in (so, se, log.read_text(encoding="utf-8")):
                self.assertNotIn(self.PROXY_SECRET, text)
            self.assertEqual(_grep_tree(out, needle), [])
            self.assertEqual(_grep_tree(cache, needle), [])

            # 2. an error text that carries the proxy URL is redacted on every stream
            log2 = scratch() / "b2.log"

            def leaky(opts):
                raise RuntimeError("proxy " + os.environ["HTTPS_PROXY"])

            rc, so, se = run_cli(build_argv(repo, scratch() / "o2", synth="chimege", extra=[
                "--cache", str(scratch() / "c2"), "--log", str(log2)]), synth_factory=leaky)
            self.assertEqual(rc, 1)
            for text in (so, se, log2.read_text(encoding="utf-8")):
                self.assertNotIn(self.PROXY_SECRET, text)
            self.assertIn("[redacted]@127.0.0.1:9", se)

    def test_user_only_credential(self):
        redact.register_proxy_credentials({"HTTPS_PROXY": "http://tok3n-value@127.0.0.1:3128"})
        self.assertEqual(redact.redact("x tok3n-value y"), "x [redacted] y")
        redact.register_proxy_credentials({"HTTPS_PROXY": "http://127.0.0.1:3128"})  # no credential: nothing to do


if __name__ == "__main__":
    unittest.main()
