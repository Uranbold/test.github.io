#!/usr/bin/env bash
# NAV-008 QA tooling self-test (owner: qa-engineer). Test plan: docs/qa/test-plans/NAV-008.md section 6.
# Shows that every NAV-008 check PASSES on a conforming test double and FAILS (with the right check id) on each
# seeded fault, before any of them is trusted against the real staging host. Local only: binds 127.0.0.1 ports
# 18480 and 18511-18530, 18443/18081 (18491 is left for isolated-gateway-rl.sh), never touches the shared dev gateway on :8080.
#
#   tests/staging/nav008/selftest.sh [--quick]      # --quick skips the rate-limit fault matrix (about 4 minutes)
# Needs: python3, openssl, curl, bash; tests/.venv for the contract part (skipped if absent).
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo="$(cd "$here/../../.." && pwd)"
api="$repo/tests/api/nav008"; fx="$here/fixtures"
QUICK=0; [ "${1:-}" = --quick ] && QUICK=1
work="$(mktemp -d)"; pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; rm -rf "$work"; }
trap cleanup EXIT
OK=0; BAD=0
expect() { # name expected_exit(0|nonzero) must_contain_regex logfile exit
  local name="$1" want="$2" re="$3" log="$4" rc="$5" good=1
  if [ "$want" = 0 ]; then [ "$rc" = 0 ] || good=0; elif [ "$want" != any ]; then [ "$rc" != 0 ] || good=0; fi
  [ -z "$re" ] || grep -qE "$re" "$log" || good=0
  if [ $good = 1 ]; then OK=$((OK+1)); echo "ok    $name (exit $rc)"
  else BAD=$((BAD+1)); echo "NOT OK $name: exit $rc, wanted $want, pattern '$re'"; tail -15 "$log" | sed 's/^/        /'; fi
}
mock() { # port faults...
  local port="$1"; shift; local args=()
  for f in "$@"; do args+=(--fault "$f"); done
  python3 "$api/mock_gateway.py" --port "$port" "${args[@]}" > "$work/mock-$port.log" 2>&1 & pids+=($!)
  # Must be OUR mock (Server: nav008-mock), not something else already listening on the port.
  for _ in $(seq 1 40); do
    curl -s --noproxy '*' -D - -o /dev/null "http://127.0.0.1:$port/health" 2>/dev/null | grep -qi '^server: nav008-mock' && return 0; sleep 0.1
  done
  echo "mock on $port did not start"; cat "$work/mock-$port.log"; return 1
}
PY="$repo/tests/.venv/bin/python"; [ -x "$PY" ] || PY=""
ORIG=(--origin https://demo-staging.nav.test --origin http://localhost:5173)
RLQ=(--burst-seconds 2 --slow-seconds 3 --tile-requests 200 --misc-requests 60 --cooldown 1.5)

echo "== 1. staging_checks.py + contract_nav008.py on the conforming mock"
mock 18480 || exit 1
python3 "$api/staging_checks.py" --base-url http://127.0.0.1:18480 --group health --group cors "${ORIG[@]}" > "$work/l" 2>&1
expect "cors.good" 0 "0 failed" "$work/l" $?
python3 "$api/staging_checks.py" --base-url http://127.0.0.1:18480 --group ratelimit "${RLQ[@]}" > "$work/l" 2>&1
expect "ratelimit.good" 0 "RL07.burst_route.first_429_matches_RateLimited" "$work/l" $?
if [ -n "$PY" ]; then "$PY" "$api/contract_nav008.py" --base-url http://127.0.0.1:18480 > "$work/l" 2>&1
  expect "contract.good" 0 "PASS  CT8-R02.route_429_conforms" "$work/l" $?; fi
python3 "$api/staging_checks.py" --base-url http://localhost:8080 --group ratelimit > "$work/l" 2>&1
expect "ratelimit.refuses_shared_dev" 0 "SKIP  RL00.guard" "$work/l" $?

echo "== 2. CORS faults"
port=18511
for pair in "star:CORS02.preflight_acao_is_origin" "echo-any-origin:CORS08.evil_preflight_no_acao" \
            "suffix-match:CORS09.lookalike_no_acao" "expose-no-retry:CORS05.preflight_expose_all_spec_tokens"; do
  f="${pair%%:*}"; id="${pair#*:}"; mock $port "$f" || { BAD=$((BAD+1)); continue; }
  python3 "$api/staging_checks.py" --base-url "http://127.0.0.1:$port" --group cors "${ORIG[@]}" > "$work/l" 2>&1
  expect "cors.fault.$f" 1 "FAIL  $id" "$work/l" $?; port=$((port+1))
done

echo "== 3. 429 / rate-limit faults"
if [ "$QUICK" = 1 ]; then echo "(skipped: --quick)"; else
  for pair in "dup-acao-429:first_429_matches_RateLimited" "retry-after-date:first_429_matches_RateLimited" \
              "retry-after-zero:first_429_matches_RateLimited" "no-retry-after:first_429_matches_RateLimited" \
              "cache-429:first_429_matches_RateLimited" "html-429:first_429_matches_RateLimited" \
              "limit-tiles:RL04.tiles_ranges.never_429" "limit-health:RL05.health.never_429" \
              "tight-limit:RL02.slow_route.never_429" "no-limit:RL07.burst_route.at_least_one_429"; do
    f="${pair%%:*}"; id="${pair#*:}"; mock $port "$f" || { BAD=$((BAD+1)); port=$((port+1)); continue; }
    python3 "$api/staging_checks.py" --base-url "http://127.0.0.1:$port" --group ratelimit "${RLQ[@]}" > "$work/l" 2>&1
    expect "ratelimit.fault.$f" 1 "FAIL  .*$id" "$work/l" $?
    if [ -n "$PY" ]; then
      case "$f" in
        dup-acao-429|retry-after-date|retry-after-zero|no-retry-after|cache-429|html-429)
          "$PY" "$api/contract_nav008.py" --base-url "http://127.0.0.1:$port" > "$work/l" 2>&1
          expect "contract.fault.$f" 1 "FAIL  CT8-R0" "$work/l" $? ;;
        no-limit)
          "$PY" "$api/contract_nav008.py" --base-url "http://127.0.0.1:$port" --budget 200 > "$work/l" 2>&1
          expect "contract.no_429_is_skip" 0 "SKIP  CT8-R01.route_429" "$work/l" $? ;;
      esac
    fi
    port=$((port+1))
  done
