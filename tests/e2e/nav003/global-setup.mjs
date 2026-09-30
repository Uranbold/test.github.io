// NAV-003 global setup (story "Test approach", binding):
// 1. Run only when the gateway /health is 200. If not, wait and retry. Never restart anything.
// 2. Make sure port 5173 serves the web app WITH the NAV-003 search box.
const HEALTH = 'http://localhost:8080/health';
const WAIT_MS = Number(process.env.NAV003_HEALTH_WAIT_MS ?? 15 * 60_000);

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
    if (Date.now() - t0 > WAIT_MS) throw new Error(`gateway ${HEALTH} not 200 after ${WAIT_MS / 1000}s (last: ${last}); NAV-003 E2E not run`);
    console.log(`[nav003] waiting for ${HEALTH} (${last}) ...`);
    await new Promise((r) => setTimeout(r, 15_000));
  }
  const html = await (await fetch('http://localhost:5173/')).text();
  if (!html.includes('/src/main.ts') || !html.includes('data-testid="search-input"')) {
    throw new Error('http://localhost:5173 does not serve the NAV-003 web app (web/ Vite dev server with the search box).');
  }
}
