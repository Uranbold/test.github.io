# NAV-017 real-iPhone checklist: the PO on the protected demo folder (AC 48, 49)

Owner: qa-engineer. Test plan: [`docs/qa/test-plans/NAV-017.md`](../test-plans/NAV-017.md) §1 ("Not testable here").
Source of the items: `web/README.md` › Demo mode (NAV-017) › Real-iPhone checklist (AC 5, 48). This file is the
**recording form**: same items, same numbers, plus what to write down. If the two lists ever differ, the README is the
one the build test checks (`tests/e2e/nav017/build.test.mjs` AC5); report the difference to QA.

**Rules.** Never paste the password, the host name, the folder name or a screenshot that shows the URL bar. Write
`<demo-host>/<demo-folder>/` instead. Record the iOS version (Settings › General › About) and the phone model once.
Mongolian is the UI language unless an item says English.

| | |
|---|---|
| Phone model / iOS version | |
| Build uploaded (commit or `SHA256SUMS` first line) | |
| Date, time (UB) | |
| Ring/silent switch at start | off (ring) |

| # | Item (README numbering) | Expected | Result (pass / fail / n.a.) and notes |
|---|---|---|---|
| 1 | Open `https://<demo-host>/<demo-folder>/` | Safari asks for the password; without it the page is **401**; with it **200**; the tile archive answers **206** to a Range request (README "checks after every upload") | |
| 2 | Opening map and picker | Tiles draw under the protection; the picker shows R1, R2, R3 with origin, destination, «Машин»/«Явган», distance and duration; «© OpenStreetMap contributors» visible | |
| 3 | Tap R1, then «Эхлэх» (Mongolian UI) | Either Mongolian speech is heard, **or** the notice «Энэ утсанд монгол дуут заавар ажиллахгүй байна. Заавар зөвхөн дэлгэцэнд харагдана.» appears once for about 8 s and a short chime plays at each instruction. Also check **Settings › Accessibility › Spoken Content › Voices**: is a Mongolian voice listed? (write yes/no; input for NAV-007 AC 9 and the voice spike) | notice shown: ☐ yes ☐ no; Mongolian voice in Settings: ☐ yes ☐ no |
| 4 | Switch the UI to English (same replay or a new one) | English speech is heard for the next instruction; banners in English; place names stay Cyrillic | |
| 5 | First sound after «Эхлэх» | Speech or the chime starts **without any extra tap** within about 2 s of «Эхлэх» | |
| 6 | Ring/silent switch | Chime (and speech) audible with the switch **off**; then repeat with the switch **on** and note whether anything is heard (the demo keeps the iOS default; a change would come through triage) | off: ; on: |
| 7 | Screen stays on | Start R3 and leave the phone untouched: the screen stays on until the arrival panel (787 s, about 13 min) | |
| 8 | Lock / switch apps mid-replay | Lock the phone during R1 for about a minute, unlock: the replay resumed at the same place and time, no instruction repeated. Take or simulate a phone call, or open Control Centre, and come back: audio still works (if not, a tap anywhere should resume it) | |
| 9 | Layout | Portrait and landscape: nothing under the notch or the home indicator; Safari's toolbar never covers «Эхлэх», «Дуусгах» or the attribution; rotating keeps the replay; Safari **aA › text size 200 %**: banner readable, «Дуусгах» reachable; a pinch that starts on the banner does not zoom the page | |
| 10 | VoiceOver | Every button has a spoken name («Эхлэх», «Дуусгах», «Дууг хаах», «Байршил руу буцах», «Хаах»); each new instruction is announced **once**, distance updates are not announced | |
| 11 | AC 49 | R1 from «Эхлэх» to the arrival panel («Зайсан Голден Вилл», «Хаах») completes; «Хаах» returns to the picker with no route drawn | |

Send the filled table to QA by chat (not in the repo, not in an issue, if it contains anything from the URL bar).
QA records the outcome in the next `docs/qa/reports/NAV-017-run<N>.md`; defects found here stay in the NAV-017 fix loop
(story AC 49).
