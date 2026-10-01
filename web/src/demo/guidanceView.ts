// D2 guidance and D3 arrival (screen spec NAV-017 › Layout, Regions, Components): banner (RB), demo row with the badge
// and the moved NAV-002 language and theme buttons (RD), recenter, one message stack (RN: A1 and offline), progress or
// arrival panel (RP), and a polite live region for new instructions. One DOM tree for every state, theme, language and
// orientation (layout rule 10): CSS re-arranges the regions. Pure view: text in, events out.
import type { I18n, MessageKey } from "../i18n/i18n";
import { computeEta, formatDistance, formatDuration, formatNextDay } from "../route/format";
import { capitalizeFirst, messageKey } from "../route/instructions";
import type { Banner, GuidanceState } from "../guidance/guidanceCore";
import { ICONS } from "../ui/icons";
import { maneuverIcon } from "../ui/routeIcons";
import { DEMO_ICONS, h, icon } from "./dom";

export interface GuidanceHandlers {
  recenter(): void;
  toggleVoice(): void;
  end(): void;
  close(): void;
  dismissNotice(): void;
}

/** Banner instruction text (NAV-004 AC 27, ADR-0008): from the manoeuvre key only, never Valhalla text. */
export function bannerText(b: Banner, i18n: I18n): string {
  let text = i18n.t(messageKey(b.key.key));
  if (b.key.params) text = text.replace("{n}", String(b.key.params.n));
  return capitalizeFirst(text, i18n.lang);
}

export class GuidanceView {
  readonly root: HTMLElement;
  readonly top: HTMLElement;
  readonly bottom: HTMLElement;
  private readonly banner: HTMLElement;
  private readonly bannerIcon: HTMLElement;
  private readonly distance: HTMLElement;
  readonly instruction: HTMLElement;
  private readonly street: HTMLElement;
  readonly row: HTMLElement;
  private readonly badgeText: HTMLElement;
  readonly recenterBtn: HTMLButtonElement;
  private readonly recenterLabel: HTMLElement;
  private readonly messages: HTMLElement;
  private readonly noticeItem: HTMLElement;
  private readonly noticeBtn: HTMLButtonElement;
  private readonly noticeText: HTMLElement;
  private readonly offlineItem: HTMLElement;
  private readonly offlineText: HTMLElement;
  private readonly progress: HTMLElement;
  private readonly eta: HTMLElement;
  private readonly etaDays: HTMLElement;
  private readonly remaining: HTMLElement;
  private readonly progressA11y: HTMLElement;
  readonly voiceBtn: HTMLButtonElement;
  readonly endBtn: HTMLButtonElement;
  readonly arrival: HTMLElement;
  private readonly arrivalName: HTMLElement;
  readonly closeBtn: HTMLButtonElement;
  private readonly live: HTMLElement;
  private lastAnnouncedStep: number | null = null;
  private lastState: GuidanceState | null = null;
  private destination = { text: "", isData: true };
  private offline = false;
  private progressAt = 0;

