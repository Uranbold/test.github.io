#!/usr/bin/env bash
# NAV-008 phone kit for the tester in Ulaanbaatar: AC 6 (/health), AC 8 (TCP connect RTT, spike section 5 "c1"
# protocol, with egress ASN) and AC 9 (tethered postRoute p95). Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md; step-by-step: docs/qa/checklists/NAV-008-phone-ub.md
#
# ONE self-contained file: copy it to the laptop (tethered by USB to the phone) or to Termux on Android.
# Needs only bash, curl, awk, sort (Linux, macOS, Git Bash on Windows 10+, Termux: `pkg install curl`).
#
#   export STAGING_HOST=staging.<domain> OPERATOR=Unitel NET=4G PLACE=Sukhbaatar DEVICE="Pixel 7" WIFI_OFF=yes
#   bash phone-kit.sh all            # info + health + c1 (20 samples); then, if no D26 flag, route-p95
#   bash phone-kit.sh info | health | c1 | route-p95
#
# Rules (spike section 5): phone Wi-Fi OFF; on a tethered laptop also Wi-Fi and Ethernet OFF; no VPN, no proxy.
# The kit never stores or prints your public IP: only the egress ASN and its holder name are recorded.
# Results go to $OUT_DIR (default ./nav008-results). Send the whole folder to QA.
#
# AC 8 / D26 rule: if the c1 median for this operator is > 100 ms, the kit prints FLAG_D26 and route-p95 refuses
# to run until the PO has responded (then run it with D26_PO_RESPONSE=recorded).
set -uo pipefail
CMD="${1:-all}"
STAGING_HOST="${STAGING_HOST:-}"
BASE_URL="${BASE_URL:-https://$STAGING_HOST}"
C1_URL="${C1_URL:-https://$STAGING_HOST/health}"   # TCP handshake target (port 443); override only for self-tests
OPERATOR="${OPERATOR:-unknown}"; NET="${NET:-unknown}"; PLACE="${PLACE:-unknown}"; DEVICE="${DEVICE:-unknown}"
WIFI_OFF="${WIFI_OFF:-unconfirmed}"; SAMPLES="${SAMPLES:-20}"; ROUTE_N="${ROUTE_N:-20}"
OUT_DIR="${OUT_DIR:-./nav008-results}"; CURL_EXTRA="${CURL_EXTRA:-}"
RTT_FLAG_MS="${RTT_FLAG_MS:-100}"; P95_LIMIT_MS="${P95_LIMIT_MS:-500}"
STAMP="$(date +%Y%m%d-%H%M)"; TAG="${OPERATOR// /_}-${STAMP}"
mkdir -p "$OUT_DIR"
[ -n "$STAGING_HOST" ] || [ "$CMD" = help ] || { echo "Set STAGING_HOST=staging.<domain> first (see the checklist)." >&2; exit 2; }
# shellcheck disable=SC2206
CX=($CURL_EXTRA)
C=(curl -s --noproxy '*' "${CX[@]}")
FAILS=0
say()  { echo "$*" | tee -a "$OUT_DIR/session-$TAG.log"; }
ok()   { say "PASS  $*"; }
bad()  { say "FAIL  $*"; FAILS=$((FAILS+1)); }

stats() { # stdin: numbers, one per line -> "n median p95 max" (median: mean of the middle two; p95: nearest rank)
  sort -n | awk '{a[NR]=$1} END {if (NR==0) {print "0 - - -"; exit}
    m = (NR%2) ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2; r = int(0.95*NR); if (r < 0.95*NR) r++; if (r < 1) r = 1;
    printf "%d %.1f %.1f %.1f\n", NR, m, a[r], a[NR]}'
}

