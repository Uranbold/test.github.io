// NAV-016 QA reproducing test (defect D-4, report docs/qa/reports/NAV-016-final-2026-10-13.md).
// Traces: NAV-016 AC 25 / AC 47 (Mongolian spoken text has 0 digits; the browser utterance receives the spoken form),
// ADR-0010 §2 and task breakdown M9 as decided in the 2026-10-13 integration review: "if any segment has no inventory
// entry (known case: walking catch-up >= 2,500 m ahead), the web must not speak. It must not fall back to the display
// text, which has digits."
//
// Run from web/ (uses the web toolchain; globals mode because this file lives outside web/):
//   cd web && npx vitest run --root .. --globals --no-cache tests/web/nav016/unmapped-prompt.test.ts
import { I18n, type Lang } from "../../../web/src/i18n/i18n";
import { maneuverKey } from "../../../web/src/route/instructions";
import { mongolianSegments, VOICE_INVENTORY, voicePrompt, type VoiceContent } from "../../../web/src/guidance/voiceText";

const t = (lang: Lang) => {
  const i = new I18n(lang);
  return (k: Parameters<I18n["t"]>[0]) => i.t(k, lang);
};
const turnLeftAt = (d: number): VoiceContent => ({ kind: "maneuver", key: maneuverKey({ type: "turn", modifier: "left" }), distanceM: d, then: null });

describe("NAV-016 D-4: an unmapped Mongolian prompt never speaks its display text (web)", () => {
  test("precondition: a manoeuvre 3,000 m ahead needs dist.km.3, which the inventory does not have", () => {
    const segs = mongolianSegments(turnLeftAt(3_000))!;
    expect(segs[0]!.key).toBe("dist.km.3");
    expect(VOICE_INVENTORY.spoken("dist.km.3", "final")).toBeUndefined();
  });

  test("the Mongolian spoken text of that prompt contains no digit (it must not be the display text)", () => {
    const p = voicePrompt(turnLeftAt(3_000), "mn", t("mn"))!;
    expect(p.display).toBe("3 километрт зүүн тийш эргэнэ үү");
    // Expected: no utterance text with a digit (the player chimes instead). Actual at 4a64023: «3 километрт …».
    expect(p.spoken, `spoken «${p.spoken}»`).not.toMatch(/[0-9]/);
  });
});
