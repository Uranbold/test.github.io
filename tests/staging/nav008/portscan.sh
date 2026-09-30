#!/usr/bin/env bash
# NAV-008 AC 12: external port scan (IPv4 and IPv6) and SSH password-refusal check. Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md, cases PS-01 .. PS-06, SSH-01 .. SSH-03. Design: deployment-staging.md section 4.
#
#   tests/staging/nav008/portscan.sh [HOST] [--ssh-port 22] [--quick] [--parse-only FILE.gnmap]
#
# Run from a machine OUTSIDE the host's network (home or office line, not the VPS or the ops VM), only against the
# PO's own staging host. Addresses come from DNS for HOST, or from STAGING_IPV4 / STAGING_IPV6 in the untracked
# staging.local.env (use that before DNS exists). Addresses are NEVER printed: reports show <ipv4>/<ipv6>.
# Expected open TCP ports: exactly {SSH_PORT, 80, 443}. Explicitly closed: 8080 (gateway), 8002 (Valhalla),
# 2322 (Photon), 2019 (Caddy admin), 3001 (Uptime Kuma), 9100 (node_exporter).
#
# Needs: nmap (full scan; `sudo apt install nmap` / `brew install nmap`), ssh (OpenSSH client).
# Without nmap it falls back to a bash /dev/tcp probe of a port list and marks the result PARTIAL (not a pass).
# No password or key is ever sent: ssh runs with BatchMode=yes and public-key auth disabled, so the server can
# only answer with the list of methods it accepts. Expect "Permission denied (publickey)".
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
nav8_load_env

HOST=""; SSH_PORT="${SSH_PORT:-22}"; QUICK=0; PARSE_ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ssh-port) SSH_PORT="$2"; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --parse-only) PARSE_ONLY="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) HOST="$1"; shift ;;
  esac
done
HOST="${HOST:-${STAGING_HOST:-}}"
EXPECTED="$(printf '%s\n' "$SSH_PORT" 80 443 | sort -n | uniq | tr '\n' ' ' | sed 's/ $//')"
MUST_BE_CLOSED="8080 8002 2322 2019 3001 9100"
mkdir -p "$NAV8_RESULTS"

open_ports_from_gnmap() { # nmap -oG file -> "22 80 443"
  grep -oE '[0-9]+/open/tcp' "$1" | cut -d/ -f1 | sort -n | uniq | tr '\n' ' ' | sed 's/ $//'
}

judge() { # family open_ports_string partial(0/1)
  local fam="$1" open="$2" partial="$3" p
  if [ "$partial" = 1 ]; then
    info "PS-02.$fam.open_ports_partial" "${open:-none} (bash probe of a port list only: NOT a full scan, AC 12 needs nmap -p-)"
  else
    [ "$open" = "$EXPECTED" ]; check "PS-02.$fam.only_expected_ports_open (AC 12)" $? "open TCP = {$EXPECTED}" "open TCP = {${open:-none}}"
  fi
  for p in $MUST_BE_CLOSED; do
    case " $open " in *" $p "*) fail "PS-03.$fam.port_${p}_closed (AC 12)" "$p not reachable" "$p open" ;;
                      *) pass "PS-03.$fam.port_${p}_closed (AC 12)" ;; esac
  done
}

if [ -n "$PARSE_ONLY" ]; then  # self-test of the parser on a saved nmap -oG file
  judge fixture "$(open_ports_from_gnmap "$PARSE_ONLY")" 0
  nav8_summary; exit $?
fi
[ -n "$HOST" ] || { echo "no host: pass HOST or set STAGING_HOST (staging.local.env)" >&2; exit 2; }
echo "NAV-008 port scan: host=$HOST expected_open={$EXPECTED} ssh_port=$SSH_PORT"

# PS-01 addresses (DNS, or the local env for pre-DNS runs)
resolve() { # family(4|6)
  if need getent; then getent "ahostsv$1" "$HOST" 2>/dev/null | awk '{print $1}' | sort -u | head -1
  elif need dig; then dig +short "$([ "$1" = 4 ] && echo A || echo AAAA)" "$HOST" | grep -v '\.$' | head -1; fi
}
V4="${STAGING_IPV4:-$(resolve 4)}"; V6="${STAGING_IPV6:-$(resolve 6)}"
[ -n "$V4" ] && info "PS-01.ipv4" "target known ($([ -n "${STAGING_IPV4:-}" ] && echo local env || echo DNS A record))" \
             || fail "PS-01.ipv4" "an A record for $HOST (or STAGING_IPV4)" "none"
if [ -n "$V6" ]; then info "PS-01.ipv6" "target known ($([ -n "${STAGING_IPV6:-}" ] && echo local env || echo DNS AAAA record))"
else info "PS-01.ipv6" "no AAAA record and no STAGING_IPV6: record 'no IPv6' in the report (NAV-008 edge case IPv6/NAT64)"; fi
if [ -n "${STAGING_IPV4:-}" ] && [ -n "$(resolve 4)" ] && [ "$(resolve 4)" != "$STAGING_IPV4" ]; then
  fail "PS-01.dns_matches_local_env" "DNS A = STAGING_IPV4" "they differ (check which host you are scanning)"
fi

