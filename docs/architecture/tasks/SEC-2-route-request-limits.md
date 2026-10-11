# SEC-2 task breakdown: route request limits on `/v1/route`

- **Change request:** SEC-2 (D213; PO answers D216–D225, "All as recommended", 2026-10-11; audit finding BE-1 medium). It is recorded as change logs, with no SEC-2 story (D224). The main ACs are **NAV-001 AC 44–49**. Client ACs: NAV-004 AC 35/36, NAV-011 AC 24, NAV-005 AC 7/49, NAV-018 AC 17, NAV-021 AC 6/7. NAV-008 AC 13 note.
- **Design:** [ADR-0019](../adr/0019-route-request-limits.md); [ADR-0017 Amendment A6](../adr/0017-offline-mongolia-pack-android.md) (device unchanged); [ADR-0002 Amendment 4](../adr/0002-local-stack-gateway-and-api-surface.md) (pointer).
- **Contract:** `openapi.yaml` **0.7.0** (see its change log).
- **Owner of this file:** architect. **Date:** 2026-10-11. Task list only, no code.
- **Process:** light QA (PO preference), minimal change. Backend runs **before SEC-4A, strictly serial** in `backend/gateway/**` (D225).

## Boundary
- **Backend engineer:** `backend/**`, `infra/**`.
- **Mobile engineer:**
  - web: the car message only;
  - Android: the car message only;
  - iOS: no client yet.
- **No client change** for 503 `RoutingBusy`, the timeouts, the request bodies, reroute pacing, `RouteOutcome`, `ReroutePolicy` or `OnDeviceConfig`.
- **QA:** light delta set (below).
- **Security-engineer:** Verify-stage review (BE-1 close-out).
- **UX:** N24 state rows in the NAV-004/005/011/018 screen specs. The N11 layout is reused, with no new tokens.

## Backend task list

**B1. Valhalla limits and timeout, one source, applied at serve time (ADR-0019 §1, §2).**
- `scripts/lib.sh`: add one function (proposed `valhalla_limit_args`) that emits the `valhalla_build_config` flags for the ADR-0019 §1 table and `--httpd-service-timeout-seconds "$VALHALLA_TIMEOUT_SECONDS"`. The rule: every `service_limits` section with `max_locations` > 10 gets 10, and every section with `max_distance` > 3,000,000 gets 3,000,000.
- `scripts/valhalla-build.sh` `make_config`: append those flags. Do **not** add them to `valhalla_fingerprint`, because a limit change must never rebuild the graph.
- `scripts/valhalla-serve.sh`:
  - write a container-local runtime config (for example `/tmp/valhalla.json`) = `$DATA/valhalla/valhalla.json` with `service_limits` and `httpd.service.timeout_seconds` overlaid (from the same values), and start `valhalla_service` on it. `/data` stays read-only;
  - validate `VALHALLA_TIMEOUT_SECONDS` (integer 1–9, default 8). An invalid value makes it refuse to start, with a log line;
  - log the effective values once (no PII).
- Checks:
  - NAV-001 AC 44: P1→X3 and P1→X5 `auto` give 400 `DistanceExceeded` < 1 s; P1→X4 gives not `DistanceExceeded`; `bus`, `taxi`, `truck`, `motorcycle`, `motor_scooter` give `DistanceExceeded`;
  - AC 45: L1→L2 `auto` gives 200 with `alternates` 0 and 2, < 10 s each, times recorded;
  - AC 46: 10 locations give 200 and 11 give 400 < 1 s on all 8 costings;
  - AC 47: walk/bike P1→X2 give 400; P1→X1 bike and walk give 200 < 10 s; P6→P3 gives 200;
  - all of these hold on a stack **started over the existing `data/` build without a rebuild** (section H preamble), and in one NAV-006 slot lane;
  - `VALHALLA_TIMEOUT_SECONDS=10` and `abc` each make the valhalla service refuse to start.

**B2. Configuration pass-through and `.env.example` (ADR-0019 §6).**
- `compose.yaml`: pass `VALHALLA_TIMEOUT_SECONDS` to `valhalla` (and to `valhalla-build` if `make_config` reads it). Pass `GATEWAY_ROUTE_MAX_INFLIGHT` and `GATEWAY_ROUTE_MAX_INFLIGHT_PER_IP` to `gateway`.
- `compose.slots.yaml`: the same, for the `x-valhalla-lane` anchor (blue and green) and `x-gateway`.
- `backend/.env.example` and `infra/staging/.env.example`: the three keys, with the one-line comments from ADR-0019 §6 and the defaults 8 / 8 / 4.
- Merge with the NAV-006 F1 edits in the same files. Never revert them.
- Checks: NAV-001 AC 48 and AC 49 preconditions (keys present with comments, AC 3 style); `infra/ci/staging-static-checks.sh` still passes; `docker compose config` is valid for both compose files and the staging overlay.