fi

echo "== 4. tls-check.sh against a local TLS double (scratch CA; nothing is trusted system-wide)"
cd "$work" && openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 30 -subj "/CN=NAV008 selftest CA" >/dev/null 2>&1
printf 'subjectAltName=DNS:staging.nav.test\n' > ext
for d in 90 10; do
  openssl req -newkey rsa:2048 -nodes -keyout s$d.key -out s$d.csr -subj "/CN=staging.nav.test" >/dev/null 2>&1
  openssl x509 -req -in s$d.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out s$d.pem -days $d -extfile ext >/dev/null 2>&1
done
tls() { # name want regex cert mockargs... -- checkargs...
  local name="$1" want="$2" re="$3" cert="$4"; shift 4; local margs=() cargs=()
  while [ $# -gt 0 ] && [ "$1" != -- ]; do margs+=("$1"); shift; done; shift
  cargs=("$@")
  python3 "$fx/tls_mock.py" --cert "$work/$cert.pem" --key "$work/$cert.key" --host staging.nav.test --https-port 18443 --http-port 18081 "${margs[@]}" &
  local p=$!; sleep 0.8
  "$here/tls-check.sh" staging.nav.test --https-port 18443 --http-port 18081 --connect 127.0.0.1 --no-testssl "${cargs[@]}" > "$work/l" 2>&1
  expect "tls.$name" "$want" "$re" "$work/l" $?; kill $p 2>/dev/null; wait $p 2>/dev/null
}
tls good 0 "PASS    TLS-05.refuses_tls1_1" s90 -- --cafile "$work/ca.pem"
tls untrusted 1 "FAIL    TLS-01" s90 --
tls expires_in_10_days 1 "FAIL    TLS-03" s10 -- --cafile "$work/ca.pem"
tls offers_tls10 1 "FAIL    TLS-05.refuses_tls1 " s90 --allow-tls10 -- --cafile "$work/ca.pem"
tls redirect_302 1 "FAIL    TLS-06" s90 --redirect-status 302 -- --cafile "$work/ca.pem"
tls http3_advertised 1 "FAIL    TLS-07" s90 --alt-svc-h3 -- --cafile "$work/ca.pem"
cd "$repo" || exit 2

echo "== 5. portscan.sh parser, log-privacy-scan.sh, repo-checks.sh"
"$here/portscan.sh" --parse-only "$fx/nmap-good.gnmap" > "$work/l" 2>&1; expect "portscan.parse.good" 0 "0 failed" "$work/l" $?
"$here/portscan.sh" --parse-only "$fx/nmap-bad.gnmap" > "$work/l" 2>&1;  expect "portscan.parse.extra_ports" 1 "FAIL    PS-03.fixture.port_8080_closed" "$work/l" $?
bash "$here/log-privacy-scan.sh" --self-test "$fx/log-clean.txt" > "$work/l" 2>&1; expect "logscan.clean" 0 "no client IP" "$work/l" $?
bash "$here/log-privacy-scan.sh" --self-test "$fx/log-bad.txt" --markers "$fx/log-markers-fixture.txt" > "$work/l" 2>&1
expect "logscan.bad_all_7" 1 "7 finding" "$work/l" $?
"$here/repo-checks.sh" > "$work/l" 2>&1; expect "repo-checks.runs" any "RS-02.no_private_keys_or_tokens" "$work/l" 0

echo "== 6. phone-kit.sh on the conforming mock (http, loopback)"
pk=(env STAGING_HOST=staging.nav.test BASE_URL=http://127.0.0.1:18480 C1_URL=http://127.0.0.1:18480/health OPERATOR=Selftest NET=lo
    PLACE=scratch WIFI_OFF=yes SAMPLES=5)
"${pk[@]}" OUT_DIR="$work/pk1" bash "$here/phone-kit.sh" info > "$work/l" 2>&1; expect "phone.info" 0 "INFO  session" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk1" bash "$here/phone-kit.sh" health > "$work/l" 2>&1; expect "phone.health" 0 "PASS  AC6.health" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk1" bash "$here/phone-kit.sh" c1 > "$work/l" 2>&1;     expect "phone.c1" 0 "RESULT=RECORDED \(median" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk1" bash "$here/phone-kit.sh" route-p95 > "$work/l" 2>&1; expect "phone.route_p95" 0 "PASS  AC9 p95" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk2" RTT_FLAG_MS=0 bash "$here/phone-kit.sh" c1 > "$work/l" 2>&1; expect "phone.c1_flag_d26" 0 "FLAG_D26" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk2" bash "$here/phone-kit.sh" route-p95 > "$work/l" 2>&1; expect "phone.route_refused_until_po" 1 "AC9 not run" "$work/l" $?
"${pk[@]}" OUT_DIR="$work/pk3" C1_URL=http://127.0.0.1:9/ bash "$here/phone-kit.sh" c1 > "$work/l" 2>&1; expect "phone.c1_timeouts_repeat" 1 "RESULT=REPEAT" "$work/l" $?
if ! ls "$work"/pk1/session-*.csv >/dev/null 2>&1 || grep -rqE '([0-9]{1,3}\.){3}[0-9]{1,3}' "$work"/pk1/session-*.csv "$work"/pk1/*.log; then BAD=$((BAD+1)); echo "NOT OK phone.no_ip_in_session_csv"; else OK=$((OK+1)); echo "ok    phone.no_ip_in_session_csv"; fi

echo; echo "self-test: $OK ok, $BAD not ok"
[ "$BAD" -eq 0 ]