  constructor(
    private readonly i18n: I18n,
    private readonly on: GuidanceHandlers,
  ) {
    this.bannerIcon = h("span", { class: "dn-icon", "aria-hidden": "true" });
    this.distance = h("p", { class: "dn-distance", "data-testid": "demo-nav-distance" });
    this.instruction = h("p", { class: "dn-instruction", "data-testid": "demo-nav-text", tabindex: "-1" });
    this.street = h("p", { class: "dn-street", "data-testid": "demo-nav-street" });
    this.banner = h("section", { class: "dn-banner", "data-testid": "demo-nav-banner", "data-variant": "maneuver" }, this.bannerIcon, h("div", { class: "dn-text" }, this.distance, this.instruction, this.street));

    this.badgeText = h("span", {});
    this.row = h("div", { class: "dn-row" }, h("span", { class: "dn-badge", "data-testid": "demo-badge" }, icon(DEMO_ICONS.flask, "dn-badge-icon"), this.badgeText), h("span", { class: "dn-row-buttons" }));
    this.top = h("div", { class: "dn-top" }, this.banner, this.row);

    this.recenterLabel = h("span", {});
    this.recenterBtn = h("button", { type: "button", class: "dn-recenter", "data-testid": "demo-nav-recenter", hidden: "" }, icon(DEMO_ICONS.myLocation), this.recenterLabel);
    this.recenterBtn.addEventListener("click", () => this.on.recenter());

    this.noticeText = h("span", { class: "txt" });
    this.noticeBtn = h("button", { type: "button", class: "dn-msg-btn" }, icon(DEMO_ICONS.info), this.noticeText);
    this.noticeBtn.addEventListener("click", () => this.on.dismissNotice());
    this.noticeItem = h("div", { class: "dn-msg", role: "status", "data-kind": "voice-unavailable", hidden: "" }, this.noticeBtn);
    this.offlineText = h("span", { class: "txt" });
    this.offlineItem = h("div", { class: "dn-msg", role: "status", "data-kind": "offline", hidden: "" }, h("span", { class: "dn-msg-row" }, icon(ICONS.cloudOff), this.offlineText));
    this.messages = h("div", { class: "dn-messages", "data-testid": "demo-nav-messages", hidden: "" }, this.noticeItem, this.offlineItem);

    this.eta = h("span", {});
    this.etaDays = h("span", { class: "dn-days" });
    this.remaining = h("p", { class: "dn-remaining", "data-testid": "demo-nav-remaining", "aria-hidden": "true" });
    this.progressA11y = h("span", { class: "sr-only" });
    const etaLine = h("p", { class: "dn-eta", "data-testid": "demo-nav-eta", "aria-hidden": "true" }, this.eta, this.etaDays);
    this.voiceBtn = h("button", { type: "button", class: "dn-voice", "data-testid": "demo-nav-voice" });
    this.voiceBtn.addEventListener("click", () => this.on.toggleVoice());
    this.endBtn = h("button", { type: "button", class: "dn-end", "data-testid": "demo-nav-end" }, icon(DEMO_ICONS.close));
    this.endBtn.addEventListener("click", () => this.on.end());
    this.progress = h("section", { class: "dn-progress", "data-testid": "demo-nav-progress" }, h("div", { class: "dn-progress-text" }, etaLine, this.remaining, this.progressA11y), this.voiceBtn, this.endBtn);

    this.arrivalName = h("p", { class: "dn-arrival-name" });
    this.closeBtn = h("button", { type: "button", class: "dn-close", "data-testid": "demo-nav-close" });
    this.closeBtn.addEventListener("click", () => this.on.close());
    this.arrival = h("section", { class: "dn-arrival", "data-testid": "demo-nav-arrival", hidden: "" }, icon(DEMO_ICONS.flag, "dn-flag"), this.arrivalName, this.closeBtn);

    this.bottom = h("div", { class: "dn-bottom" }, this.messages, this.progress, this.arrival);
    this.live = h("div", { class: "sr-only", "aria-live": "polite", "data-testid": "demo-nav-live" });
    const band = h("div", { class: "dn-band" }, this.recenterBtn);
    // DOM order = Tab order (screen spec › Accessibility): banner, badge, language, theme, recenter, messages, panel.
    this.root = h("div", { class: "demo-nav", id: "demo-nav", "data-testid": "demo-nav", hidden: "" }, this.top, band, this.bottom, this.live);
  }

  /** Slot in the demo row for the NAV-002 language and theme buttons (moved there during the replay). */
  get rowButtons(): HTMLElement {
    return this.row.querySelector(".dn-row-buttons") as HTMLElement;
  }

  open(destination: { text: string; isData: boolean }): void {
    this.destination = destination;
    this.lastAnnouncedStep = null;
    this.lastState = null;
    this.progressAt = 0;
    this.live.textContent = "";
    this.root.hidden = false;
    this.arrival.hidden = true;
    this.progress.hidden = false;
    this.recenterBtn.hidden = true;
    this.renderStatic();
  }

  close_(): void {
    this.root.hidden = true;
  }

  setOffline(offline: boolean): void {
    this.offline = offline;
    this.renderMessages(this.lastState);
  }

  setFollowing(following: boolean): void {
    this.recenterBtn.hidden = following || this.lastState?.phase === "arrived";
  }

