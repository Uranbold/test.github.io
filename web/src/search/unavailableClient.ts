// Static public demo (NAV-002 AC 53–54, D44): search and reverse are turned off by the build setting VITE_STATIC_DEMO.
// This stand-in for GatewayClient answers every request with the "unavailable" outcome at once and never touches the
// network, so the list and the coordinate card show «Хайлт түр ажиллахгүй байна» with «Дахин оролдох» (NAV-003 AC 30,
// 33) well within 1 s, and no query text or coordinates reach any host (NAV-003 AC 46).
import type { GatewayClient, Outcome } from "./gateway";

const answer = (signal: AbortSignal): Promise<Outcome> =>
  Promise.resolve(signal.aborted ? { kind: "aborted" } : { kind: "unavailable" });

export const UNAVAILABLE_CLIENT: Pick<GatewayClient, "search" | "reverse"> = Object.freeze({
  search: (_q: unknown, signal: AbortSignal) => answer(signal),
  reverse: (_q: unknown, signal: AbortSignal) => answer(signal),
});

/**
 * A `fetch` that refuses every call without a network request. Handed to the (unused) GatewayClient when search and
 * reverse are off, as a second guard: even a code path that bypassed UNAVAILABLE_CLIENT could not send anything.
 */
export const refusingFetch: typeof fetch = () => Promise.reject(new TypeError("backend requests are disabled in this build"));
