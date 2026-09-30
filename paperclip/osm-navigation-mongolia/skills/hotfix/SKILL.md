---
name: hotfix
description: "Use only for S1 items (outage, crash on start, dangerous guidance, data leak) after the board confirmed P0; one hotfix at a time"
---

1. Refuse anything that isn't S1, and send it to `bug-fix`. **Only one hotfix at a time**: other work waits.
2. **qa-engineer:** reproduce with a failing test and confirm S1. If QA rates it lower, leave the hotfix lane.
3. **Owner:** the **smallest safe change**, with no refactoring and no scope additions.
4. **qa-engineer:** the failing test passes, and smoke tests pass.
5. **Mandatory follow-up within 48 h**, as a new issue: a root-cause note, the regression test kept, and a check with the BA for a gap in the story AC.