info_cmd() {
  for v in HTTPS_PROXY https_proxy HTTP_PROXY http_proxy ALL_PROXY all_proxy; do
    [ -n "${!v:-}" ] && say "WARN  $v is set. The kit bypasses it (--noproxy), but a VPN or system proxy must be OFF."
  done
  local ip asn holder fam
  ip="$("${C[@]}" --max-time 10 https://stat.ripe.net/data/whats-my-ip/data.json | grep -oE '"ip" *: *"[^"]+"' | sed -E 's/.*"([^"]+)"$/\1/')"
  if [ -n "$ip" ]; then
    case "$ip" in *:*) fam=IPv6 ;; *) fam=IPv4 ;; esac
    local po; po="$("${C[@]}" --max-time 15 "https://stat.ripe.net/data/prefix-overview/data.json?resource=$ip")"
    asn="$(grep -oE '"asn" *: *[0-9]+' <<<"$po" | head -1 | grep -oE '[0-9]+$')"
    holder="$(grep -oE '"holder" *: *"[^"]*"' <<<"$po" | head -1 | sed -E 's/.*"([^"]*)"$/\1/' | tr ',' ' ')"
  fi
  unset ip  # never stored
  echo "date,time,operator,net,place,device,wifi_off,egress_family,egress_asn,asn_holder" > "$OUT_DIR/session-$TAG.csv"
  echo "$(date +%F),$(date +%T),$OPERATOR,$NET,$PLACE,$DEVICE,$WIFI_OFF,${fam:-unknown},AS${asn:-unknown},${holder:-unknown}" >> "$OUT_DIR/session-$TAG.csv"
  say "INFO  session: operator=$OPERATOR net=$NET place=$PLACE device=$DEVICE wifi_off=$WIFI_OFF egress=${fam:-?} AS${asn:-unknown} (${holder:-unknown})"
  [ "$WIFI_OFF" = yes ] || say "WARN  WIFI_OFF is not 'yes'. Turn Wi-Fi off (phone and laptop), then set WIFI_OFF=yes."
}

health_cmd() {  # AC 6 machine part + IPv6/NAT64 edge case
  local out rc
  out="$("${C[@]}" --max-time 15 -w '\n%{http_code} %{time_total}' "$BASE_URL/health")"; rc=$?
  local code; code="$(tail -1 <<<"$out" | cut -d' ' -f1)"
  if [ $rc -eq 0 ] && [ "$code" = 200 ] && grep -q '"status" *: *"ok"' <<<"$out"; then ok "AC6.health 200 {\"status\":\"ok\"}, certificate verified (curl exit 0)"
  else bad "AC6.health expected 200 {\"status\":\"ok\"} with a trusted certificate; got curl exit $rc, HTTP ${code:-none} (exit 60 = certificate problem)"; fi
  for fam in 4 6; do
    "${C[@]}" -"$fam" --max-time 10 -o /dev/null "$BASE_URL/health"; rc=$?
    case $rc in 0) say "INFO  health over IPv$fam: ok" ;; 6) say "INFO  health over IPv$fam: no address for this family (no A/AAAA or no DNS64)" ;;
                *) say "INFO  health over IPv$fam: failed (curl exit $rc)" ;; esac
  done
  say "MANUAL AC6 also open https://$STAGING_HOST/health in the phone's default browser (Chrome / Safari): no certificate warning, shows {\"status\":\"ok\"}. Take a screenshot."
}

c1_cmd() {  # AC 8: spike section 5.4 metric = time_connect - time_namelookup, new TCP connection per sample
  local csv="$OUT_DIR/c1-$TAG.csv" sum="$OUT_DIR/c1-summary-$TAG.txt" i out nl cn ms st to=0
  echo "date,time,operator,net,key,host,sample,connect_ms,status" > "$csv"
  "${C[@]}" -k -o /dev/null --max-time 5 "$C1_URL" >/dev/null 2>&1   # warm-up (DNS cache), discarded
  for i in $(seq 1 "$SAMPLES"); do
    out="$("${C[@]}" -k -o /dev/null --connect-timeout 5 --max-time 5 -w '%{time_namelookup} %{time_connect}' "$C1_URL")"
    nl="${out%% *}"; cn="${out##* }"
    if [ -z "$cn" ] || [ "$cn" = "0.000000" ] || [ "$cn" = "0.000" ]; then st=timeout; ms=; to=$((to+1))
    else st=ok; ms="$(awk -v n="$nl" -v c="$cn" 'BEGIN{printf "%.1f",(c-n)*1000}')"; fi
    echo "$(date +%F),$(date +%T),$OPERATOR,$NET,STAGING,$STAGING_HOST,$i,$ms,$st" >> "$csv"
    sleep 1
  done
  read -r n med p95 max < <(awk -F, 'NR>1 && $9=="ok" {print $8}' "$csv" | stats)
  {
    echo "operator=$OPERATOR net=$NET place=$PLACE date=$(date +%F) samples=$SAMPLES ok=$n timeouts=$to median_ms=$med p95_ms=$p95 max_ms=$max"
    if [ "$to" -gt 2 ]; then echo "RESULT=REPEAT (> 2 timeouts out of $SAMPLES: this run does not count, AC 8)"
    elif awk -v m="$med" -v f="$RTT_FLAG_MS" 'BEGIN{exit !(m+0 > f+0)}'; then
      echo "RESULT=RECORDED FLAG_D26 median $med ms > $RTT_FLAG_MS ms: tell QA/orchestrator NOW; the PO responds before AC 9 runs"
    else echo "RESULT=RECORDED (median <= $RTT_FLAG_MS ms, D26 expected value)"; fi
    [ -f "$OUT_DIR/session-$TAG.csv" ] && echo "egress=$(tail -1 "$OUT_DIR/session-$TAG.csv" | cut -d, -f8-10)"
  } > "$sum"
  while read -r l; do say "INFO  c1 $l"; done < "$sum"
  grep -q "RESULT=REPEAT" "$sum" && FAILS=$((FAILS+1))
  return 0
}

