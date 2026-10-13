"""B1: inventory builder, canonical bytes, hashes, text rules (NAV-016 AC 1-5, 7)."""

from __future__ import annotations

import re
import subprocess
import unittest

from tests.helpers import REPO, make_repo, run_cli, scratch
from voicepack import inventory, textrules
from voicepack.inventory import DISTANCE_TSV, INVENTORY_TSV


class InventoryTest(unittest.TestCase):
    def test_304_entries_265_final_39_nonfinal(self):
        inv = inventory.load(REPO)
        self.assertEqual(len(inv.entries), 304)
        self.assertEqual(inv.n_final, 265)
        self.assertEqual(inv.n_non_final, 39)

    def test_canonical_layout(self):
        inv = inventory.load(REPO)
        b = inv.canonical
        self.assertFalse(b.startswith(b"\xef\xbb\xbf"), "no BOM")
        self.assertNotIn(b"\r", b)
        self.assertTrue(b.endswith(b"\n"))
        lines = b.decode("utf-8").split("\n")[:-1]
        self.assertEqual(len(lines), 304)
        pairs = [tuple(ln.split("\t")[:2]) for ln in lines]
        self.assertEqual(pairs, sorted(pairs, key=lambda p: (p[0].encode(), p[1].encode())))
        self.assertEqual(len(set(pairs)), 304)
        for ln in lines:
            self.assertEqual(len(ln.split("\t")), 3)
        # final < nonFinal for the same key (ASCII order)
        i = pairs.index(("instr.turn.right", "final"))
        self.assertEqual(pairs[i + 1], ("instr.turn.right", "nonFinal"))
        # the GPS spoken form (D231)
        self.assertIn("fixed.gpsLost\tfinal\tжи пи эс дохио тасарлаа", lines)
        self.assertIn("fixed.gpsRestored\tfinal\tжи пи эс дохио сэргэлээ", lines)

    def test_two_runs_byte_identical(self):
        tmp = scratch()
        a, b = tmp / "a.tsv", tmp / "b.tsv"
        rc1, out1, _ = run_cli(["inventory", "--out", str(a)])
        rc2, out2, _ = run_cli(["inventory", "--out", str(b)])
        self.assertEqual((rc1, rc2), (0, 0))
        self.assertEqual(a.read_bytes(), b.read_bytes())
        self.assertEqual(out1, out2)
        inv = inventory.load(REPO)
        self.assertEqual(a.read_bytes(), inv.canonical)
        self.assertIn(f"inventorySha256 {inv.sha256}", out1)
        self.assertIn(f"glossaryRevision {inv.glossary_revision}", out1)

    def test_hash_equals_pack_format_value(self):
        """pack-format §2.3 prints the value for the inputs at commit 372e26e plus the current inventory.tsv."""
        doc = (REPO / "docs/architecture/voice-pack/pack-format.md").read_text(encoding="utf-8")
        m = re.search(r"\*Value at the time of writing\*.*?`([0-9a-f]{64})`", doc)
        self.assertIsNotNone(m, "pack-format §2.3 value not found")
        documented = m.group(1)
        inv_bytes = (REPO / INVENTORY_TSV).read_bytes()
        cur_tsv = (REPO / DISTANCE_TSV).read_bytes()
        try:
            old_tsv = subprocess.run(["git", "-C", str(REPO), "show", f"372e26e:{DISTANCE_TSV}"],
                                     capture_output=True, check=True, timeout=30).stdout
        except (OSError, subprocess.SubprocessError):
            old_tsv = None
        if old_tsv is not None:
            self.assertEqual(inventory.build(old_tsv, inv_bytes).sha256, documented)
        if old_tsv is None or old_tsv == cur_tsv:
            # the inputs are unchanged since the documented commit: the current hash must be the documented one
            self.assertEqual(inventory.load(REPO).sha256, documented)

    def test_one_character_changes_hash_status_column_does_not(self):
        tsv = (REPO / DISTANCE_TSV).read_bytes()
        inv = (REPO / INVENTORY_TSV).read_bytes()
        base = inventory.build(tsv, inv)
        # one character in the TSV spoken text
        t2 = tsv.replace("гурван зуун метрт".encode(), "гурван зуун метрд".encode(), 1)
        self.assertNotEqual(inventory.build(t2, inv).sha256, base.sha256)
        # one character in the inventory file spoken text
        i2 = inv.replace("баруун тийш эргэнэ үү".encode(), "баруун тийш эргэнэ уу".encode(), 1)
        self.assertNotEqual(inventory.build(tsv, i2).sha256, base.sha256)
        # status column only: inventorySha256 unchanged, glossaryRevision changed
        t3 = tsv.replace(b"needs native review", b"PO-approved 2026-10-13", 1)
        self.assertNotEqual(t3, tsv)
        b3 = inventory.build(t3, inv)
        self.assertEqual(b3.sha256, base.sha256)
        self.assertNotEqual(b3.glossary_revision, base.glossary_revision)
        # the source column of inventory.tsv is a human note: ignored too
        i3 = inv.replace(b"maneuver_turn_left", b"maneuver_turn_left (note)", 1)
        self.assertEqual(inventory.build(tsv, i3).sha256, base.sha256)

    def test_glossary_revision_is_sha_of_both_files(self):
        import hashlib

        tsv = (REPO / DISTANCE_TSV).read_bytes()
        inv = (REPO / INVENTORY_TSV).read_bytes()
        self.assertEqual(inventory.build(tsv, inv).glossary_revision, hashlib.sha256(tsv + inv).hexdigest())

    def test_no_rule_violation_on_current_inputs(self):
        inv = inventory.load(REPO)
        avoid = textrules.AvoidRules.from_repo(REPO)
        self.assertEqual(textrules.check_entries(inv.entries, avoid), [])
        # parser self-check: the D22 / glossary Avoid terms are read
        for t in ("навигаци", "Төвлөрүүлэх", "км/ц", "хоёр дахь", "карт"):
            self.assertIn(t, avoid.terms)


