"""B10: repository scans for the generator (NAV-016 AC 8, AC 19). Standard library only; needs git and the checkout,
so `make voice-pack-test` runs this module on the host as well (the reference image has no git)."""

from __future__ import annotations

import re
import shutil
import subprocess
import unittest
from pathlib import Path

from voicepack import reposcan

TOOL = Path(__file__).resolve().parents[1]
REPO = TOOL.parents[2]
BACKEND = REPO / "backend"
TOKEN_VAR = reposcan.TOKEN_VAR
PIN = re.compile(r"^[a-z0-9._/:-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$")
HAVE_GIT = bool(shutil.which("git")) and (REPO / ".git").exists()


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True, timeout=60)


@unittest.skipUnless(HAVE_GIT, "needs git and the checkout")
class RepoScanTest(unittest.TestCase):
    def test_no_non_empty_token_value_anywhere(self):
        """AC 8 scan (task B10 definition, clarified 2026-10-13): tracked and untracked files."""
        self.assertEqual(reposcan.scan_token(REPO), [], "a non-empty token value is in a tracked or untracked file")

    def test_repo_wide_no_audio_pack_or_review_files(self):
        """AC 19 / D79 repo-wide (security VP-3): no audio, pack manifest or review page anywhere, tracked or
        untracked-and-not-ignored."""
        self.assertEqual(reposcan.scan_audio(REPO), [])

    def test_root_ignore_rules_cover_audio_outside_the_tool(self):
        for rel in ("mobile/android/app/src/main/assets/voicepack/clips/x.final.aac", "web/public/x.aac", "x.wav",
                    "docs/y.m4a"):
            self.assertEqual(git("check-ignore", "-q", rel).returncode, 0, rel)

    def test_env_example_keys(self):
        lines = (BACKEND / ".env.example").read_text(encoding="utf-8").splitlines()
        self.assertIn(TOKEN_VAR + "=", lines)
        self.assertIn("NAV_VOICE_PACK_OUT=", lines)
        self.assertIn("NAV_VOICE_PACK_CACHE=", lines)
        i = lines.index(TOKEN_VAR + "=")
        self.assertIn("never commit a value", lines[i - 1])
        self.assertIn("tools/voice-pack/README.md", lines[i - 1])

    def test_outputs_cache_audio_and_review_ignored(self):
        for rel in ("backend/tools/voice-pack/out/FEMALE5v2-v1/pack/manifest.json",
                    "backend/tools/voice-pack/cache/wav/x.wav",
                    "backend/tools/voice-pack/somewhere/review/index.html",
                    "backend/tools/voice-pack/x.aac", "backend/tools/voice-pack/a/b.m4a", "backend/tools/voice-pack/c.wav"):
            self.assertEqual(git("check-ignore", "-q", rel).returncode, 0, rel)
        for rel in ("backend/tools/voice-pack/voicepack/build.py", "backend/tools/voice-pack/coverage-prompts.tsv",
                    "backend/tools/voice-pack/README.md"):
            self.assertEqual(git("check-ignore", "-q", rel).returncode, 1, rel)

    def test_no_audio_or_pack_files_in_the_tool(self):
        tracked = git("ls-files", "backend/tools/voice-pack").stdout.split()
        untracked = git("ls-files", "--others", "--exclude-standard", "backend/tools/voice-pack").stdout.split()
        for f in tracked + untracked:
            self.assertNotRegex(f, r"\.(wav|m4a|aac|mp3|ogg|opus|flac)$", f)
            self.assertNotRegex(f, r"(^|/)review/|(^|/)manifest\.json$", f)


