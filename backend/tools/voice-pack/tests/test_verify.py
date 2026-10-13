"""B9: `voicepack verify` gives the expected reason code for each NAV-016 AC 44 corruption (pack-format §8)."""

from __future__ import annotations

import json
import os
import shutil
import unittest
from pathlib import Path

from tests.helpers import run_cli, scratch, shared_full_pack


def copy_pack() -> Path:
    dst = scratch() / "pack"
    shutil.copytree(shared_full_pack() / "pack", dst)
    return dst


def edit_manifest(pack: Path, fn) -> None:
    p = pack / "manifest.json"
    m = json.loads(p.read_text(encoding="utf-8"))
    fn(m)
    p.write_text(json.dumps(m, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class VerifyTest(unittest.TestCase):
    def verify(self, pack: Path, policy: str = "debug", extra=None) -> tuple[int, str]:
        rc, so, se = run_cli(["verify", str(pack), "--policy", policy, *(extra or [])])
        return rc, so + se

    def expect(self, pack: Path, reason: str, policy: str = "debug", extra=None):
        rc, text = self.verify(pack, policy, extra)
        self.assertEqual(rc, 1, text)
        self.assertIn(f"FAIL {reason}:", text)

    def test_valid_stub_pack(self):
        rc, text = self.verify(shared_full_pack() / "pack", "debug")
        self.assertEqual(rc, 0, text)
        self.assertIn("OK (debug): 304 entries", text)

    # --- the six AC 44 corruptions
    def test_one_byte_changed(self):
        pack = copy_pack()
        f = next((pack / "clips").iterdir())
        b = bytearray(f.read_bytes())
        b[len(b) // 2] ^= 0x01
        f.write_bytes(bytes(b))
        self.expect(pack, "FILE_HASH_MISMATCH")

    def test_one_file_deleted(self):
        pack = copy_pack()
        next((pack / "clips").iterdir()).unlink()
        self.expect(pack, "FILE_MISSING")

    def test_manifest_truncated(self):
        pack = copy_pack()
        p = pack / "manifest.json"
        p.write_bytes(p.read_bytes()[: 1000])
        self.expect(pack, "MANIFEST_UNREADABLE")

    def test_inventory_sha_changed(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m.update(inventorySha256="0" * 64))
        self.expect(pack, "INVENTORY_MISMATCH")

    def test_schema_version_99(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m.update(schemaVersion=99))
        self.expect(pack, "SCHEMA_UNSUPPORTED")

    def test_stub_in_release_and_demo(self):
        for policy in ("release", "demo"):
            self.expect(shared_full_pack() / "pack", "STUB_NOT_ALLOWED", policy)

    # --- the other checks
    def test_no_manifest(self):
        pack = copy_pack()
        (pack / "manifest.json").unlink()
        self.expect(pack, "NO_PACK")

    def test_voice_not_allowed(self):
        pack = copy_pack()

        def fn(m):
            m["source"]["vendor"] = "chimege"
            m["voiceId"] = "FEMALE3v2"

        edit_manifest(pack, fn)
        self.expect(pack, "VOICE_NOT_ALLOWED", "release")

    def test_codec_unsupported(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m["codec"].update(container="mp4"))
        self.expect(pack, "CODEC_UNSUPPORTED")

    def test_schema_type_error(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m.update(packVersion="one"))
        self.expect(pack, "MANIFEST_UNREADABLE")

    def test_entry_invalid(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m["entries"][3].update(durationMs=m["entries"][3]["durationMs"] + 1))
        self.expect(pack, "ENTRY_INVALID")
        pack = copy_pack()
        edit_manifest(pack, lambda m: m["entries"][3].update(frames=m["entries"][3]["encodedFrames"]))
        self.expect(pack, "ENTRY_INVALID")

    def test_file_path_escape_is_entry_invalid(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m["entries"][0].update(file="clips/../manifest.json"))
        self.expect(pack, "ENTRY_INVALID")

    def test_entries_unsorted(self):
        pack = copy_pack()
        edit_manifest(pack, lambda m: m["entries"].reverse())
        self.expect(pack, "INVENTORY_MISMATCH")

    def test_expected_inventory_differs(self):
        self.expect(shared_full_pack() / "pack", "INVENTORY_MISMATCH", extra=["--inventory-sha256", "f" * 64])

    def test_unlisted_file(self):
        pack = copy_pack()
        (pack / "clips" / "extra.aac").write_bytes(b"x")
        self.expect(pack, "UNLISTED_FILE")


def edit_raw(pack: Path, fn) -> None:
    p = pack / "manifest.json"
    p.write_text(fn(p.read_text(encoding="utf-8")), encoding="utf-8")


class FaultOwnershipTest(unittest.TestCase):
    """pack-format §8.1: the same code as the Gradle guard and the app for each single-fault corruption, plus the
    two-fault case (architect integration review defects 3 and 4; QA D-1, D-2)."""

    def expect(self, pack: Path, reason: str):
        rc, so, se = run_cli(["verify", str(pack), "--policy", "debug"])
        self.assertEqual(rc, 1, so + se)
        self.assertIn(f"FAIL {reason}:", so + se)
        self.assertNotIn("\n\n", se, "a value with a newline must not break the report line")

    def test_raw_cases(self):
        cases = [
            ("top-level array", lambda t: "[" + t.rstrip("\n") + "]\n", "MANIFEST_UNREADABLE"),
            ("top-level string", lambda t: '"x"\n', "MANIFEST_UNREADABLE"),
            ("top-level number", lambda t: "1\n", "MANIFEST_UNREADABLE"),
            ("schemaVersion 1.0", lambda t: t.replace('"schemaVersion": 1,', '"schemaVersion": 1.0,', 1), "SCHEMA_UNSUPPORTED"),
            ("schemaVersion true", lambda t: t.replace('"schemaVersion": 1,', '"schemaVersion": true,', 1), "SCHEMA_UNSUPPORTED"),
            ("schemaVersion string", lambda t: t.replace('"schemaVersion": 1,', '"schemaVersion": "1",', 1), "SCHEMA_UNSUPPORTED"),
            ("packVersion 1.0", lambda t: t.replace('"packVersion": 1,', '"packVersion": 1.0,', 1), "MANIFEST_UNREADABLE"),
            ("bitrate 2.4e1", lambda t: t.replace('"bitrateKbps": 24,', '"bitrateKbps": 2.4e1,', 1), "MANIFEST_UNREADABLE"),
            ("joinPauseMs 80.0", lambda t: t.replace('"joinPauseMs": 80,', '"joinPauseMs": 80.0,', 1), "MANIFEST_UNREADABLE"),
            ("sourceSampleRateHz 22050.0", lambda t: t.replace('"sourceSampleRateHz": 22050,', '"sourceSampleRateHz": 22050.0,', 1), "MANIFEST_UNREADABLE"),
            ("codec.sampleRateHz 22050.0", lambda t: t.replace('"sampleRateHz": 22050,\n    "channels"', '"sampleRateHz": 22050.0,\n    "channels"', 1), "CODEC_UNSUPPORTED"),
            ("codec.channels 1.0", lambda t: t.replace('"channels": 1,', '"channels": 1.0,', 1), "CODEC_UNSUPPORTED"),
            ("codec.channels true", lambda t: t.replace('"channels": 1,', '"channels": true,', 1), "CODEC_UNSUPPORTED"),
        ]
        for name, fn, reason in cases:
            with self.subTest(name):
                pack = copy_pack()
                before = (pack / "manifest.json").read_text(encoding="utf-8")
                edit_raw(pack, fn)
                self.assertNotEqual(before, (pack / "manifest.json").read_text(encoding="utf-8"), "fixture applied")
                self.expect(pack, reason)

    def test_edit_cases(self):
        def two_fault(m):
            m["codec"]["bitrateKbps"] = 999
            m["codec"]["name"] = "mp3"

        cases = [
            ("voiceId trailing newline", lambda m: m.update(voiceId=m["voiceId"] + "\n"), "MANIFEST_UNREADABLE"),
            ("spokenText control character", lambda m: m["entries"][0].update(spokenText=m["entries"][0]["spokenText"] + "\u0007"), "MANIFEST_UNREADABLE"),
            ("file trailing newline", lambda m: m["entries"][0].update(file=m["entries"][0]["file"] + "\n"), "MANIFEST_UNREADABLE"),
            ("codec.name trailing newline", lambda m: m["codec"].update(name="aac-lc\n"), "MANIFEST_UNREADABLE"),
            ("sha256 trailing newline", lambda m: m["entries"][0].update(sha256=m["entries"][0]["sha256"] + "\n"), "MANIFEST_UNREADABLE"),
            ("bitrate out of range", lambda m: m["codec"].update(bitrateKbps=999), "MANIFEST_UNREADABLE"),
            ("codec.name", lambda m: m["codec"].update(name="mp3"), "CODEC_UNSUPPORTED"),
            ("two faults: bitrate and name", two_fault, "MANIFEST_UNREADABLE"),
            ("codec member missing", lambda m: m["codec"].pop("encoder"), "MANIFEST_UNREADABLE"),
            ("codec.sampleRateHz string", lambda m: m["codec"].update(sampleRateHz="22050"), "CODEC_UNSUPPORTED"),
            ("extra top-level member", lambda m: m.update(builtAt="x"), "MANIFEST_UNREADABLE"),
            ("vendor unknown", lambda m: m["source"].update(vendor="other"), "MANIFEST_UNREADABLE"),
            ("file not a string", lambda m: m["entries"][0].update(file=7), "MANIFEST_UNREADABLE"),
            ("file escapes", lambda m: m["entries"][0].update(file="../x.aac"), "ENTRY_INVALID"),
        ]
        for name, fn, reason in cases:
            with self.subTest(name):
                pack = copy_pack()
                edit_manifest(pack, fn)
                self.expect(pack, reason)


def _resha(pack: Path, rel: str) -> None:
    import hashlib

    data = (pack / rel).read_bytes()
    edit_manifest(pack, lambda m: next(e for e in m["entries"] if e["file"] == rel).update(sha256=hashlib.sha256(data).hexdigest()))


class FileRulesTest(unittest.TestCase):
    """pack-format §8.2 (security VP-4): no-follow checks, real path inside the pack, size caps. Links are made here,
    at run time, never committed."""

    def expect(self, pack: Path, reason: str):
        rc, so, se = run_cli(["verify", str(pack), "--policy", "debug"])
        self.assertEqual(rc, 1, so + se)
        self.assertIn(f"FAIL {reason}:", so + se)

    def first_clip(self, pack: Path) -> str:
        return json.loads((pack / "manifest.json").read_text(encoding="utf-8"))["entries"][0]["file"]

    def test_manifest_symlink(self):
        pack = copy_pack()
        real = pack.parent / "elsewhere.json"
        (pack / "manifest.json").rename(real)
        (pack / "manifest.json").symlink_to(real)
        self.expect(pack, "MANIFEST_UNREADABLE")

    def test_manifest_dangling_symlink_is_present_but_unreadable(self):
        pack = copy_pack()
        (pack / "manifest.json").unlink()
        (pack / "manifest.json").symlink_to(pack.parent / "missing.json")
        self.expect(pack, "MANIFEST_UNREADABLE")

    def test_manifest_over_1_mib(self):
        pack = copy_pack()
        p = pack / "manifest.json"
        p.write_bytes(p.read_bytes() + b" " * (1_048_576 + 1 - p.stat().st_size))
        self.expect(pack, "MANIFEST_UNREADABLE")

    def test_manifest_exactly_1_mib_is_read(self):
        pack = copy_pack()
        p = pack / "manifest.json"
        p.write_bytes(p.read_bytes() + b" " * (1_048_576 - p.stat().st_size))
        rc, so, se = run_cli(["verify", str(pack), "--policy", "debug"])
        self.assertEqual(rc, 0, so + se)

    def test_clip_symlink_outside_and_inside(self):
        for where in ("outside", "inside"):
            with self.subTest(where):
                pack = copy_pack()
                rel = self.first_clip(pack)
                target = (pack.parent / "decoy.aac") if where == "outside" else (pack / "clips" / "decoy.aac")
                (pack / rel).rename(target)
                (pack / rel).symlink_to(target)
                self.expect(pack, "FILE_INVALID")

    def test_clips_directory_symlink(self):
        pack = copy_pack()
        (pack / "clips").rename(pack.parent / "clips-real")
        (pack / "clips").symlink_to(pack.parent / "clips-real", target_is_directory=True)
        self.expect(pack, "FILE_INVALID")

    def test_dangling_symlink_clip(self):
        pack = copy_pack()
        rel = self.first_clip(pack)
        (pack / rel).unlink()
        (pack / rel).symlink_to(pack.parent / "nothing.aac")
        self.expect(pack, "FILE_INVALID")

    def test_clip_over_256_kib(self):
        pack = copy_pack()
        rel = self.first_clip(pack)
        (pack / rel).write_bytes(b"\xff" * 262_145)
        _resha(pack, rel)
        self.expect(pack, "FILE_INVALID")

    def test_pack_total_over_6_mib(self):
        pack = copy_pack()
        m = json.loads((pack / "manifest.json").read_text(encoding="utf-8"))
        for e in m["entries"][:26]:
            (pack / e["file"]).write_bytes(b"\xff" * 250_000)  # each under the 256 KiB cap, 6.5 MB together
        self.expect(pack, "FILE_INVALID")

    def test_fifo_clip(self):
        pack = copy_pack()
        rel = self.first_clip(pack)
        (pack / rel).unlink()
        os.mkfifo(pack / rel)
        self.expect(pack, "FILE_INVALID")

    def test_unlisted_symlink_is_g1(self):
        pack = copy_pack()
        (pack / "clips" / "extra.final.aac").symlink_to(pack / "manifest.json")
        self.expect(pack, "UNLISTED_FILE")
        pack = copy_pack()
        (pack / "more").symlink_to(pack / "clips", target_is_directory=True)
        self.expect(pack, "UNLISTED_FILE")

    def test_clips_absent_or_empty_is_file_missing(self):
        pack = copy_pack()
        shutil.rmtree(pack / "clips")
        self.expect(pack, "FILE_MISSING")
        pack = copy_pack()
        for f in (pack / "clips").iterdir():
            f.unlink()
        self.expect(pack, "FILE_MISSING")

    def test_size_change_between_v9_and_v10_is_file_invalid(self):
        """§8.2: V10 reads at most the V9 size + 1; a different count (grew, shrank, vanished) is V9 FILE_INVALID."""
        from unittest import mock

        from voicepack import verify as verify_mod

        real = verify_mod._read_bounded
        for name, fake in (("grew", lambda p, n: real(p, n) + b"x"),
                           ("shrank", lambda p, n: real(p, n)[:-1]),
                           ("vanished", lambda p, n: (_ for _ in ()).throw(FileNotFoundError(2, "gone")))):
            with self.subTest(name):
                pack = copy_pack()
                with mock.patch.object(verify_mod, "_read_bounded", side_effect=fake) as rb:
                    self.expect(pack, "FILE_INVALID")
                self.assertEqual(rb.call_args_list[0].args[1], (pack / self.first_clip(pack)).stat().st_size + 1)

    def test_pack_directory_itself_through_a_link_is_fine(self):
        pack = copy_pack()
        link = pack.parent / "via-link"
        link.symlink_to(pack, target_is_directory=True)
        rc, so, se = run_cli(["verify", str(link), "--policy", "debug"])
        self.assertEqual(rc, 0, so + se)


if __name__ == "__main__":
    unittest.main()
