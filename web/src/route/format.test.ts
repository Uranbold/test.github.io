// NAV-004 AC 21–25: distance, duration and «Хүрэх цаг» formatting (exact values from the AC).
import { afterEach, describe, expect, it } from "vitest";
import { resources, type Lang, type MessageKey } from "../i18n/i18n";
import { computeEta, formatDistance, formatDuration, formatEta, formatNextDay, formatRouteCount } from "./format";

const t = (lang: Lang) => (k: MessageKey) => resources()[lang][k];
const mn = t("mn");
const en = t("en");

describe("formatDistance (AC 23)", () => {
  it.each([
    [0, "10 м", "10 m"],
    [4, "10 м", "10 m"],
    [5, "10 м", "10 m"],
    [850, "850 м", "850 m"],
    [994, "990 м", "990 m"],
    [995, "1 км", "1 km"],
    [1000, "1 км", "1 km"],
    [1416, "1,4 км", "1.4 km"],
    [1449, "1,4 км", "1.4 km"],
    [1450, "1,5 км", "1.5 km"],
    [12_000, "12 км", "12 km"],
    [12_400, "12,4 км", "12.4 km"],
    [99_949, "99,9 км", "99.9 km"],
    [99_950, "100 км", "100 km"],
    [245_300, "245 км", "245 km"],
  ])("%d m → «%s» / %s", (m, mnText, enText) => {
    expect(formatDistance(m, "mn", mn)).toBe(mnText);
    expect(formatDistance(m, "en", en)).toBe(enText);
  });

  it("treats negative and non-finite values as 0", () => {
    expect(formatDistance(-5, "mn", mn)).toBe("10 м");
    expect(formatDistance(Number.NaN, "en", en)).toBe("10 m");
  });
});

describe("formatDuration (AC 24)", () => {
  it.each([
    [0, "1 мин", "1 min"],
    [29, "1 мин", "1 min"],
    [30, "1 мин", "1 min"],
    [89, "1 мин", "1 min"],
    [1500, "25 мин", "25 min"],
    [3569, "59 мин", "59 min"],
    [3570, "1 ц", "1 h"],
    [5100, "1 ц 25 мин", "1 h 25 min"],
    [7200, "2 ц", "2 h"],
    [108_000, "30 ц", "30 h"],
  ])("%d s → «%s» / %s", (s, mnText, enText) => {
    expect(formatDuration(s, mn)).toBe(mnText);
    expect(formatDuration(s, en)).toBe(enText);
  });
});

describe("«Хүрэх цаг» (AC 25)", () => {
  const tz = process.env.TZ;
  afterEach(() => {
    process.env.TZ = tz;
  });
  const at = (h: number, m: number, s = 0) => new Date(2026, 8, 30, h, m, s).getTime();

  it("response 13:50:00 + 2,700 s → «Хүрэх цаг 14:35»", () => {
    const eta = computeEta(at(13, 50), 2700);
    expect(eta).toEqual({ time: "14:35", days: 0 });
    expect(formatEta(eta, "mn", mn)).toBe("Хүрэх цаг 14:35");
    expect(formatEta(eta, "en", en)).toBe("Arrive at 14:35");
  });

  it("response 23:30:00 + 3,600 s → «Хүрэх цаг 00:30 +1 өдөр»", () => {
    const eta = computeEta(at(23, 30), 3600);
    expect(eta).toEqual({ time: "00:30", days: 1 });
    expect(formatEta(eta, "mn", mn)).toBe("Хүрэх цаг 00:30 +1 өдөр");
    expect(formatEta(eta, "en", en)).toBe("Arrive at 00:30 +1 day");
  });

  it("rounds to the nearest minute and counts several days", () => {
    expect(computeEta(at(10, 0, 0), 29).time).toBe("10:00");
    expect(computeEta(at(10, 0, 0), 30).time).toBe("10:01");
    const eta = computeEta(at(22, 0), 2 * 86_400 + 3 * 3600);
    expect(eta).toEqual({ time: "01:00", days: 3 });
    expect(formatEta(eta, "en", en)).toBe("Arrive at 01:00 +3 days");
    expect(formatNextDay(2, "mn", mn)).toBe("+2 өдөр");
  });
});

describe("route count (N7, AC 47)", () => {
  it("uses the plural keys with {count}", () => {
    expect(formatRouteCount(1, "mn", mn)).toBe("1 маршрут олдлоо");
    expect(formatRouteCount(3, "mn", mn)).toBe("3 маршрут олдлоо");
    expect(formatRouteCount(1, "en", en)).toBe("1 route found");
    expect(formatRouteCount(2, "en", en)).toBe("2 routes found");
  });
});
