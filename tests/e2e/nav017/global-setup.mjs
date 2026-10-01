// NAV-017 global setup: only checks that the local site from nav017/site.mjs is up (run.sh starts and stops it, so the
// Chromium run on the host and the WebKit run in Docker share one build and one server). Never touches the shared
// dev stack.
import { state } from './site.mjs';

export default async function globalSetup() {
  const s = state();
  const r = await fetch(s.origin + s.folders.a).catch((e) => ({ status: String(e) }));
  if (r.status !== 200) throw new Error(`NAV-017 site ${s.origin}${s.folders.a} answered ${r.status}; run node nav017/site.mjs start`);
}
