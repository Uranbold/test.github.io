#!/usr/bin/env bash
# NAV-008 AC 7 (and AC 6 machine part): TLS check of the staging host from OUTSIDE. Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md, cases TLS-01 .. TLS-09. Design: deployment-staging.md section 5.
#
#   tests/staging/nav008/tls-check.sh [HOST] [--https-port 443] [--http-port 80] [--connect ADDR]
#                                     [--cafile FILE] [--min-days 30] [--no-testssl]
#
#   HOST        default: STAGING_HOST from the environment or tests/staging/nav008/staging.local.env
#   --connect   dial this address instead of resolving HOST (before DNS exists). SNI and the Host header stay HOST.
#               The address is used on the command line only and never printed.
#   --cafile    extra trust anchor (self-test only; a real staging certificate must verify WITHOUT it)
#
# Needs: bash, openssl (1.1.1+), curl. Optional: testssl.sh on PATH (or TESTSSL="docker run --rm drwetter/testssl.sh").
# Run from a normal network WITHOUT a TLS-intercepting proxy or VPN: curl is called with --noproxy '*', and a
# middlebox that re-signs certificates makes TLS-01 fail (that is the intended signal).
# Exit 0 only if no check failed. MANUAL lines (renewal dry run) must be closed in the run report.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
nav8_load_env

HOST=""; HTTPS_PORT=443; HTTP_PORT=80; CONNECT=""; CAFILE=""; MIN_DAYS=30; USE_TESTSSL=1
while [ $# -gt 0 ]; do
  case "$1" in
    --https-port) HTTPS_PORT="$2"; shift 2 ;;
    --http-port)  HTTP_PORT="$2"; shift 2 ;;
    --connect)    CONNECT="$2"; shift 2 ;;
    --cafile)     CAFILE="$2"; shift 2 ;;
    --min-days)   MIN_DAYS="$2"; shift 2 ;;
    --no-testssl) USE_TESTSSL=0; shift ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *)            HOST="$1"; shift ;;
  esac
done
HOST="${HOST:-${STAGING_HOST:-}}"
[ -n "$HOST" ] || { echo "no host: pass HOST or set STAGING_HOST (staging.local.env)" >&2; exit 2; }
need openssl && need curl || { echo "needs openssl and curl" >&2; exit 2; }
mkdir -p "$NAV8_RESULTS"

DIAL="${CONNECT:-$HOST}"
case "$DIAL" in *:*) DIAL_HP="[$DIAL]:$HTTPS_PORT" ;; *) DIAL_HP="$DIAL:$HTTPS_PORT" ;; esac
CA_OPT=(); CURL_CA=(); [ -n "$CAFILE" ] && { CA_OPT=(-CAfile "$CAFILE"); CURL_CA=(--cacert "$CAFILE"); }
RESOLVE=()
[ -n "$CONNECT" ] && RESOLVE=(--resolve "$HOST:$HTTPS_PORT:$CONNECT" --resolve "$HOST:$HTTP_PORT:$CONNECT")
CURL=(curl -sS --noproxy '*' --max-time 15 "${RESOLVE[@]}" "${CURL_CA[@]}")
hp() { [ "$2" = "$3" ] && echo "$1://$HOST" || echo "$1://$HOST:$2"; }   # scheme port default
BASE="$(hp https "$HTTPS_PORT" 443)"; HBASE="$(hp http "$HTTP_PORT" 80)"
echo "NAV-008 TLS check: host=$HOST https=$HTTPS_PORT http=$HTTP_PORT connect=${CONNECT:+<given, not shown>} min_days=$MIN_DAYS"

sclient() { # extra openssl args...; stdin closed; 10 s cap
  timeout 15 openssl s_client -connect "$DIAL_HP" -servername "$HOST" "${CA_OPT[@]}" "$@" </dev/null 2>&1
}
handshake_ok() { grep -qE 'Cipher is [A-Z0-9_-]+' <<<"$1" && ! grep -q 'Cipher is (NONE)' <<<"$1"; }