class _Counting:
    vendor, api_version, speed, pitch, max_in_flight = "chimege", "v1.2", "1", "1", 1

    def __init__(self):
        self.calls = 0

    def synthesize(self, *a, **k):
        self.calls += 1
        raise AssertionError("no request may be sent")


class TextRuleFixturesTest(unittest.TestCase):
    """Each rule has a failing fixture: exit 3, the key and the rule named, 0 requests (AC 7)."""

    CASES = [
        ("digit", "instr.uturn", "буцаж 2 эргэнэ үү"),
        ("latin", "instr.uturn", "буцаж u эргэнэ үү"),
        ("zero-width", "instr.uturn", "буцаж​эргэнэ үү"),
        ("charset", "instr.uturn", "Буцаж эргэнэ үү"),
        ("charset", "fixed.offRoute", "та маршрутаас гарлаа!"),
        ("length", "join.then", "д"),
        ("length", "fixed.offRoute", "а" * 301),
        ("nfc", "instr.uturn", "буцаж эргэнэ үү й"),
        ("avoid", "fixed.offRoute", "та карт харна уу"),
        ("avoid", "tail.approach", "очих газартаа ирнэ"),
    ]

    def _repo_with(self, key, text):
        def edit(s):
            out = []
            for ln in s.split("\n"):
                c = ln.split("\t")
                if c and c[0] == key:
                    c[2] = text
                    ln = "\t".join(c)
                out.append(ln)
            return "\n".join(out)

        return make_repo(scratch() / "repo", edit_inv=edit)

    def test_each_rule_fails_with_key_and_rule(self):
        for rule, key, text in self.CASES:
            with self.subTest(rule=rule, key=key):
                repo = self._repo_with(key, text)
                synth = _Counting()
                rc, out, err = run_cli(["build", "--voice", "FEMALE5v2", "--pack-version", "1", "--synth", "chimege",
                                        "--out", str(scratch() / "out"), "--repo", str(repo)],
                                       synth_factory=lambda o: synth)
                self.assertEqual(rc, 3, err)
                self.assertIn(key, err)
                self.assertIn(f"rule {rule}", err)
                self.assertIn("0 requests sent", err)
                self.assertEqual(synth.calls, 0)
                # inventory subcommand too
                rc2, _, err2 = run_cli(["inventory", "--repo", str(repo)])
                self.assertEqual(rc2, 3)
                self.assertIn(f"rule {rule}", err2)

    def test_request_text_length_with_carrier(self):
        e = inventory.Entry("instr.uturn", "nonFinal", "а " * 145 + "аа")  # 292 chars; + ", дараа нь" = 302
        v = textrules.check_entries([e], textrules.AvoidRules(()))
        self.assertEqual([x.rule for x in v], ["length"])

    def test_d22_patterns(self):
        a = textrules.AvoidRules(())
        self.assertEqual(a.hits("навигацийн"), ["навигаци"])
        self.assertEqual(a.hits("км/цаг"), [])
        self.assertEqual(a.hits("60 км/ц"), ["км/ц"])
        self.assertEqual(a.hits("хоёр дахь гарц"), ["<number> дахь/дэх"])
        self.assertEqual(a.hits("хотын төв дэх"), [])


if __name__ == "__main__":
    unittest.main()
