// check-glossary.mjs (NAV-002 AC 33) must accept only approved wording: banned terms quoted in the glossary's
// Notes (Avoid / Rejected / previous proposals) must fail, even though they appear in the file.
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, describe, expect, it } from "vitest";

const SCRIPT = fileURLToPath(new URL("./check-glossary.mjs", import.meta.url));
const MN = fileURLToPath(new URL("../src/i18n/mn.json", import.meta.url));
const GLOSSARY = fileURLToPath(new URL("../../docs/requirements/glossary.md", import.meta.url));
const dir = mkdtempSync(join(tmpdir(), "check-glossary-"));
afterAll(() => rmSync(dir, { recursive: true, force: true }));

function run(mnValues) {
  const file = join(dir, `mn-${Math.random().toString(36).slice(2)}.json`);
  writeFileSync(file, JSON.stringify(mnValues));
  return spawnSync(process.execPath, [SCRIPT, "--mn", file], { encoding: "utf8" });
}

describe("check-glossary", () => {
  it("passes for the real mn.json (25/25)", () => {
    const r = spawnSync(process.execPath, [SCRIPT], { encoding: "utf8" });
    expect(r.status, r.stdout + r.stderr).toBe(0);
    const n = Object.keys(JSON.parse(readFileSync(MN, "utf8"))).length;
    expect(r.stdout).toContain(`${n}/${n} mn values match`);
  });

  // Each is quoted in the glossary only as Avoid / Rejected / previous-proposal wording.
  const banned = ["навигаци", "Навигаци", "Төвлөрүүлэх", "км/ц", "хоёр дахь", "карт", "хувилбар маршрут", "Зогсоох", "траффик"];
  for (const value of banned) {
    it(`fails for the banned term «${value}»`, () => {
      const glossary = readFileSync(GLOSSARY, "utf8");
      expect(glossary.includes(`«${value.toLowerCase()}»`) || glossary.includes(`«${value}»`), "precondition: quoted in the glossary").toBe(true);
      const r = run({ "control.test": value });
      expect(r.status, r.stdout).toBe(1);
      expect(r.stdout).toMatch(/MISSING\s+control\.test/);
    });
  }

  // NAV-004 AC 27/48: derivation rules a–c (placeholder, left/right mirror, cardinal set) are reported as derived.
  it.each([
    ["Тойрог: {n}-р гарц", /derived: placeholder/],
    ["Баруун талаас замд нийлнэ үү", /derived: left\/right mirror/],
    ["Баруун хойд зүг рүү явна уу", /derived: .*cardinal set/],
    ["Өмнө зүг рүү явна уу", /derived: .*cardinal set/],
  ])("accepts the pattern form «%s» as derived", (value, how) => {
    const r = run({ "maneuver.test": value });
    expect(r.status, r.stdout).toBe(0);
    expect(r.stdout).toMatch(how);
  });

  it.each([
    "Тойрог: {n} дахь гарц", // placeholder over wording that is not approved
    "{n} дахь гарц",
    "Баруун эргэнэ үү", // bare left/right (C2); its mirror is not approved either
    "Зүүн эргэ",
    "Баруун руу чиглүүл",
    "Өмнөд зүг рүү явна уу", // «өмнөд» is not in the cardinal set
    "Зүүн хойд зүг рүү яв", // wrong register (C1)
    "Хойд тийш эргэнэ үү",
  ])("still rejects «%s»", (value) => {
    const r = run({ "maneuver.test": value });
    expect(r.status, r.stdout).toBe(1);
    expect(r.stdout).toMatch(/MISSING\s+maneuver\.test/);
  });

  it("still accepts an approved term next to a banned one and reports only the banned one", () => {
    const r = run({ "a.ok": "Дахин оролдох", "b.bad": "навигаци" });
    expect(r.status).toBe(1);
    expect(r.stdout).toMatch(/ok\s+a\.ok/);
    expect(r.stdout).toMatch(/MISSING\s+b\.bad/);
    expect(r.stdout).toContain("1/2 mn values match");
  });
});
