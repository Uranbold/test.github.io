# ADR-0008: On-screen route instruction text is generated on the client from OSRM manoeuvre fields and glossary templates

- **Status:** accepted (Phase 0, web route preview). Revisit when NAV-005 designs banners and voice (see Consequences).
- **Date:** 2026-09-30
- **Stories:** NAV-004 (design question "instruction text source", AC 12, 26–29, 31, 50). Affects NAV-005 (banners, voice) and NAV-007 (upstream `mn-MN` fix).

## Context
NAV-004 shows a turn-by-turn list in the web route preview. Its text must be exactly the glossary wording in NAV-004 AC 27 (C1 polite form, C2 «тийш» for left/right and «зүг» for compass directions, banner ordinals «2-р»), and AC 28 scans it for Latin letters, placeholders, a bare «зүүн»/«баруун», zero-width characters and glossary Avoid terms.

The route comes from `postRoute` (Valhalla 3.9.0 pass-through, `format=osrm`, openapi.yaml 0.5.0). Every step carries a narrative (`maneuver.instruction`, banner text, voice announcement) in the requested `language`, plus structured fields (`maneuver.type`, `modifier`, `exit`, `bearing_after`).

**Measured on 2026-09-30 against the running dev gateway** (RS1–RS8 of NAV-004, `alternates: 2`, `mn-MN` and `en-US`, 18 requests at ≤ 2 per second, 798 steps in total):

| # | Finding | Consequence |
|---|---|---|
| M1 | The `mn-MN` narrative has the NAV-007 defects in real UB routes: a bare «зүүн»/«баруун» at the start of a lower-case sentence («зүүн эргээд … руу ор.», «баруун эргэ.», F6/F7), a mixed register («эргэ», «яв», «хийгээрэй», F7), hyphen-attached suffixes («…гудамж-н 2-р гарцаар гар.», F8), the glossary Avoid term «зорьсон газар» on `arrive` («Таны зорьсон газарт очлоо.»), and road refs and Latin OSM names inside the sentence («A0401/AH3/…», «Erdenet-Selenge sum хүртэл…») | Option (a) fails AC 27 and AC 28 on RS1–RS6. This is not a rare edge case |
| M2 | Types seen: `depart`, `turn`, `continue`, `new name`, `end of road`, `fork`, `roundabout`, `exit roundabout`, `rotary`, `exit rotary`, `arrive`. Modifiers: all eight, plus absent on `depart` and some `arrive`. `continue` and `new name` also come with turning modifiers (`continue` + `left`, whose Valhalla text is "turn left and …") | The mapping needs an explicit order of precedence (§2) |
| M3 | `exit` is present on **40 of 40** `roundabout`/`rotary` steps. `bearing_after` is present on every step, and can be **360** | The structured fields are enough to produce every AC 27 row. `exit` was missing from the openapi schema (added in 0.5.0). Bearings are normalised modulo 360 |
| M4 | Gateway times 13–270 ms per route (RS6 intercity 90 ms, RS8 walk 315 km 270 ms) | The text source has no latency impact. No reason to add a server-side stage |

Valhalla compiles its narrative locales into the binary, so our own text on the server would need either a patched Valhalla build (a fork) or a new response-rewriting service behind the gateway. The gateway today is nginx with pure pass-through (ADR-0002), and its NAV-008 rate-limit, `real_ip` and header files must not be touched by this story.

## Decision

### 1. Source: option (b), client-side text, in both UI languages
The web client builds every on-screen instruction from the OSRM manoeuvre fields and the glossary templates in its resource files (`web/src/i18n/mn.json`, `en.json`). It **never** displays `maneuver.instruction`, `bannerInstructions[].*.text` or `voiceInstructions[].announcement`, in either language. English also uses client templates (AC 29 allows Valhalla `en-US`, but one code path is simpler and gives **0** route requests on a language switch, AC 50).

The request still sends `language` (`mn-MN` or `en-US`) as AC 10 requires. The response then stays readable in debugging tools and matches what Ferrostar will expect in NAV-005. A language switch does not re-request the route.

### 2. Mapping: language-neutral key first, localised text second
The mapping is a pure function in two steps, with no DOM, MapLibre or i18n dependency in the first step:

