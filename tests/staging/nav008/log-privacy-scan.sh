#!/usr/bin/env bash
# NAV-008 AC 14: log-privacy scan, run ON THE STAGING HOST by the operator (nav-ops) after one day of normal use.
# Owner: qa-engineer. Test plan: docs/qa/test-plans/NAV-008.md, cases LP-01 .. LP-08. Design: deployment-staging.md section 8.
#
#   sudo bash log-privacy-scan.sh [--since 24h] [--markers log-markers-*.txt] [--self-test FILE]
#
# Reads every running container's log (gateway, valhalla, photon, caddy, ...), the nav-* systemd units' journal,
# the ufw log and the sshd journal. Searches for client IPv4/IPv6 addresses, query strings, request bodies,
# coordinate pairs in Mongolia's range and the QA marker values (staging_checks.py --group log-markers).
# PRINTS ONLY COUNTS AND MASKED SAMPLES (every digit replaced by '#'), so the output can be pasted into the QA report
# without copying any personal data. Exit 0 = no finding on the request path; 1 = at least one finding.
# sshd lines with source IPs are expected security logs (design section 8): counted as INFO for the counsel review.
set -uo pipefail
SINCE="24h"; MARKERS=""; SELF=""
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift 2 ;;
    --markers) MARKERS="$2"; shift 2 ;;
    --self-test) SELF="$2"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
FINDINGS=0

# An address in a log line follows a space, quote, '=', ':', bracket, brace, comma or '<'. Version strings such as
# "VM/21.0.12.1" or "jetty-9.4.51.1" follow '/' or '-' and are not matched.
IPV4="(^|[][ \"'=:(,{<>])((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])([^0-9.]|\$)"
# IPv6: a token of hex digits and at least 3 colons (timestamps have 2). "400::Failed" is not matched.
IPV6='(^|[^0-9A-Za-z:.])[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){3,7}([^0-9A-Za-z:]|$)'
# Known non-client version strings that look like IPv4 (Photon/OpenSearch JVM banner). Whole line ignored for LP-01 only.
IGNORE_V4='(jvm|JVM\[|OpenJDK 64-Bit Server VM/)[ ]?[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+'
QUERY='(\?|&)(q|lat|lon|json|lang|limit|osm_tag|layer|bbox|location_bias_scale)=|"(q|query|lat|lon|locations)" *:'
BODY='"locations" *: *\[|"costing" *:|"costing_options"'
LAT='(^|[^0-9])(4[1-9]|5[0-2])\.[0-9]{4,}'
LON='(^|[^0-9])(8[7-9]|9[0-9]|1[01][0-9]|120)\.[0-9]{4,}'
HEADERS='(^|[^A-Za-z_])(User-Agent|Referer|X-Forwarded-For|X-Real-IP|remote_ip|remote_addr|client_ip|http_user_agent)([^A-Za-z_]|$)'

mask() { sed -E 's/[0-9]/#/g' | cut -c1-160; }
# Private, loopback and unspecified IPv4 literals (Docker networks, upstream addresses) are not client IPs.
drop_private4() {
  sed -E -e 's/(^|[^0-9.])(10|127)(\.[0-9]{1,3}){3}/\1<private>/g' -e 's/(^|[^0-9.])192\.168(\.[0-9]{1,3}){2}/\1<private>/g' \
         -e 's/(^|[^0-9.])172\.(1[6-9]|2[0-9]|3[01])(\.[0-9]{1,3}){2}/\1<private>/g' -e 's/(^|[^0-9.])(0\.0\.0\.0|169\.254(\.[0-9]{1,3}){2})/\1<private>/g'
}

