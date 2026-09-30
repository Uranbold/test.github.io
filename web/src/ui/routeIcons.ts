// NAV-004 icons (screen spec › Content rules › Manoeuvre icons, Components). Own simple paths in Material Symbols
// style, like src/ui/icons.ts: bundled, no icon font, no CDN, no third-party SVG copied (so no extra licence notice).
// All decorative (aria-hidden); currentColor.
import type { ManeuverKey } from "../route/instructions";

const svg = (path: string, mirror = false): string =>
  `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"${mirror ? ' class="mirror"' : ""}>${path}</svg>`;

const P = {
  car: '<path d="M5 11 6.6 6.4A2 2 0 0 1 8.5 5h7a2 2 0 0 1 1.9 1.4L19 11v8h-2v-2H7v2H5Zm2.2-1h9.6l-1-3H8.2ZM7.5 12a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Zm9 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3Z"/>',
  walk: '<path d="M13.5 5.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3A7.3 7.3 0 0 0 19 13v-2a5.2 5.2 0 0 1-4.3-2.4l-1-1.6a2 2 0 0 0-2.5-.8L6 8.3V13h2V9.6Z"/>',
  bike: '<path d="M15.5 5.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM5 12a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 8.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7Zm5.8-10.1L13 8.2l.8.8A7 7 0 0 0 19 11V9a5 5 0 0 1-3.6-1.5l-1.9-1.9a2 2 0 0 0-2.8 0L7.6 8.7a2 2 0 0 0 .4 3.1L11 13.5V19h2v-6.6ZM19 12a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 8.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7Z"/>',
  swap: '<path d="M8 3 3.5 7.5 4.9 8.9 7 6.8V14h2V6.8l2.1 2.1 1.4-1.4Zm8 18 4.5-4.5-1.4-1.4-2.1 2.1V10h-2v7.2l-2.1-2.1-1.4 1.4Z"/>',
  directions: '<path d="m12 1.4 10.6 10.6L12 22.6 1.4 12Zm1 7.1V11H9v4h2v-2h2v2.5l3.5-3.5Z"/>',
  ring: '<path d="M12 5a7 7 0 1 1 0 14 7 7 0 0 1 0-14Zm0 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z"/>',
  pin: '<path d="M12 2a7 7 0 0 1 7 7c0 5.2-7 13-7 13S5 14.2 5 9a7 7 0 0 1 7-7Zm0 4.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5Z"/>',
  myLocation: '<path d="M11 1h2v3.1A8 8 0 0 1 19.9 11H23v2h-3.1A8 8 0 0 1 13 19.9V23h-2v-3.1A8 8 0 0 1 4.1 13H1v-2h3.1A8 8 0 0 1 11 4.1Zm1 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12Zm0 3a3 3 0 1 1 0 6 3 3 0 0 1 0-6Z"/>',
  info: '<path d="M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm-1 8v7h2v-7Zm0-3v2h2V7Z"/>',
  noRoute: '<path d="M3.3 2 2 3.3 5.7 7H5v2h2.7l2 2H7l-3 3 3 3h7.7l6 6 1.4-1.4ZM13 5V2h-2v3H9.8l2 2H17l3-3-3-3h-4Zm-2 17h2v-2.2l-2-2Z"/>',
  // manoeuvres (right-hand versions; left = mirrored)
  depart: '<path d="M12 5a7 7 0 1 1 0 14 7 7 0 0 1 0-14Zm0 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z"/>',
  turn: '<path d="M4 20V11a4 4 0 0 1 4-4h8.2l-2.6-2.6L15 3l5 5-5 5-1.4-1.4L16.2 9H8a2 2 0 0 0-2 2v9Z"/>',
  slight: '<path d="M11 21v-7.6l5.2-5.2L18 10V4h-6l1.8 1.8L9 10.6V21Z"/>',
  sharp: '<path d="M6 21V8a4 4 0 0 1 7-2.6l4.3 5L19 8.8V15h-6.2l1.7-1.7L10.7 8.8A2 2 0 0 0 8 8.4V21Z"/>',
  uturn: '<path d="M16 21V9a4 4 0 0 0-8 0v7.2l2.6-2.6L12 15l-5 5-5-5 1.4-1.4L6 16.2V9a6 6 0 0 1 12 0v12Z"/>',
  straight: '<path d="M11 21V7.8L8.4 10.4 7 9l5-5 5 5-1.4 1.4L13 7.8V21Z"/>',
  fork: '<path d="M11 21v-6.6l5-5V12h2V6h-6v2h2.6L12 12.6 9.4 10H12V8H6v6h2v-2.6l3 3V21Zm0 0h2v-6.6Z"/>',
  merge: '<path d="M7.4 21 6 19.6l5-5V7.8L8.4 10.4 7 9l5-5 5 5-1.4 1.4L13 7.8v7.6l-2 2Zm9.2 0L13.7 18l1.4-1.4 2.9 2.9Z"/>',
  ramp: '<path d="M11 21V11L5 5l1.4-1.4L12 9.2l4.6-4.6H14V2.6h6v6h-2V6L13 11v10Z"/>',
  roundabout: '<path d="M12 6a5 5 0 1 0 5 5h-2a3 3 0 1 1-3-3V2h2v4.1ZM11 16h2v6h-2Z"/>',
  flag: '<path d="M5 21V3h9l.4 2H20v10h-7l-.4-2H7v8Z"/>',
} as const;

export const ROUTE_ICONS = {
  car: svg(P.car),
  walk: svg(P.walk),
  bike: svg(P.bike),
  swap: svg(P.swap),
  directions: svg(P.directions),
  ring: svg(P.ring),
  pin: svg(P.pin),
  myLocation: svg(P.myLocation),
  info: svg(P.info),
  noRoute: svg(P.noRoute),
} as const;

/** The same language-neutral key that picks the text picks the icon (screen spec › Manoeuvre icons). */
export function maneuverIcon(key: ManeuverKey): string {
  if (key.startsWith("depart.")) return svg(P.depart);
  if (key.startsWith("arrive")) return svg(P.flag);
  if (key.startsWith("roundabout.")) return svg(P.roundabout, true); // counter-clockwise (right-hand traffic)
  switch (key) {
    case "turn.left":
      return svg(P.turn, true);
    case "turn.right":
      return svg(P.turn);
    case "turn.slightLeft":
      return svg(P.slight, true);
    case "turn.slightRight":
      return svg(P.slight);
    case "turn.sharpLeft":
      return svg(P.sharp, true);
    case "turn.sharpRight":
      return svg(P.sharp);
    case "uturn":
      return svg(P.uturn); // right-hand traffic: U-turns go left
    case "keep.left":
      return svg(P.fork, true);
    case "keep.right":
      return svg(P.fork);
    case "merge":
    case "merge.left":
    case "merge.right":
      return svg(P.merge, key === "merge.right");
    case "onRamp.left":
    case "offRamp.left":
      return svg(P.ramp, true);
    case "onRamp":
    case "onRamp.right":
    case "offRamp":
    case "offRamp.right":
      return svg(P.ramp);
    default:
      return svg(P.straight);
  }
}