**B3. Gateway concurrency cap and the `/v1/route` 5xx mapping (ADR-0019 §2, §3).**
- New `gateway/entrypoint/17-route-limits.sh` writes `conf.d/02-route-limits.conf`:
  - the two `map`s (OPTIONS and other URIs get an empty key; the per-IP key is `$binary_remote_addr` after `real_ip`);
  - `limit_conn_zone` global and per IP;
  - http-level `limit_conn` with the two values;
  - `limit_conn_log_level info`;
  - `limit_conn_status 529` (private);
  - validation: integers ≥ 1, otherwise exit non-zero; no "off" value;
  - one JSON start-up log line like the one in `16-rate-limits.sh`.
- `gateway/templates/default.conf.template`, ROUTE location only:
  - `proxy_intercept_errors on`;
  - `error_page 500 502 503 = @route_unavailable` (status 502; upstream 500 added in implementation, confirmed in the Verify stage);
  - `error_page 504 = @route_timeout` (unchanged);
  - `error_page 529 = @route_busy`;
  - **no** `error_page 400`.
- New `@route_busy`: CORS snippet once, `add_header Retry-After 1 always`, `add_header Cache-Control no-store always`, `default_type application/json`, `return 503 '{"code":"RoutingBusy","message":"routing service busy"}'`.
- Update the template header comment.
- SEARCH, REVERSE, TILES, PACKS and HEALTH do not change.
- Checks:
  - NAV-001 AC 49: per IP, the 5th request with 4 slow ones in flight gives 503 `RoutingBusy` < 1 s; `Retry-After` integer ≥ 1 once; `no-store` once; ACAO and ACEH once each, with ACEH listing `Retry-After`; never 502; the 4 in flight are unaffected; search, reverse, tiles and health keep working;
  - global cap: with `GATEWAY_ROUTE_MAX_INFLIGHT=2`, the 3rd request gets 503;
  - AC 35: 0 × 503 and p95 ≤ 500 ms;
  - unchanged: AC 32/33 (400 bodies still pass through raw), AC 34 (valhalla stopped gives 502 `UpstreamUnavailable` ≤ 5 s), AC 43 / NFR-C3 (single CORS headers on every route status);
  - no client IP in `docker compose logs gateway` after a cap burst.

**B4. Timeout shaping (ADR-0019 §2).**
- With `VALHALLA_TIMEOUT_SECONDS=1`, the slow request (P1→X1 `pedestrian`, or L1→L2 `auto` with `alternates: 2`) returns 504 `UpstreamTimeout` JSON within 3 s, with single CORS headers, never Valhalla's raw body.
- Record the time to 200 of a P1→P3 request sent 1 s later (INFO).
- Checks: NAV-001 AC 48.

**B5. Checks in `scripts/` (also used by NAV-006 slot verification).**
- `contract_check.py`:
  - the new `carTooFar` request example is sent automatically (400 must validate against `RouteBadRequest`);
  - add an explicit 11-location case (expect 400 and a documented shape);
  - add an opt-in `--route-cap` case. It holds `GATEWAY_ROUTE_MAX_INFLIGHT_PER_IP` slow requests and validates one 503 against `components/responses/RoutingBusy` (headers and body).
- `smoke.py`: one fast check, P1→X3 `auto` gives 400 `DistanceExceeded`. This makes every slot switch verify that the limits are served.
- `rate_limit_check.py`: in burst-route, accept 503 that validates as `RoutingBusy` next to 429. The normal scenario must still have 0 errors.
- Checks: `make contract` and `make smoke` pass on dev; `rate_limit_check.py` normal and burst scenarios pass against an isolated gateway with `GATEWAY_RATE_LIMIT=on` (NAV-008 AC 13).

**B6. Measurements (ADR-0019 F2, implementation record).** Record in `backend/README.md` and in the handoff:
- F2a: P1→X5 result, and whether a 3-location request whose legs are each < 3,000 km but sum to > 3,000 km is rejected (summed or per pair);
- F2b: the exact OSRM code and message for 11 locations;
- F2c: Valhalla's timeout status and body, and the AC 48 INFO value;
- latency of P1→X1 on foot and of a bike request near 500 km on the reference machine.

The architect then updates the `tooManyLocations` example and ADR-0019. If walk or bike exceeds 8 s, the architect lowers that limit (D217).

*Verify stage (2026-10-11):* done. 11 locations give 400 `InvalidValue` (example and texts updated in `openapi.yaml` 0.7.0); distance is summed; Valhalla's timeout answer is a plain-text 504 with about 1 s granularity; walk and bike near the limits stay far below 8 s, so no D217 change. See the ADR-0019 implementation record.

