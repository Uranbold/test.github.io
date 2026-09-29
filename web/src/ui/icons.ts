// 24 px icons (Material Symbols shapes, drawn as simple paths). Decorative: aria-hidden.
const svg = (path: string): string =>
  `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${path}</svg>`;

export const ICONS = {
  moon: svg('<path d="M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9Z"/>'),
  sun: svg(
    '<circle cx="12" cy="12" r="4.5"/><path d="M11 1h2v3h-2Zm0 19h2v3h-2ZM1 11h3v2H1Zm19 0h3v2h-3ZM4.2 5.6l1.4-1.4 2.1 2.1-1.4 1.4Zm12.1 12.1 1.4-1.4 2.1 2.1-1.4 1.4ZM4.2 18.4l2.1-2.1 1.4 1.4-2.1 2.1ZM16.3 6.3l2.1-2.1 1.4 1.4-2.1 2.1Z"/>',
  ),
  locate: svg(
    '<path d="M11 1h2v3.1A8 8 0 0 1 19.9 11H23v2h-3.1A8 8 0 0 1 13 19.9V23h-2v-3.1A8 8 0 0 1 4.1 13H1v-2h3.1A8 8 0 0 1 11 4.1Zm1 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12Z"/>',
  ),
  locateFilled: svg(
    '<path d="M11 1h2v3.1A8 8 0 0 1 19.9 11H23v2h-3.1A8 8 0 0 1 13 19.9V23h-2v-3.1A8 8 0 0 1 4.1 13H1v-2h3.1A8 8 0 0 1 11 4.1Zm1 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12Zm0 2.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7Z"/>',
  ),
  locateOff: svg(
    '<path d="M2.3 3.7 3.7 2.3l18 18-1.4 1.4-3.2-3.2A8 8 0 0 1 13 19.9V23h-2v-3.1A8 8 0 0 1 4.1 13H1v-2h3.1a8 8 0 0 1 1.5-3.9ZM7 8.4A6 6 0 0 0 15.6 17ZM11 1h2v3.1A8 8 0 0 1 19.9 11H23v2h-3.1a8 8 0 0 1-.9 2.8l-1.5-1.5A6 6 0 0 0 9.7 6.5L8.2 5a8 8 0 0 1 2.8-.9Z"/>',
  ),
  cloudOff: svg(
    '<path d="M3.3 2 2 3.3l3 3A7 7 0 0 0 6 20h11.7l2.9 2.9 1.4-1.4ZM6 18a5 5 0 0 1-.3-10l9.9 10Zm13.4-1.1A4 4 0 0 0 19 10a7 7 0 0 0-10.6-5.9L9.9 5.6A5 5 0 0 1 17 10v1h1a2 2 0 0 1 .9 3.8Z"/>',
  ),
  warning: svg('<path d="M1 21h22L12 2Zm12-3h-2v-2h2Zm0-4h-2v-4h2Z"/>'),
  mapOff: svg(
    '<path d="M3.3 2 2 3.3 4 5.3V19l5-2 6 2 2.7-1L20.7 21 22 19.7ZM9 15l-3 1.2V7.3l3 3Zm6 1.9-4-1.4v-3.2l4 4ZM9 5.1l6 2.1v4.5l2 2V6.3l3-1.2v9.7l1.8 1.8L22 16V3l-5 2-6-2-3.5 1.2 1.6 1.6Z"/>',
  ),
  error: svg('<path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm1 15h-2v-2h2Zm0-4h-2V7h2Z"/>'),
} as const;
