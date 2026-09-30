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