collect() {  # one file per source under $tmp
  if [ -n "$SELF" ]; then cp "$SELF" "$tmp/selftest.log"; return; fi
  local c
  if command -v docker >/dev/null; then
    for c in $(docker ps --format '{{.Names}}'); do docker logs --since "$SINCE" "$c" > "$tmp/container-$c.log" 2>&1; done
  fi
  if command -v journalctl >/dev/null; then
    journalctl --since "-$SINCE" -u 'nav-*' --no-pager -o cat > "$tmp/journal-nav-units.log" 2>/dev/null
    journalctl --since "-$SINCE" -u ssh -u sshd --no-pager -o cat > "$tmp/sshd.security" 2>/dev/null
    journalctl --since "-$SINCE" -k --no-pager -o cat 2>/dev/null | grep -i 'UFW' > "$tmp/kernel-ufw.log"
  fi
  [ -f /var/log/ufw.log ] && tail -n 100000 /var/log/ufw.log > "$tmp/var-log-ufw.log"
  # Caddy must have no access log file anywhere (design section 5): list any *.log in its volumes.
  if command -v docker >/dev/null; then
    for c in $(docker ps --format '{{.Names}}' | grep -i caddy); do
      docker exec "$c" sh -c 'find / -xdev -name "*.log" -newermt "-2 days" 2>/dev/null | grep -v ^/proc' > "$tmp/caddy-logfiles.list" 2>/dev/null
    done
  fi
}

scan_file() { # file label
  local f="$1" name="$2" n pat id
  local -a checks=("LP-01.public_ipv4" "LP-02.ipv6" "LP-03.query_string" "LP-04.request_body" "LP-05.coordinate_pair" "LP-06.client_headers")
  for id in "${checks[@]}"; do
    case "$id" in
      LP-01*) drop_private4 < "$f" | grep -vE "$IGNORE_V4" | grep -E "$IPV4" > "$tmp/hits" ;;
      LP-02*) grep -E "$IPV6" "$f" > "$tmp/hits" ;;
      LP-03*) grep -E "$QUERY" "$f" | grep -v '<redacted>' > "$tmp/hits" ;;
      LP-04*) grep -E "$BODY" "$f" > "$tmp/hits" ;;
      LP-05*) grep -E "$LAT" "$f" | grep -E "$LON" > "$tmp/hits" ;;
      LP-06*) grep -iE "$HEADERS" "$f" > "$tmp/hits" ;;
    esac
    n=$(wc -l < "$tmp/hits" | tr -d ' ')
    if [ "$n" -gt 0 ]; then
      FINDINGS=$((FINDINGS+1)); echo "FAIL    $id [$name]: $n line(s). Masked samples:"; head -3 "$tmp/hits" | mask | sed 's/^/          /'
    fi
  done
  if [ -n "$MARKERS" ]; then
    n=$(grep -v '^#' "$MARKERS" | grep -v '^$' | grep -F -f - "$f" | wc -l | tr -d ' ')
    [ "$n" -gt 0 ] && { FINDINGS=$((FINDINGS+1)); echo "FAIL    LP-07.qa_markers [$name]: $n line(s) contain a QA marker value"; }
  fi
  echo "INFO    scanned [$name]: $(wc -l < "$f" | tr -d ' ') lines"
}

collect
echo "NAV-008 AC 14 log-privacy scan: since=$SINCE markers=${MARKERS:-none} host=$(hostname -s 2>/dev/null | mask)"
shopt -s nullglob
for f in "$tmp"/*.log; do scan_file "$f" "$(basename "$f" .log)"; done
if [ -f "$tmp/sshd.security" ]; then
  echo "INFO    LP-08.sshd_security_log: $(grep -cE "$IPV4|$IPV6" "$tmp/sshd.security") line(s) with source IPs in the sshd journal (expected, <= 14 days, listed for the counsel review; not a finding)"
fi
if [ -s "$tmp/caddy-logfiles.list" ]; then
  FINDINGS=$((FINDINGS+1)); echo "FAIL    LP-09.caddy_log_files: Caddy has *.log files: $(tr '\n' ' ' < "$tmp/caddy-logfiles.list")"
fi
echo "INFO    retention: docker log driver = $(docker info --format '{{.LoggingDriver}}' 2>/dev/null || echo '?') (expected local); journald: $(grep -hE '^(SystemMaxUse|MaxRetentionSec)' /etc/systemd/journald.conf /etc/systemd/journald.conf.d/*.conf 2>/dev/null | tr '\n' ' ')"
echo
if [ "$FINDINGS" -eq 0 ]; then echo "RESULT  no client IP, query string, body, coordinate or marker found (AC 14 pass for this window)"; exit 0; fi
echo "RESULT  $FINDINGS finding(s). Send this output to QA; do NOT send raw log lines."
exit 1
