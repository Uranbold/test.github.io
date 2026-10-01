// Small DOM helpers for the demo views (no framework, like the rest of web/).
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, string> = {}, ...children: (Node | string | null)[]): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c !== null) e.append(c);
  return e;
}

export function icon(svg: string, cls = "icon"): HTMLSpanElement {
  const s = h("span", { class: cls, "aria-hidden": "true" });
  s.innerHTML = svg;
  return s;
}

export function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const e = document.getElementById(id);
  if (!e) throw new Error(`missing #${id}`);
  return e as T;
}

const svg = (path: string): string => `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${path}</svg>`;

/** Demo-only icons, own simple paths in the Material Symbols style of src/ui/icons.ts (no third-party SVG). */
export const DEMO_ICONS = {
  play: svg('<path d="M8 5v14l11-7Z"/>'),
  flask: svg('<path d="M9 2h6v2h-1v5.3l5.6 9.4A2 2 0 0 1 17.9 22H6.1a2 2 0 0 1-1.7-3.3L10 9.3V4H9Zm3 7.9L8.6 15.6h6.8Z"/>'),
  volumeUp: svg('<path d="M3 9v6h4l5 5V4L7 9Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4ZM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6Z"/>'),
  volumeOff: svg('<path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.2l2.4 2.4.1-.6Zm2.5 0a7 7 0 0 1-.6 2.8l1.5 1.5A9 9 0 0 0 14 3.2v2.1a7 7 0 0 1 5 6.7ZM4.3 3 3 4.3 7.7 9H3v6h4l5 5v-6.7l4.3 4.3a7 7 0 0 1-2.3 1.2v2.1a9 9 0 0 0 3.7-1.8l2 2 1.3-1.3-9-9ZM12 4 9.9 6.1 12 8.2Z"/>'),
  close: svg('<path d="m6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6l5.6-5.6L5 6.4Z"/>'),
  flag: svg('<path d="M5 21V3h9l.4 2H20v10h-7l-.4-2H7v8Z"/>'),
  info: svg('<path d="M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm-1 8v7h2v-7Zm0-3v2h2V7Z"/>'),
  myLocation: svg('<path d="M11 1h2v3.1A8 8 0 0 1 19.9 11H23v2h-3.1A8 8 0 0 1 13 19.9V23h-2v-3.1A8 8 0 0 1 4.1 13H1v-2h3.1A8 8 0 0 1 11 4.1Zm1 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12Zm0 3a3 3 0 1 1 0 6 3 3 0 0 1 0-6Z"/>'),
} as const;
