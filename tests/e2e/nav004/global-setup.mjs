// NAV-004 global setup (story "Test approach", binding):
// 1. Run only when the gateway /health is 200. If not, wait and retry. Never restart anything.
// 2. Make sure the NAV-004 dev server serves the web app WITH the route preview slot, and the static server the build.
import { DEV_PORT, STATIC_PORT } from './playwright.config.mjs';

const HEALTH = 'http://localhost:8080/health';
const WAIT_MS = Number(process.env.NAV004_HEALTH_WAIT_MS ?? 15 * 60_000);

export default async function globalSetup() {
  const t0 = Date.now();
  let last = '';
  for (;;) {
    try {
      const r = await fetch(HEALTH);
      if (r.status === 200) break;
      last = `HTTP ${r.status}`;
    } catch (e) {
      last = String(e.cause?.code ?? e.message);
    }
    if (Date.now() - t0 > WAIT_MS) throw new Error(`gateway ${HEALTH} not 200 after ${WAIT_MS / 1000}s (last: ${last}); NAV-004 E2E not run`);
    console.log(`[nav004] waiting for ${HEALTH} (${last}) ...`);
    await new Promise((r) => setTimeout(r, 15_000));
  }
  const html = await (await fetch(`http://localhost:${DEV_PORT}/`)).text();
  if (!html.includes('/src/main.ts') || !html.includes('data-testid="route-slot"')) {
    throw new Error(`http://localhost:${DEV_PORT} does not serve the NAV-004 web app (Vite dev server with the route slot).`);
  }
  const shtml = await (await fetch(`http://localhost:${STATIC_PORT}/`)).text();
  if (shtml.includes('/src/main.ts')) throw new Error(`http://localhost:${STATIC_PORT} serves a dev build, not the static production build.`);
}
