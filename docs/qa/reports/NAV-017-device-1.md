# Device report: NAV-017 web demo mode, real iPhone, device run 1 (PO)

| | |
|---|---|
| Story | [`NAV-017`](../../requirements/stories/NAV-017-web-demo-mode-replay.md), AC 48, 49 (with AC 28, 29) |
| Test plan | [`docs/qa/test-plans/NAV-017.md`](../test-plans/NAV-017.md) §1 ("Not testable here"), §4 rows 48, 49 |
| Recording form | [`docs/qa/checklists/NAV-017-phone-po.md`](../checklists/NAV-017-phone-po.md), column "device run 1" |
| Date | 2026-10-03 (PO); recorded by QA 2026-10-04 |
| Tester, device | The PO, on the PO's iPhone. Model and iOS version **not reported** |
| Build | Not reported (last QA-verified demo build: `web/` at `0b166a8`, [`NAV-017-run3.md`](NAV-017-run3.md)) |
| Host | The PO's demo folder (`<demo-host>/<demo-folder>/`). Whether it still had a password at the time is not reported; D107 (public, no password) is dated the same day |
| Source | PO report in chat, **relayed by the orchestrator**: decision-log row D109 (2026-10-03) and the QA task of 2026-10-04. QA has not seen the PO's own wording |
| Verdict | **AC 49: PASS (as relayed). AC 48: partial**: the items below marked "not reported" are still open. **No defect found** in what was reported |

## 1. What the PO reported

1. **All three demo routes (R1, R2, R3) worked**: route picker, map, route line, banners and arrival (D109).
2. **Mongolian UI:** the notice A1 «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.»
   is shown, and there is **no Mongolian speech, because iOS has no Mongolian voice**.
3. **English UI: English speech works.**

## 2. Results per AC

| AC | Result | Notes |
|---|---|---|
| 49 | **PASS** (as relayed) | R1 to arrival completed, and so did R2 and R3. «Хаах» back to the picker was not mentioned |
| 29 | **Partly verified** | The A1 notice appears when there is no Mongolian voice: as designed (D73, AC 29). **Chime at each instruction: not reported.** No Mongolian speech is the expected fallback, not a defect |
| 28 | **PASS for English** | An English voice is used in the English UI. The exact voice (en-US preferred) was not reported |
| 48 | **Partial** | Reported: A1 shown, no Mongolian voice, English speech. **Not reported:** iOS version and phone model; page opens without a password prompt plus the 200 / `noindex` / 206 checks (AC 5, D107); whether **Settings › Accessibility › Spoken Content › Voices** lists a Mongolian voice (the relay says "iOS has no Mongolian voice" but not where this was read); chime with the ring/silent switch off and on; first sound without an extra tap (AC 27); screen on for all of R3 (787 s, AC 39); lock/resume (AC 15); safe areas, rotation, aA 200 %, VoiceOver (AC 37, 38, 41) |

## 3. Reconciliation with D109

D109 (2026-10-03) recorded "speech did not work", with English speech, the chime and A1 all unverified. This report
completes it. The missing speech was **Mongolian** speech, which is expected because iOS has no Mongolian voice
(AC 29 fallback). A1 is shown and English speech works. Still open from D109: **whether the chime played.** If it did
not, that is a NAV-017 defect in this story's fix loop (AC 29, D73). It is not a new item.

The "no Mongolian voice on iOS" result is input for NAV-007 AC 9 (iOS rows), the voice spike (D75, D78) and NAV-015.

## 4. Defects

None from the reported items. A missing chime would be a defect (owner mobile-engineer, `web/src/demo/audio.ts`). Its
severity would be set when it is reported. It is not S1: guidance stays on screen.

## 5. Next device run (ask the PO)

Fill the "not reported" rows of the checklist, most importantly: the **chime** in the Mongolian UI (switch off, then
on); the iOS version and model; the 200 / `noindex` / 206 checks on the now-public folder; and whether Settings ›
Spoken Content › Voices lists a Mongolian voice.