  /** Renders a core state. `force` re-renders the progress text (language switch). */
  render(s: GuidanceState, now: number, force = false): void {
    const prev = this.lastState;
    this.lastState = s;
    const b = s.banner;
    if (!prev || prev.banner.key.key !== b.key.key || prev.banner.variant !== b.variant || force) this.bannerIcon.innerHTML = maneuverIcon(b.key.key);
    this.banner.dataset.variant = b.variant;
    const text = bannerText(b, this.i18n);
    if (this.instruction.textContent !== text) this.instruction.textContent = text;
    const lang = this.i18n.lang;
    const t = (k: MessageKey) => this.i18n.t(k);
    this.distance.hidden = b.variant !== "maneuver";
    if (b.variant === "maneuver") this.distance.textContent = formatDistance(b.distanceM, lang, t);
    this.street.textContent = b.street;
    this.street.hidden = b.street === "";
    if (lang !== "mn") this.street.lang = "mn";
    else this.street.removeAttribute("lang");
    // AC 38: each new instruction once, politely; not the first banner after «Эхлэх» (focus reads it), not a language
    // switch, not distance updates.
    if (this.lastAnnouncedStep === null) this.lastAnnouncedStep = b.step;
    else if (b.step !== this.lastAnnouncedStep) {
      this.lastAnnouncedStep = b.step;
      this.live.textContent = text;
    }
    // Progress: recomputed with every state, shown text refreshed at least every 5 s (AC 21); frozen while paused.
    if (force || now - this.progressAt >= 1000 || !prev || prev.phase !== s.phase) {
      this.progressAt = now;
      this.renderProgress(s);
    }
    const arrived = s.phase === "arrived";
    this.progress.hidden = arrived;
    this.arrival.hidden = !arrived;
    if (arrived) this.recenterBtn.hidden = true;
    this.renderVoiceButton(s.muted);
    this.renderMessages(s);
  }

  private renderProgress(s: GuidanceState): void {
    const lang = this.i18n.lang;
    const t = (k: MessageKey) => this.i18n.t(k);
    const eta = computeEta(Date.now(), s.progress.durationRemaining);
    const head = `${t("route.eta")} ${eta.time}`;
    const days = eta.days > 0 ? formatNextDay(eta.days, lang, t) : "";
    const time = formatDuration(s.progress.durationRemaining, t);
    const dist = formatDistance(s.progress.distanceRemaining, lang, t);
    this.eta.textContent = head;
    this.etaDays.textContent = days ? ` ${days}` : "";
    this.remaining.textContent = `${time} · ${dist}`;
    this.progressA11y.textContent = `${head}${days ? " " + days : ""}, ${t("nav.remainingTime")} ${time}, ${t("nav.remainingDistance")} ${dist}`;
  }

  private renderVoiceButton(muted: boolean): void {
    const label = this.i18n.t(muted ? "nav.unmute" : "nav.mute");
    if (this.voiceBtn.getAttribute("aria-label") !== label) {
      this.voiceBtn.setAttribute("aria-label", label);
      this.voiceBtn.title = label;
      this.voiceBtn.innerHTML = muted ? DEMO_ICONS.volumeOff : DEMO_ICONS.volumeUp;
      this.voiceBtn.dataset.muted = String(muted);
    }
  }

  private renderMessages(s: GuidanceState | null): void {
    const notice = !!s && s.voiceNoticeVisible;
    const offline = this.offline;
    this.noticeItem.hidden = !notice;
    this.offlineItem.hidden = !offline;
    this.messages.hidden = !notice && !offline;
  }

  /** Labels that do not depend on the replay state (language switch). */
  renderStatic(): void {
    const t = (k: MessageKey) => this.i18n.t(k);
    this.badgeText.textContent = t("demo.mode");
    this.recenterLabel.textContent = t("nav.recenter");
    this.noticeText.textContent = t("nav.voiceUnavailable");
    this.offlineText.textContent = t("status.offline");
    this.endBtn.setAttribute("aria-label", t("nav.end"));
    this.endBtn.title = t("nav.end");
    this.closeBtn.textContent = t("action.close");
    this.arrivalName.textContent = this.destination.isData ? this.destination.text : t("place.selectedPoint");
    if (this.destination.isData && this.i18n.lang !== "mn") this.arrivalName.lang = "mn";
    else this.arrivalName.removeAttribute("lang");
    this.voiceBtn.removeAttribute("aria-label");
    if (this.lastState) this.render(this.lastState, performance.now(), true);
  }
}