# TLS-01 publicly trusted chain + hostname match (OpenSSL trust store)
out="$(sclient -verify_return_error -verify_hostname "$HOST")"
code="$(sed -n 's/.*Verify return code: \([0-9]*\).*/\1/p' <<<"$out" | tail -1)"
handshake_ok "$out" && [ "$code" = 0 ]; check "TLS-01.chain_trusted_and_hostname_matches (AC 7)" $? \
  "Verify return code: 0 with -verify_hostname $HOST" "code=${code:-none} $(grep -m1 -E 'verify error|Verify return code' <<<"$out" | redact_ips)"

# TLS-02 same through curl's trust store + the real endpoint (AC 6 machine part)
body="$("${CURL[@]}" -w '\n%{http_code} %{ssl_verify_result}' "$BASE/health" 2>&1)"; rc=$?
last="$(tail -1 <<<"$body")"
[ $rc -eq 0 ] && [ "${last%% *}" = 200 ] && grep -q '"status" *: *"ok"' <<<"$body"
check "TLS-02.health_200_ok_over_verified_https (AC 6, AC 7)" $? '200 {"status":"ok"}, curl exit 0' "curl exit $rc, $(tr '\n' ' ' <<<"$body" | head -c 160 | redact_ips)"

# TLS-03 validity >= MIN_DAYS
leaf="$(sclient 2>/dev/null | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' | sed '/-----END CERTIFICATE-----/q')"
if [ -z "$leaf" ]; then
  fail "TLS-03.valid_ge_${MIN_DAYS}_days (AC 7)" "a server certificate" "none received"
else
  end="$(openssl x509 -noout -enddate <<<"$leaf" | cut -d= -f2)"
  days="?"; e=$(date -u -d "$end" +%s 2>/dev/null) && days=$(( (e - $(date -u +%s)) / 86400 ))
  openssl x509 -noout -checkend $((MIN_DAYS * 86400)) <<<"$leaf" >/dev/null
  check "TLS-03.valid_ge_${MIN_DAYS}_days (AC 7)" $? ">= $MIN_DAYS days left" "notAfter=$end (${days} days)"
  info "TLS-03.issuer" "$(openssl x509 -noout -issuer <<<"$leaf" | sed 's/^issuer= *//')"
  info "TLS-03.subject_alt_names" "$(openssl x509 -noout -ext subjectAltName <<<"$leaf" 2>/dev/null | tail -n +2 | tr -d ' ' | redact_ips)"
fi

# TLS-04/05 protocol versions: 1.2 and 1.3 offered; 1.0 and 1.1 refused. SECLEVEL=0 on the client side, so a
# refusal is the server's, not a local OpenSSL policy. SSLv3/SSLv2 cannot be offered by OpenSSL 3 clients: testssl.sh.
for p in tls1_2 tls1_3; do
  out="$(sclient "-$p")"
  handshake_ok "$out"; check "TLS-04.offers_${p} (AC 7)" $? "handshake with -$p" "$(grep -m1 -E 'Protocol *:|Cipher is|alert|error' <<<"$out" | redact_ips)"
done
for p in tls1 tls1_1; do
  out="$(sclient "-$p" -cipher 'DEFAULT:@SECLEVEL=0')"
  if grep -qiE "unknown option|no such option|Option unknown" <<<"$out"; then
    skip "TLS-05.refuses_${p} (AC 7)" "local openssl was built without $p; use testssl.sh (TLS-08)"
  else
    ! handshake_ok "$out"; check "TLS-05.refuses_${p} (AC 7)" $? "no handshake with -$p" "$(grep -m1 -E 'Protocol *:|Cipher is|alert|error' <<<"$out" | redact_ips)"
  fi
done

