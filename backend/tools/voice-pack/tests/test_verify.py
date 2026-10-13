"""B9: `voicepack verify` gives the expected reason code for each NAV-016 AC 44 corruption (pack-format §8)."""

from __future__ import annotations

import json
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


if __name__ == "__main__":
    unittest.main()
