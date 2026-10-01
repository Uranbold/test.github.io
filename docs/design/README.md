# Design (owner: ux-designer)

- `flows/`: user flows (Mermaid)
- `screens/`: screen specs (template: `../templates/screen-spec.md`)
- `prototypes/`: static HTML/SVG wireframes
- `tokens.json`: design tokens (light + night)
- `map-style.md`: MapLibre style spec
- `navigation-ux.md`: active navigation rules (banners, voice timing, lanes, speed limit)

Checks (run from the repo root):
- `node docs/design/prototypes/check-contrast.mjs`: WCAG contrast of every token pair in `tokens.json › contrastPairs`, day/night flavor key parity, and `map-style.md` table values vs `tokens.json`.
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout.mjs`: layout rules of the NAV-002 wireframe at 5 viewports x every state x day/night x mn/en (uses the Playwright copy in `tests/e2e`, installs nothing).
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav003.mjs`: layout rules of the NAV-003 search wireframe (`prototypes/NAV-003-search.html`) at the same 5 viewports x 21 search/card states x day/night x mn/en x with/without the NAV-002 worst-case messages, including "the pin at the viewport centre is not covered".
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav004.mjs`: layout rules of the NAV-004 route preview wireframe (`prototypes/NAV-004-route-preview.html`) at the same 5 viewports x 24 route states x day/night x mn/en x with/without the NAV-002 worst-case messages (bottom sheet below 840 px, own left grid column from 840 px, sticky panel header, search field hidden while the panel is open).
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node docs/design/prototypes/check-layout-nav005.mjs`: layout rules of the NAV-005 Android guidance wireframe (`prototypes/NAV-005-guidance.html`, 1 CSS px = 1 dp) at 360×640, 412×915, 320×568 and 640×360 dp x 13 guidance/preview states x day/night x mn/en x font scale 1.0/1.3/2.0 (attribution uncovered, no overlaps, 48 dp targets, banner instruction ≤ 3 lines, minimum map band, puck uncovered). `--wide` repeats it with a much wider font as a stress test.