1. `maneuverKey(maneuver) → { key, params? }`: language-neutral.
2. `t(key, params)`: the existing i18n lookup, then the first letter is upper-cased (idempotent, so resource values may be stored in the glossary's lower-case form or capitalised, AC 48).

`side(modifier)` is `left` for `left`, `slight left`, `sharp left`; `right` for `right`, `slight right`, `sharp right`; otherwise none. The **first** matching rule wins:

| # | Condition | Key (suggested name) | AC 27 row |
|---|---|---|---|
| 1 | `type = depart` and `bearing_after` is a number | `depart.<n\|ne\|e\|se\|s\|sw\|w\|nw>`, sector = `floor(((b mod 360) + 22.5) / 45) mod 8` (half-open sectors: 22.4° → n, 22.5° → ne, 337.5° → n, 360 → n) | depart |
| 2 | `type = arrive` | `arrive.left` / `arrive.right` by side, else `arrive` | arrive |
| 3 | `type ∈ {roundabout, rotary}` and `exit` is an integer ≥ 1 | `roundabout.exit` with `{ n: exit }` (resource value keeps `{n}`, for example the banner form «Тойрог: {n}-р гарц») | roundabout with exit |
| 4 | `type ∈ {roundabout, rotary}` otherwise | `roundabout.enter` | roundabout without exit |
| 5 | `type ∈ {exit roundabout, exit rotary}` | `roundabout.leave` (the modifier is ignored) | exit roundabout |
| 6 | `modifier = uturn` (any remaining type) | `uturn` | uturn |
| 7 | `type = fork` | `keep.left` / `keep.right` by side, else `continue` | fork |
| 8 | `type = merge` | `merge.left` / `merge.right` by side, else `merge` | merge |
| 9 | `type = on ramp` | `onRamp.left` / `onRamp.right` by side, else `onRamp` | on ramp |
| 10 | `type = off ramp` | `offRamp.left` / `offRamp.right` by side, else `offRamp` | off ramp |
| 11 | `modifier` is a turning modifier (any remaining type: `turn`, `end of road`, `continue`, `new name`, `notification`, unknown) | `turn.left`, `turn.right`, `turn.slightLeft`, `turn.slightRight`, `turn.sharpLeft`, `turn.sharpRight` | turn |
| 12 | anything else (`straight`, no modifier, unknown type or modifier, `depart` without a bearing) | `continue` | continue |

The exact `mn` and `en` strings are the ones in NAV-004 AC 27 (glossary section 3 and rows N14, N15, N23). This ADR adds no user-facing wording. The mobile engineer owns the final key names; the rule order above is binding.

Other rules:
- The street name is **not** built into the sentence. It is shown on its own line (AC 26) from `step.name` after removing U+200B, U+200C, U+200D and U+FEFF. This avoids the case-suffix problem (glossary C5, NAV-007 F8) entirely and keeps Latin OSM names out of the instruction text (AC 28 excludes the street-name line).
- Unknown future Valhalla types or modifiers fall through to rule 11 or 12. Raw Valhalla text is never a fallback.
- Nothing about steps or coordinates is written to the console or storage (AC 52).

### 3. Portability to NAV-005
- The rule table is language-neutral and lives in one module (suggested `web/src/route/instructions.ts`), with a fixture file of `{ maneuver subset → expected key and params }` covering every row and boundary in the AC 27 test list (suggested `web/src/route/maneuvers.fixture.json`). The Android and iOS ports in NAV-005 reuse the same fixture file (moved to a shared location then) so the three implementations are tested against identical cases.
- The strings live only in resource files (AC 31). No platform copies strings into code.

### 4. Related contract decisions for NAV-004 (openapi.yaml 0.5.0)
- **Costings:** `auto`, `pedestrian` and **`bicycle`** (newly contracted). `alternates: 2` for all three (measured: 3 routes for walk and bike in UB).
- **Avoid unpaved:** contracted for `auto` only (`costing_options.auto.exclude_unpaved`). Valhalla silently ignores `exclude_unpaved` for `pedestrian` and `bicycle` (measured: identical routes with and without it). Bicycle costing has only a weighting preference (`avoid_bad_surfaces`), which is not the "exclude" meaning of the toggle. So NAV-004 AC 12 resolves to: the toggle is **hidden** on «Явган» and «Дугуй», and no `costing_options` are sent for them.
- **No toll option** is sent (product decision in the story; `use_tolls` stays uncontracted for clients).
- **`voice_instructions` is omitted** in the preview (nothing is spoken; smaller responses). `banner_instructions: true` stays (AC 10) so the response has the same shape NAV-005 will use.
- **`DistanceExceeded`:** limits are straight-line per costing and checked before snapping (dev: walk 250 km, bike 500 km, car 5,000 km). A far out-of-coverage point on foot or by bike gives `DistanceExceeded` (shown as "too far", AC 35), not `NoSegment`. RS8 (P1 → X1 on foot, about 245 km straight line) returns **200** on dev, so QA uses P1 → X2 on foot (live 400 `DistanceExceeded`) or an injected response.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| (a) Valhalla `mn-MN` text as-is | No client logic; richer sentences ("onto <street>") | Fails AC 27/28 on everyday UB routes (M1): English and placeholder leaks at roundabouts (F1, F2), bare «зүүн»/«баруун» (F6, safety-relevant), mixed register (F7), Avoid term «зорьсон газар». Fixes depend on an upstream PR and a new Valhalla pin (NAV-007, D17), which may take months |
| **(b) Client-side text from manoeuvre fields and glossary templates (chosen)** | Glossary is the single source of wording; fully testable offline with fixtures; no backend change and no departure from the pass-through gateway; 0 requests on language switch; works unchanged when Valhalla is later fixed or re-pinned | The same mapping must be implemented per platform (web now; Android and iOS in NAV-005); sentences are shorter than Valhalla's (the street name is on a separate line); new Valhalla manoeuvre types need a mapping update (fallback keeps the text valid) |
| (c) Gateway post-processing of Valhalla's response | One implementation for all clients; could later also fix banner and voice text for Ferrostar | Breaks the pure pass-through (ADR-0002); needs a new service behind nginx (JSON rewriting cannot be done well in nginx config), a new failure mode and latency on every route, and backend work next to the NAV-008 gateway files the story must avoid; server-side i18n for two languages; the preview would still need the same mapping table |
| (d) Patched Valhalla build with our own `mn-MN` locale | Fixes the text at the source, for banners and voice too | A fork of an upstream component (needs its own ADR under our principles), a custom image build and rebuild pipeline; F6/F8 fixes may need code, not only locale changes. This is the NAV-007 upstream path, not a Phase 0 web decision |

## Consequences
- **Easier:** AC 27–29 and AC 48 can be met and unit-tested without the stack; the text stays correct whatever Valhalla's narrative does; language switches cost no network; the contract stays pass-through (0.5.0 is documentation only for backend).
- **Harder:** the rule table is duplicated in Kotlin and Swift in NAV-005 (mitigated by the shared fixture file). When the Valhalla pin changes, re-check the OSRM manoeuvre types and modifiers it emits against §2 (like the log re-check in ADR-0002 §3.4).
- **Interpretation to confirm (non-blocking):** rule 11 shows turn text for `continue`/`new name` steps that carry a turning modifier (M2). NAV-004 AC 27 lists `continue` and `new name` in the "Continue straight" row; the ADR reads that row as "with `straight` or no modifier", which matches Valhalla's own meaning of these steps ("turn left and continue on …"). If the BA reads it otherwise, AC 27 needs a one-line clarification.
- **NAV-005 follow-up (new ADR then):** Ferrostar renders banner text from the parsed route and speaks `voiceInstructions`. NAV-005 must decide between (i) a client-side transform of the OSRM JSON before Ferrostar parses it (rewriting banner text with this mapping), (ii) custom banner views keyed on the manoeuvre, or (iii) option (c) or (d) if voice needs server-side text and the NAV-007 upstream fix is not yet in a pinned release. Voice text (distance prefixes with locatives, spelled-out ordinals, C3/C4/C5) is out of scope here.
- **Trigger to revisit (c)/(d):** the three platform implementations drift apart in QA, or NAV-005 needs corrected voice text before the NAV-007 fix is released.