route_cmd() {  # AC 9: 20 sequential postRoute P1->P2 over ONE kept-alive HTTPS connection, p95 <= 500 ms
  if grep -qs FLAG_D26 "$OUT_DIR"/c1-summary-*.txt && [ "${D26_PO_RESPONSE:-}" != recorded ]; then
    bad "AC9 not run: a c1 median is > $RTT_FLAG_MS ms (FLAG_D26). AC 8 says the PO responds first. When that is recorded, rerun with D26_PO_RESPONSE=recorded."
    return 0
  fi
  local d="$OUT_DIR/route-$TAG" args=() i
  mkdir -p "$d"
  printf '%s' '{"locations":[{"lat":47.9189,"lon":106.9176},{"lat":47.9139,"lon":106.9044}],"costing":"auto","format":"osrm","banner_instructions":true,"voice_instructions":true,"language":"mn-MN","units":"kilometers"}' > "$d/body.json"
  for i in $(seq 1 "$ROUTE_N"); do args+=(-o "$d/r$i.json" "$BASE_URL/v1/route"); done
  # warm-up GET /health opens the connection (TLS handshake), --next keeps it for the POSTs
  "${C[@]}" --max-time 20 -o /dev/null -w 'warmup %{http_code} %{num_connects} %{time_total}\n' "$BASE_URL/health" \
     --next -s --noproxy '*' "${CX[@]}" --max-time 20 -H 'Content-Type: application/json' --data-binary "@$d/body.json" \
     -w 'route %{http_code} %{num_connects} %{time_total}\n' "${args[@]}" > "$d/timings.txt"
  local n200 reconnects okbodies
  n200="$(awk '$1=="route" && $2==200' "$d/timings.txt" | wc -l | tr -d ' ')"
  reconnects="$(awk '$1=="route" && $3>0' "$d/timings.txt" | wc -l | tr -d ' ')"
  okbodies="$(grep -l '"code" *: *"Ok"' "$d"/r*.json 2>/dev/null | wc -l | tr -d ' ')"
  read -r n med p95 max < <(awk '$1=="route" {printf "%.1f\n", $4*1000}' "$d/timings.txt" | stats)
  echo "operator=$OPERATOR net=$NET n=$n ok200=$n200 okbody=$okbodies new_connections=$reconnects median_ms=$med p95_ms=$p95 max_ms=$max" > "$d/summary.txt"
  say "INFO  AC9 $(cat "$d/summary.txt")"
  [ "$n200" = "$ROUTE_N" ] && [ "$okbodies" = "$ROUTE_N" ] || bad "AC9 expected $ROUTE_N x 200 with code Ok; got $n200 x 200, $okbodies bodies with Ok"
  [ "$reconnects" = 0 ] || say "WARN  $reconnects of $ROUTE_N requests opened a new connection (keep-alive broken); report it, the p95 still counts"
  if awk -v p="$p95" -v l="$P95_LIMIT_MS" 'BEGIN{exit !(p+0 <= l+0)}'; then ok "AC9 p95 $p95 ms <= $P95_LIMIT_MS ms"
  else bad "AC9 expected p95 <= $P95_LIMIT_MS ms; got $p95 ms"; fi
}

case "$CMD" in
  info) info_cmd ;;
  health) health_cmd ;;
  c1) c1_cmd ;;
  route-p95) route_cmd ;;
  all) info_cmd; health_cmd; c1_cmd; route_cmd ;;
  help|-h|--help) sed -n '2,22p' "$0"; exit 0 ;;
  *) echo "unknown command $CMD (info | health | c1 | route-p95 | all)" >&2; exit 2 ;;
esac
say "DONE  $CMD: $FAILS problem(s). Send the folder $OUT_DIR to QA."
[ "$FAILS" -eq 0 ]
