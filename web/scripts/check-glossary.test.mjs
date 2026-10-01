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

function run(mnValues, glossary) {
  const file = join(dir, `mn-${Math.random().toString(36).slice(2)}.json`);
  writeFileSync(file, JSON.stringify(mnValues));
  return spawnSync(process.execPath, [SCRIPT, "--mn", file, ...(glossary ? ["--glossary", glossary] : [])], { encoding: "utf8" });
}

describe("check-glossary", () => {
  it("passes for the real mn.json (every value)", () => {
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

  // NAV-004 AC 27/48: the glossary quotes these forms explicitly (verification round 0, D3), so they match exactly.
  it.each([
    "Тойрог: {n}-р гарц", // placeholder kept literally (AC 48)
    "Баруун талаас замд нийлнэ үү", // row "Merge", first letter case
    "Баруун хойд зүг рүү явна уу", // row "Head <direction> (start)"
    "Өмнө зүг рүү явна уу",
  ])("accepts the quoted glossary form «%s» exactly, with no derivation", (value) => {
    const r = run({ "maneuver.test": value });
    expect(r.status, r.stdout).toBe(0);
    expect(r.stdout).toMatch(/ok\s+maneuver\.test\s+«[^»]+»\s+<- glossary "/);
    expect(r.stdout).not.toMatch(/derived/);
  });

  // Derivation rules (placeholder → number, left/right mirror, cardinal set) are removed: a form the glossary does not
  // quote fails even when a pattern relative of it is approved.
  it.each([
    ["Тойрог: {n}-р гарц", "«Тойрог: 2-р гарц»", "n."],
    ["Баруун талаас замд нийлнэ үү", "«зүүн талаас замд нийлнэ үү»", "n."],
    ["Өмнө зүг рүү явна уу", "«хойд зүг рүү явна уу»", "Cardinal set: хойд, өмнө + «зүг»."],
  ])("rejects «%s» when the glossary only has %s (Notes: %s)", (value, approved, notes) => {
    const g = join(dir, `g-${Math.random().toString(36).slice(2)}.md`);
    writeFileSync(
      g,
      ["| English term | Approved Mongolian | Definition (English) | Notes | Status |", "|---|---|---|---|---|", `| Pattern | ${approved} | d. | ${notes} | s |`, ""].join("\n"),
    );
    const r = run({ "maneuver.test": value }, g);
    expect(r.status, r.stdout).toBe(1);
    expect(r.stdout).toMatch(/MISSING\s+maneuver\.test/);
  });

  // Same marker rule as NAV-002 AC 33 (TC-33-03): English option names are not Avoid markers; real markers still are.
  it("reads English \"Avoid …\" option names as names, not as markers", () => {
    const g = join(dir, `g-${Math.random().toString(36).slice(2)}.md`);
    writeFileSync(
      g,
      [
        "| English term | Approved Mongolian | Definition (English) | Notes | Status |",
        "|---|---|---|---|---|",
        "| Avoid unpaved roads | «Шороон замаас зайлсхийх» | Switch. | n. | s |",
        '| No route hint | «Хинт А» | Shown with «Маршрут олдсонгүй» when Avoid unpaved roads is on. Built from «тохиргоо» ("Avoid unpaved roads"). | n. | s |',
        "| Stop | «Зогсоол» | Drivers may misread it, so Avoid «Зогсоох». Like Avoid unpaved roads, but Avoid «Төлбөрийн зам». | n. | s |",
        "",
      ].join("\n"),
    );
    const r = run({ a: "Маршрут олдсонгүй", b: "Тохиргоо", c: "Зогсоох", d: "Төлбөрийн зам", e: "Шороон замаас зайлсхийх" }, g);
    expect(r.status, r.stdout).toBe(1);
    for (const k of ["a", "b", "e"]) expect(r.stdout).toMatch(new RegExp(`ok\\s+${k}\\s`));
    for (const k of ["c", "d"]) expect(r.stdout).toMatch(new RegExp(`MISSING\\s+${k}\\s`));
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
