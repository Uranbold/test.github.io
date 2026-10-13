// @vitest-environment jsdom
// NAV-017 › State row (ended) as decided 2026-10-13 (UX audit UX-01 (2), PO; AC 35): the picker footer shows
// «Замчлал дууслаа» + «Буцаах» (`demo-undo`) in the `role="status"` live row; the button is named by its label and
// described by the row text; the retry button never shows in that state.
import { describe, expect, it, vi } from "vitest";
import { I18n } from "../i18n/i18n";
import { PickerView } from "./picker";
import type { DemoRouteSummary } from "./routeData";

const routes: DemoRouteSummary[] = [
  { id: "r1", label: "R1", mode: "car", origin: { name: "А", osm: null }, destination: { name: "Б", osm: null }, distance: 6_100, duration: 540, url: "demo-routes/r1.json" },
];

describe("picker undo row (UX-01 (2))", () => {
  it("shows the ended row with «Буцаах» and calls undo", () => {
    const i18n = new I18n("mn");
    const undo = vi.fn();
    const view = new PickerView(i18n, routes, { select() {}, retry() {}, start() {}, undo });
    document.body.append(view.section);
    const row = view.section.querySelector<HTMLElement>('[data-testid="demo-state"]')!;
    expect(row.hidden).toBe(true);
    view.setState("ended");
    expect(row.hidden).toBe(false);
    expect(row.dataset.state).toBe("ended");
    expect(row.querySelector("#demo-state-text")!.textContent).toBe("Замчлал дууслаа");
    expect(view.undoBtn.hidden).toBe(false);
    expect(view.undoBtn.textContent).toBe("Буцаах");
    expect(view.undoBtn.getAttribute("aria-describedby")).toBe("demo-state-text");
    expect(view.retryBtn.hidden).toBe(true);
    expect(row.parentElement!.getAttribute("role")).toBe("status");
    view.undoBtn.click();
    expect(undo).toHaveBeenCalledTimes(1);
    i18n.setLang("en");
    view.render();
    expect(row.querySelector("#demo-state-text")!.textContent).toBe("Navigation ended");
    expect(view.undoBtn.textContent).toBe("Undo");
    view.setState("none");
    expect(row.hidden).toBe(true);
    expect(view.undoBtn.hidden).toBe(true);
  });
});