class TokenScanRuleTest(unittest.TestCase):
    """Fixtures for the AC 8 scan rules (task B10, incl. the QA Q-2 parameter-expansion sub-rule)."""

    N = TOKEN_VAR
    CASES = {
        # (line, is a finding)
        "{N}=": False, "{N}=abc": True, "{N}=\"abc\"": True, "{N}='abc'": True, "{N}: abc": True,
        "  {N} = abc": True, "{N}:\"abc\"": True, "{N}=.": False, "{N}=<value>": False, "{N}=SENTINEL-…": False,
        "{N}=$X": False, "{N}=${{X}}": False, "{N}=${{X:-}}": False, "{N}=${{X:-abc}}": True,
        "[[ -n \"${{{N}:-}}\" ]]": False, "${{{N}:?not set}}": False, "${{{N}-}}": False, "${{{N}:+x}}": False,
        "${{{N}}}": False, "${{{N}:-$OTHER}}": False, "${{{N}:-abc123}}": True, "${{{N}=abc}}": True,
        "${{{N}-abc}}": True, "${{{N}:=abc}}": True, "-e {N} -e HTTPS_PROXY": False, "`{N}`: the token": False,
        "{{\"{N}\": token}}": False,
    }

    def test_cases(self):
        for tmpl, finding in self.CASES.items():
            line = tmpl.format(N=self.N)
            with self.subTest(line=line):
                self.assertEqual(bool(reposcan.token_findings(line)), finding)


class SourceScanTest(unittest.TestCase):
    ALLOWED_HOSTS = {"api.chimege.com", "127.0.0.1", "localhost", "[::1]"}

    def _sources(self):
        for p in sorted(TOOL.rglob("*")):
            rel = p.relative_to(TOOL).parts
            if not p.is_file() or "__pycache__" in rel or any(x.startswith(".") for x in rel):
                continue  # caches and hidden tool state (e.g. .ruff_cache) are not sources
            if p.suffix in (".py", ".md", ".sh", ".tsv", ".txt") or p.name == "Dockerfile":
                yield p

    def test_no_hosts_or_ips_except_api_base_and_loopback(self):
        for p in self._sources():
            text = p.read_text(encoding="utf-8")
            for host in re.findall(r"[a-z][a-z0-9+.-]*://([^/\s\"'`<>)]+)", text):
                host = host.split("@")[-1]
                host = re.sub(r":\d+$|:\{.*$", "", host)
                if host.endswith(".invalid") or host in ("{api_base}", ""):
                    continue
                self.assertIn(host, self.ALLOWED_HOSTS, f"{p.relative_to(REPO)}: host {host}")
            for ip in re.findall(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", text):
                self.assertEqual(ip, "127.0.0.1", f"{p.relative_to(REPO)}: IP {ip}")

    def test_readme_only_the_public_api_base(self):
        text = (TOOL / "README.md").read_text(encoding="utf-8")
        hosts = set(re.findall(r"https?://([^/\s)]+)", text))
        self.assertEqual(hosts, {"api.chimege.com"})
        self.assertIn("https://api.chimege.com/v1.2", text)
        self.assertNotRegex(text, TOKEN_VAR + r"=\S", "no token value")
        self.assertNotIn("SENTINEL", text)
        self.assertIn("guaranteed only within the reference image", text)

    def test_dockerfile_pins(self):
        df = (TOOL / "Dockerfile").read_text(encoding="utf-8")
        args = dict(re.findall(r"^ARG (\w+_IMAGE)=(\S+)$", df, re.M))
        self.assertEqual(set(args), {"PYTHON_IMAGE", "FFMPEG_IMAGE"})
        for name, img in args.items():
            self.assertRegex(img, PIN, name)
        self.assertIn("python:3.11-slim", args["PYTHON_IMAGE"])
        self.assertIn(":7.0.2@", args["FFMPEG_IMAGE"])
        self.assertEqual(re.findall(r"^FROM (\S+)", df, re.M), ["${FFMPEG_IMAGE}", "${PYTHON_IMAGE}"])
        self.assertIn("--require-hashes", df)
        req = (TOOL / "requirements.txt").read_text(encoding="utf-8")
        for line in re.findall(r"^([A-Za-z0-9_.-]+)==", req, re.M):
            self.assertRegex(req, rf"{re.escape(line)}==[^\n]+\\\n\s+--hash=sha256:[0-9a-f]{{64}}", line)

    def test_content_type_is_one_constant(self):
        src = (TOOL / "voicepack" / "synth" / "chimege.py").read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'"plain/text"', src)), 1)
        others = [p for p in (TOOL / "voicepack").rglob("*.py") if p.name != "chimege.py" and "plain/text" in p.read_text(encoding="utf-8")]
        self.assertEqual(others, [])


if __name__ == "__main__":
    unittest.main()
