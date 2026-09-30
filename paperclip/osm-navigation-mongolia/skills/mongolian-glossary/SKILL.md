---
name: mongolian-glossary
description: "Use whenever you write user-facing Mongolian (UI copy, voice prompts, test expectations, triage comments): take every term from the binding glossary"
---

- `docs/requirements/glossary.md` is **binding**: one approved Mongolian term per concept.
- Use the term exactly as written in its Mongolian column. **Never invent** a term. If one is missing, request it from the business-analyst (`requests_to_other_agents`).
- Key conventions (glossary section 1):
  - polite instructions: «эргэнэ үү», «явна уу»
  - left and right always take «тийш» («зүүн тийш»); compass directions take «зүг» («зүүн зүг»)
  - units: banners use «300 м-т» and «км/цаг»; voice spells them out («300 метрт», «цагт 60 километр»)
  - voice ordinals: «нэгдүгээр… аравдугаар»
- **Avoid** terms («навигаци», «Төвлөрүүлэх», «км/ц», the «хоёр дахь» ordinals) must not appear in app strings.
- Statuses: `PO-approved … (panel pending)` and `needs native review` are provisional until the NAV-007 native-speaker panel signs off.
- Map labels fall back `name:mn` → `name` → `name:en`.
