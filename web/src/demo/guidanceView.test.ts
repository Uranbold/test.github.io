// @vitest-environment jsdom
// NAV-017 › Progress panel as decided 2026-10-13 (UX audit UX-02 (b), PO): line 1 = the remaining time, line 2 =
// «Хүрэх цаг HH:MM · distance» in two chunks; the accessible text reads in visual order; «Дуусгах» stays last (UX-01).
// Also UX-04: the banner's verb + particle span keeps textContent identical.
import { describe, expect, it, vi } from "vitest";
import type { GuidanceState } from "../guidance/guidanceCore";
import { I18n } from "../i18n/i18n";
import { GuidanceView } from "./guidanceView";

function state(): GuidanceState {
  return {
    phase: "navigating",
    banner: { variant: "maneuver", key: { key: "turn.left" }, distanceM: 300, street: "Их тойруу", step: 1 },
    progress: { distanceRemaining: 6_100, durationRemaining: 9 * 60 },
    puck: null,
    stepIndex: 0,
    voiceNoticeVisible: false,
    muted: false,
    speedMps: 10,
  };
}

describe("progress panel (UX-02 (b))", () => {
  it("leads with the remaining time, then «Хүрэх цаг HH:MM · distance» as two chunks, a11y in visual order", () => {
    vi.setSystemTime(new Date(2026, 9, 13, 14, 26));
    const i18n = new I18n("mn");
    const view = new GuidanceView(i18n, { recenter() {}, toggleVoice() {}, end() {}, close() {}, dismissNotice() {} });
    document.body.append(view.root);
    view.open({ text: "Зайсан", isData: true });
    view.render(state(), 0);
    const q = (id: string) => view.root.querySelector<HTMLElement>(`[data-testid="${id}"]`)!;
    const remaining = q("demo-nav-remaining");
    const eta = q("demo-nav-eta");
    expect(remaining.textContent).toBe("9 мин");
    expect(eta.textContent).toBe("Хүрэх цаг 14:35 · 6,1 км");
    const chunks = [...eta.querySelectorAll(".dn-chunk")].map((c) => c.textContent);
    expect(chunks).toEqual(["Хүрэх цаг 14:35 ·", " 6,1 км"]);
    // line 1 precedes line 2 in the DOM (= visual order), both decorative for AT; one sr-only text in visual order
    const text = q("demo-nav-progress").querySelector(".dn-progress-text")!;
    expect([...text.children].map((c) => c.getAttribute("data-testid"))).toEqual(["demo-nav-remaining", "demo-nav-eta", null]);
    expect(remaining.getAttribute("aria-hidden")).toBe("true");
    expect(eta.getAttribute("aria-hidden")).toBe("true");
    expect(text.querySelector(".sr-only")!.textContent).toBe("үлдсэн хугацаа 9 мин, Хүрэх цаг 14:35, үлдсэн зай 6,1 км");
    // «Дуусгах» is the last control of the panel (UX-01: 16 px from the voice button, demo.css)
    const panel = q("demo-nav-progress");
    expect(panel.lastElementChild).toBe(view.endBtn);
    expect(view.endBtn.previousElementSibling).toBe(view.voiceBtn);
    // UX-04: the banner instruction keeps its text and gets the no-wrap span
    expect(view.instruction.textContent).toBe("Зүүн тийш эргэнэ үү");
    expect(view.instruction.querySelector("span.nb")?.textContent).toBe("эргэнэ үү");
    vi.useRealTimers();
  });
});
