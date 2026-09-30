#!/usr/bin/env bash
# NAV-008 AC 15 (API downtime during the rebuild), AC 17 (alert drill timing), AC 20 (restore drill clock).
# Polls GET $BASE_URL/health from OUTSIDE every INTERVAL seconds and prints each up/down transition with UTC time.
# Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-008.md, cases RB-03, AL-01, RR-02.
#
#   tests/staging/nav008/downtime-probe.sh [--interval 2] [--duration 3600] [--route]
#   --route   also POST a small P1->P2 route each tick (shows Caddy backstop 502 vs gateway JSON during a rebuild)
#
# Stop with Ctrl-C (the summary is printed on exit). /health is never rate limited, so the probe cannot trip limits.
# Output (CSV of transitions + summary) goes to tests/staging/nav008/results/ (git-ignored).
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
nav8_load_env
INTERVAL=2; DURATION=3600; ROUTE=0
while [ $# -gt 0 ]; do
  case "$1" in --interval) INTERVAL="$2"; shift 2 ;; --duration) DURATION="$2"; shift 2 ;; --route) ROUTE=1; shift ;;
               -h|--help) sed -n '2,12p' "$0"; exit 0 ;; *) echo "unknown $1" >&2; exit 2 ;; esac
done
URL="${BASE_URL:-${STAGING_HOST:+https://$STAGING_HOST}}"
[ -n "$URL" ] || { echo "set BASE_URL or STAGING_HOST" >&2; exit 2; }
mkdir -p "$NAV8_RESULTS"; out="$NAV8_RESULTS/downtime-$(date -u +%Y%m%dT%H%M%SZ).csv"
body='{"locations":[{"lat":47.9189,"lon":106.9176},{"lat":47.9139,"lon":106.9044}],"costing":"auto","format":"osrm"}'
state=""; since=0; down_total=0; downs=0; t_end=$(( $(date +%s) + DURATION ))
echo "utc,state,health_status,route_status,previous_state_seconds" > "$out"
summary() {
  now=$(date +%s); [ "$state" = down ] && down_total=$((down_total + now - since))
  echo "SUMMARY downtime_total_s=$down_total down_periods=$downs (resolution ${INTERVAL}s) file=$out" | tee -a "$out"
}
trap 'summary; exit 0' INT TERM
echo "probing $URL/health every ${INTERVAL}s (Ctrl-C to stop)"
while [ "$(date +%s)" -lt "$t_end" ]; do
  h="$(curl -s --noproxy '*' --max-time 5 -o /dev/null -w '%{http_code}' "$URL/health")"
  r="-"; [ "$ROUTE" = 1 ] && r="$(curl -s --noproxy '*' --max-time 10 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data "$body" "$URL/v1/route")"
  new=$([ "$h" = 200 ] && echo up || echo down); now=$(date +%s)
  if [ "$new" != "$state" ]; then
    prev=$([ -n "$state" ] && echo $((now - since)) || echo 0)
    [ "$state" = down ] && down_total=$((down_total + now - since))
    [ "$new" = down ] && downs=$((downs + 1))
    line="$(date -u +%FT%TZ),$new,$h,$r,$prev"; echo "$line" | tee -a "$out"
    state="$new"; since=$now
  fi
  sleep "$INTERVAL"
done
summary