# TLS-06 HTTP -> HTTPS: 308 (Caddy default, design section 5); AC 7 also accepts 301. Path and query kept.
res="$("${CURL[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "$HBASE/health?nav008=1" 2>&1)"
st="${res%% *}"; loc="${res#* }"; want="$BASE/health?nav008=1"
{ [ "$st" = 308 ] || [ "$st" = 301 ]; } && [ "$loc" = "$want" ]
check "TLS-06.http_redirects_to_https (AC 7)" $? "301/308 -> $want" "$(redact_ips <<<"$res")"
[ "$st" = 301 ] && info "TLS-06.status_note" "301 passes AC 7, but the design expects Caddy's 308 (method-preserving); tell backend"
res="$("${CURL[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' -X POST -H 'Content-Type: application/json' --data '{}' "$HBASE/v1/route" 2>&1)"
info "TLS-06.post_redirect" "POST http:// -> $(redact_ips <<<"$res") (clients must use https:// directly; a 301 would turn POST into GET)"

# TLS-07 response headers: no HTTP/3 advertised (design section 4: UDP 443 off); HSTS if present has no
# includeSubDomains/preload (design section 5: the company's other subdomains must not be affected).
hdrs="$("${CURL[@]}" -o /dev/null -D - "$BASE/health" 2>/dev/null | tr -d '\r')"
altsvc="$(grep -i '^alt-svc:' <<<"$hdrs")"
! grep -qi 'h3' <<<"$altsvc"; check "TLS-07.no_http3_advertised (design section 4)" $? "no Alt-Svc h3" "${altsvc:-<none>}"
hsts="$(grep -i '^strict-transport-security:' <<<"$hdrs")"
if [ -z "$hsts" ]; then info "TLS-07.hsts" "not sent (optional on staging)"
else ! grep -qiE 'includesubdomains|preload' <<<"$hsts"; check "TLS-07.hsts_scoped (design section 5)" $? "no includeSubDomains/preload" "$hsts"; fi
info "TLS-07.server_header" "$(grep -i '^server:' <<<"$hdrs" | cut -d' ' -f2- || true)"

# TLS-08 testssl.sh (if available): protocol matrix incl. SSLv2/SSLv3
TESTSSL="${TESTSSL:-$(command -v testssl.sh || command -v testssl || true)}"
if [ "$USE_TESTSSL" = 1 ] && [ -n "$TESTSSL" ]; then
  js="$NAV8_RESULTS/testssl-$(date -u +%Y%m%dT%H%M%SZ).json"
  ipopt=(); [ -n "$CONNECT" ] && ipopt=(--ip "$CONNECT")
  # shellcheck disable=SC2086
  $TESTSSL --quiet --color 0 --warnings off --protocols --server-defaults "${ipopt[@]}" --jsonfile "$js" "$HOST:$HTTPS_PORT" >/dev/null 2>&1
  if [ -s "$js" ] && need python3; then
    python3 - "$js" <<'PY'
import json, sys
rows = {r.get("id"): r.get("finding", "") for r in json.load(open(sys.argv[1])) if isinstance(r, dict)}
bad = [p for p in ("SSLv2", "SSLv3", "TLS1", "TLS1_1") if p in rows and "not offered" not in rows[p]]
good = [p for p in ("TLS1_2", "TLS1_3") if "offered" in rows.get(p, "") and "not offered" not in rows.get(p, "")]
ok = not bad and len(good) == 2
print(("PASS    " if ok else "FAIL    ") + "TLS-08.testssl_protocols (AC 7)" +
      ("" if ok else f"\n          expected: only TLS1_2, TLS1_3 offered\n          actual:   offered-bad={bad} offered-good={good}"))
sys.exit(0 if ok else 1)
PY
    [ $? -eq 0 ] && N_PASS=$((N_PASS+1)) || N_FAIL=$((N_FAIL+1))
    info "TLS-08.raw" "$js (contains the IP: keep it local, results/ is git-ignored)"
  else
    skip "TLS-08.testssl_protocols" "testssl.sh produced no JSON (or python3 missing); read $js by hand"
  fi
else
  skip "TLS-08.testssl_protocols" "testssl.sh not found (optional; TLS-04/05 cover TLS 1.0-1.3 with openssl)"
fi

# TLS-09 renewal: host-side, cannot be seen from outside
manual "TLS-09.renewal_dry_run (AC 7)" "operator runs the Caddy renewal dry run (deployment-staging.md section 5, Let's Encrypt staging CA, fresh data volume) and pastes the log excerpt into the run report"
nav8_summary
