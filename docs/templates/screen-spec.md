# Screen: <name>

- **Stories:** NAV-XXX (AC 1, 3)
- **Platforms:** Android / iOS / Web
- **Figma / prototype:** <link or docs/design/prototypes/...>

## Purpose
<one sentence>

## Context and goal
- **Primary task:** <what the user is trying to do>
- **Moment of use:** <driving / walking / planning / passenger typing> and the time budget (e.g. one glance < 1 s)
- **Alternatives considered:** <2+ options, one line each, and why this one was chosen>

## Layout
<regions top→bottom, or an ASCII/SVG wireframe>

## Components
| Component | Source (Ferrostar / Material 3 / custom) | Notes |
|---|---|---|

## States
| State | What the user sees |
|---|---|
| Default | |
| Loading | |
| Empty / no result | |
| Error | |
| Offline | |
| GPS lost / permission denied | |

## Interactions
- <gesture/tap → result>

## Copy (mn / en)
| Key | mn | en |
|---|---|---|

## Accessibility
- Contrast, screen-reader labels, dynamic type, touch targets ≥ 48dp

## Design rationale
| UX law / heuristic | How this screen applies it | Deliberate trade-off |
|---|---|---|
| Fitts's law | | |
| Hick's law | | |
| Jakob's law | | |
| Doherty threshold | | |
| <others from `.claude/agents/ux-designer.md`> | | |

Nielsen heuristics checked: <list any that needed a design change, or "all pass">

## Evidence
- Prototype: <path>; layout checker: <command, viewports × states × languages × font scales, result>
- Contrast: <`check-contrast.mjs` result>
- Timing budgets: <which AC, measured or delegated to QA>