scan() { # family addr
  local fam="$1" addr="$2" out="$NAV8_RESULTS/nmap-$1-$(date -u +%Y%m%dT%H%M%SZ).gnmap" six=()
  [ "$fam" = ipv6 ] && six=(-6)
  if need nmap; then
    local ports=(-p-); [ "$QUICK" = 1 ] && ports=(--top-ports 2000 -p "22,80,443,$SSH_PORT,${MUST_BE_CLOSED// /,}")
    echo "scanning $fam (nmap -Pn -sT ${ports[*]}; a full scan takes 2-20 minutes) ..."
    nmap "${six[@]}" -Pn -sT -n -T4 --open "${ports[@]}" -oG "$out" "$addr" >/dev/null 2>&1
    if grep -q 'Status: Up\|/open/' "$out" || grep -q '^# Nmap done.*(1 host up)' "$out"; then
      judge "$fam" "$(open_ports_from_gnmap "$out")" "$QUICK"
      [ "$QUICK" = 1 ] && info "PS-02.$fam.quick" "--quick scanned the top 2000 ports only: rerun without --quick for AC 12"
    else
      fail "PS-02.$fam.scan_ran" "nmap reached the host" "$(tail -2 "$out" | redact_ips | tr '\n' ' ')"
    fi
    info "PS-04.$fam.raw" "$out (contains the address: stays in results/, which is git-ignored)"
  else
    local p open=""
    for p in 21 22 23 25 53 80 110 143 443 465 587 993 995 2019 2222 2322 3000 3001 3306 5432 6379 8000 8002 8080 8443 9000 9100 "$SSH_PORT"; do
      if timeout 3 bash -c "exec 3<>/dev/tcp/$addr/$p" 2>/dev/null; then open="$open $p"; fi
    done
    judge "$fam" "$(tr ' ' '\n' <<<"$open" | grep -v '^$' | sort -n | uniq | tr '\n' ' ' | sed 's/ $//')" 1
  fi
}
[ -n "$V4" ] && scan ipv4 "$V4"
if [ -n "$V6" ]; then
  if curl -6 -s --noproxy '*' --max-time 6 -o /dev/null https://www.google.com 2>/dev/null; then scan ipv6 "$V6"
  else skip "PS-02.ipv6" "this machine has no IPv6 route; rerun from a network with IPv6 (AC 12 needs both families when AAAA exists)"; fi
fi

# PS-05 UDP 443 (HTTP/3 off, design section 4). Needs root for -sU; informational only (open|filtered is ambiguous).
if need nmap && [ "$(id -u)" = 0 ] && [ -n "$V4" ]; then
  u="$(nmap -Pn -sU -n -p 443 "$V4" 2>/dev/null | grep '^443/udp' | awk '{print $2}')"
  info "PS-05.udp443" "${u:-unknown} (expected closed or filtered; 'open' means HTTP/3 or another UDP service is listening)"
else
  info "PS-05.udp443" "not scanned (needs nmap as root); TLS-07 checks that no HTTP/3 is advertised"
fi

# SSH-01 methods the server offers (nmap NSE), SSH-02 password refused for each probe user, SSH-03 host key info
target="${V4:-$V6}"
if [ -z "$target" ]; then skip "SSH-01..03" "no address"
else
  if need nmap; then
    m="$(nmap -Pn -n -p "$SSH_PORT" --script ssh-auth-methods --script-args ssh.user=nav-ops "$target" 2>/dev/null \
         | sed -n '/Supported authentication methods/,/^[^|]/p' | grep -E '^\|' | sed 's/^|[ _]*//' | grep -v 'Supported' | tr '\n' ' ')"
    if [ -n "$m" ]; then
      [ "$(echo $m)" = "publickey" ]; check "SSH-01.only_publickey_offered (AC 12)" $? "publickey" "$m"
    else skip "SSH-01.only_publickey_offered" "ssh-auth-methods returned nothing (port closed or script unavailable)"; fi
  else skip "SSH-01.only_publickey_offered" "needs nmap"; fi
  if need ssh; then
    for u in ${SSH_PROBE_USERS:-nav-ops root}; do
      out="$(ssh -p "$SSH_PORT" -o BatchMode=yes -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
                 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR \
                 -o NumberOfPasswordPrompts=0 "$u@$target" true 2>&1 </dev/null)"; rc=$?
      methods="$(grep -oE 'Permission denied \([^)]*\)' <<<"$out")"
      [ $rc -ne 0 ] && [ "$methods" = "Permission denied (publickey)" ]
      check "SSH-02.password_refused.$u (AC 12)" $? "Permission denied (publickey)" "exit $rc: $(redact_ips <<<"$out" | head -2 | tr '\n' ' ')"
    done
  else skip "SSH-02.password_refused" "needs the OpenSSH client"; fi
  if need ssh-keyscan; then
    info "SSH-03.host_key_types" "$(ssh-keyscan -p "$SSH_PORT" -T 8 "$target" 2>/dev/null | awk '{print $2}' | sort -u | tr '\n' ' ')"
  fi
fi
manual "SSH-04.sshd_T (AC 12)" "operator runs 'sudo sshd -T | grep -Ei \"^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin|pubkeyauthentication|allowusers)\"' on the host; expect no / no / no / yes / nav-ops"
manual "SSH-05.unattended_upgrades (AC 12)" "operator runs 'systemctl is-enabled unattended-upgrades; apt-config dump | grep -E \"Unattended-Upgrade::(Allowed-Origins|Origins-Pattern|Automatic-Reboot)\"' and pastes the output"
nav8_summary