## Mobile task list: web (NAV-004 AC 35)

**MW1. Car `DistanceExceeded` → N24.**
- `web/src/route/routeController.ts` `apply()`: `distanceExceeded` on car shows a new state (proposed `too-far-car`) instead of `no-route`. Walk and bike keep `too-far`. No retry.
- `web/src/ui/routePreview.ts`: a state row `"too-far-car": { key: "route.tooFarCar", icon: ROUTE_ICONS.info }` with the same layout as `too-far` (UX: info icon, no error colour, no new tokens).
- `web/src/i18n/mn.json`: `route.tooFarCar` = «Энэ зай машинаар хэт хол байна». `en.json`: "This distance is too far by car". Both exact glossary N24 strings. NAV-003 shares these files: merge only.
- No inline script or style: the SEC-4B build-time CSP hashes must stay valid.
- 503 `RoutingBusy` needs no change (`routeClient.ts` already maps `status >= 500` to `unavailable`).
- Checks:
  - NAV-004 AC 35: car P1→X3 (mocked 400 `DistanceExceeded`) shows N24; walk and bike show N11; no retry button;
  - AC 36: a mocked 503 `RoutingBusy` shows N8 with «Дахин оролдох»;
  - `routeController.test.ts`: change the old "DistanceExceeded on car → no route" case to N24;
  - `npm run check:glossary`, `npm run check:i18n`, `npm run typecheck`, `npm test` and `npm run build` (CSP verifier) all pass.

## Mobile task list: Android (NAV-011 AC 24, NAV-005 AC 7, NAV-018 AC 17, NAV-021 AC 6)

**MA1. Car `TooFar` → N24.**
- `preview/PreviewController.kt` (line 271): `RouteOutcome.TooFar` on `TravelMode.CAR` gives a new `PreviewResult.TooFarCar`, no longer `NoRoute`. Walk and bike keep `PreviewResult.TooFar`. The same path covers the server answer and the NAV-021 on-device `TooFar`.
- `ui/screens/BrowseOverlay.kt`: render `TooFarCar` with the N11 layout and N24 text (info icon, no retry).
- `i18n/StringKey.kt`, `res/values/strings.xml` (mn, default) «Энэ зай машинаар хэт хол байна», and `res/values-en/strings.xml` "This distance is too far by car".
- No change to `RouteOutcome`, `ReroutePolicy` (it already treats `TooFar` as no-route), `OnDeviceConfig` or `OnDeviceRouteRequester`.
- 503 `RoutingBusy` needs no change (it is `Unavailable`: back-off, or fallback with a pack).
- NAV-019 shares `strings.xml` and `PreviewController.kt`: never revert its edits.
- No new dependency, so `gradle/verification-metadata.xml` and the lockfiles stay valid (SEC-4B).
- Checks:
  - NAV-011 AC 24 / NAV-005 AC 7 / NAV-018 AC 17: car `DistanceExceeded` shows N24; walk and bike show N11;
  - NAV-021 AC 6: on-device car `TooFar` shows N24;
  - `PreviewControllerTest`, `Nav011OverlayTest`, `QaNav018Test` updated;
  - `FallbackRouteRequesterTest`: 503 triggers the fallback;
  - the glossary string-match tests accept N24;
  - `./gradlew testDebugUnitTest lintDebug assembleDebug` pass with `--dependency-verification strict`.

## QA (light, per PO)
- `tests/api/nav001`: the AC 44–49 checks (reference points X3–X5, L1, L2; cap and timeout cases with the documented env overrides).
- `tests/api/nav004/contract_nav004.py`: the car `DistanceExceeded` example and the 503 `RoutingBusy` shape.
- `tests/e2e/nav004/states.test.mjs`: car AC 35 expects N24; 503 still shows N8.
- Android unit tests as in MA1.
- Test-plan delta rows for NAV-001, NAV-004 and NAV-005.

## Dependencies and order
1. **B1 → B2 → B3 → B4 → B5 → B6** (one backend engineer). B3 needs B2's keys. B5's `--route-cap` case needs B3.
2. **MW1 ∥ MA1**, in parallel with the backend. Both use mocked responses and depend only on the contract, not on B1–B6.
3. QA ∥ architect integration review ∥ security review, after both.
4. Afterwards: SEC-4A (BE-2 re-keys `nav_rc_client` together with the rate-limit key for IPv6 /64; BE-4 keeps `VALHALLA_THREADS` × 2 ≥ `GATEWAY_ROUTE_MAX_INFLIGHT`).
5. Before any public exposure: the staging cap measurement (`deployment-staging.md` §7.2).
