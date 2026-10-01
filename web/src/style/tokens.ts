// Reads docs/design/tokens.json (owner: ux-designer, single source of truth for colours).
// Nothing here copies a colour by hand: the map flavors, the custom road colours, the UI roles and
// the location colours are all derived from the token file at build time.
import type { Flavor } from "@protomaps/basemaps";
import tokens from "@design/tokens.json";

export type Theme = "day" | "night";
type Mode = "light" | "night";

export const THEME_MODE: Record<Theme, Mode> = { day: "light", night: "night" };

interface TokenLeaf {
  $type: string;
  $value: unknown;
}
type TokenTree = { [key: string]: TokenTree | TokenLeaf | string };

function isLeaf(node: unknown): node is TokenLeaf {
  return typeof node === "object" && node !== null && "$value" in node;
}

/** Turns a token group into a plain object of values ({ key: { $value } } -> { key: value }). */
export function resolveGroup(group: TokenTree): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, node] of Object.entries(group)) {
    if (key.startsWith("$")) continue;
    if (isLeaf(node)) out[key] = node.$value;
    else if (typeof node === "object" && node !== null) out[key] = resolveGroup(node as TokenTree);
  }
  return out;
}

const color = tokens.color as unknown as Record<Mode, TokenTree>;

function modeGroup(theme: Theme, ...path: string[]): Record<string, unknown> {
  let node: TokenTree = color[THEME_MODE[theme]];
  for (const p of path) node = node[p] as TokenTree;
  return resolveGroup(node);
}

/** Complete Protomaps Flavor for the theme (tokens.json › color.<mode>.map.flavor). */
export function tokenFlavor(theme: Theme): Flavor {
  return modeGroup(theme, "map", "flavor") as unknown as Flavor;
}

export interface CustomRoadColours {
  trunk: string;
  trunk_casing: string;
  lowzoom_highway: string;
  lowzoom_trunk: string;
  lowzoom_major: string;
}

export function customRoadColours(theme: Theme): CustomRoadColours {
  return modeGroup(theme, "map", "custom") as unknown as CustomRoadColours;
}

export function uiColours(theme: Theme): Record<string, string> {
  return modeGroup(theme, "ui") as Record<string, string>;
}

export interface LocationColours {
  dot: string;
  "dot-stroke": string;
  "accuracy-fill": string;
  "accuracy-stroke": string;
  "stale-dot": string;
  "stale-accuracy-fill": string;
  "stale-accuracy-stroke": string;
}

export function locationColours(theme: Theme): LocationColours {
  return modeGroup(theme, "location") as unknown as LocationColours;
}

export interface PinColours {
  fill: string;
  stroke: string;
  center: string;
}

/** Any colour group of a theme as a flat map (e.g. "nav", "demo"), for features that add their own custom properties. */
export function colourGroup(theme: Theme, group: string): Record<string, string> {
  return modeGroup(theme, group) as Record<string, string>;
}

/** NAV-003 selected-place pin (tokens.json › color.<mode>.pin, map-style.md §7.1). */
export function pinColours(theme: Theme): PinColours {
  return modeGroup(theme, "pin") as unknown as PinColours;
}

export interface RouteColours {
  selected: string;
  "selected-casing": string;
  alternative: string;
  "alternative-casing": string;
  "origin-fill": string;
  "origin-stroke": string;
  "step-fill": string;
  "step-stroke": string;
}

/** NAV-004 route lines and markers (tokens.json › color.<mode>.route, map-style.md §7.2–7.3). */
export function routeColours(theme: Theme): RouteColours {
  return modeGroup(theme, "route") as unknown as RouteColours;
}

type Scalar = string | number;

function cssDeclarations(theme: Theme): string[] {
  const decl: string[] = [];
  for (const [k, v] of Object.entries(uiColours(theme))) decl.push(`--ui-${k}:${v}`);
  for (const [k, v] of Object.entries(locationColours(theme))) decl.push(`--loc-${k}:${v}`);
  for (const [k, v] of Object.entries(pinColours(theme))) decl.push(`--pin-${k}:${v}`);
  for (const [k, v] of Object.entries(routeColours(theme))) decl.push(`--route-${k}:${v}`);
  const flavor = tokenFlavor(theme) as unknown as Record<string, unknown>;
  decl.push(`--map-earth:${String(flavor.earth)}`);
  decl.push(`color-scheme:${theme === "night" ? "dark" : "light"}`);
  return decl;
}

function simpleGroup(name: string): Record<string, Scalar> {
  return resolveGroup((tokens as unknown as Record<string, TokenTree>)[name] as TokenTree) as Record<string, Scalar>;
}

/**
 * A motion duration token in milliseconds (tokens.json › motion.<name>, e.g. "700ms" -> 700).
 * Throws when the token is missing or not a millisecond duration, so a renamed token fails the build's tests.
 */
export function motionMs(name: string): number {
  const v = simpleGroup("motion")[name];
  const m = typeof v === "string" ? /^(\d+(?:\.\d+)?)ms$/.exec(v) : null;
  if (!m) throw new Error(`tokens.json motion.${name} is not a millisecond duration: ${String(v)}`);
  return Number(m[1]);
}

/**
 * CSS custom properties for the UI chrome. Day rules sit on :root, night rules on
 * :root[data-theme="night"], so switching the attribute switches the chrome in one frame (map-style.md §8).
 */
export function tokensCss(): string {
  const shared: string[] = [];
  for (const group of ["spacing", "size", "radius", "elevation"]) {
    for (const [k, v] of Object.entries(simpleGroup(group))) shared.push(`--${group}-${k}:${String(v)}`);
  }
  const motion = simpleGroup("motion");
  for (const [k, v] of Object.entries(motion)) {
    if (typeof v === "string" || typeof v === "number") shared.push(`--motion-${k}:${String(v)}`);
  }
  const easing = (tokens.motion as unknown as Record<string, TokenLeaf>)["easing-standard"]?.$value as number[] | undefined;
  if (easing) shared.push(`--motion-easing-standard:cubic-bezier(${easing.join(",")})`);
  const uiFont = (tokens.typography.fontFamily.ui.$value as string[])
    .map((f) => (f.includes(" ") || f.includes("-") ? `"${f}"` : f))
    .join(",");
  shared.push(`--font-ui:${uiFont}`);
  return (
    `:root{${[...shared, ...cssDeclarations("day")].join(";")}}\n` +
    `:root[data-theme="night"]{${cssDeclarations("night").join(";")}}\n`
  );
}
