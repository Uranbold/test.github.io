---
name: spike
description: "Use for a triaged question that must be answered before stories can be written: timeboxed research, then a skeptic challenge, then follow-ups to triage"
---

1. **Owner:** the **architect** for technical spikes (writes `docs/architecture/spikes/<slug>.md`, plus a *proposed* ADR if the decision is significant), or the **business-analyst** for product spikes (writes `docs/requirements/spikes/<slug>.md`).
2. Timeboxed research: compare realistic options, cite sources, and label indirect evidence as indirect. No production code. **Recommend; don't decide.**
3. **Skeptic** (the other of architect or BA): tries to **refute** the recommendation without modifying files, and lists concerns and missing evidence.
4. The CEO presents the answer and the challenge to the board. Follow-up items go back through **triage**.
