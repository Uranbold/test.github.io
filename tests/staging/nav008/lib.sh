# shellcheck shell=bash
# Shared helpers for the NAV-008 staging QA scripts (owner: qa-engineer). Sourced, not executed.
# Test plan: docs/qa/test-plans/NAV-008.md. Output lines: PASS / FAIL / INFO / SKIP / MANUAL.
NAV8_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAV8_REPO="$(cd "$NAV8_DIR/../../.." && pwd)"
NAV8_RESULTS="${NAV8_RESULTS:-$NAV8_DIR/results}"
N_PASS=0; N_FAIL=0; N_SKIP=0; N_INFO=0; N_MANUAL=0

nav8_load_env() {  # environment wins over the local file
  local f="${NAV8_ENV:-$NAV8_DIR/staging.local.env}" line k v
  [ -f "$f" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    k="${line%%=*}"; v="${line#*=}"; v="${v%\"}"; v="${v#\"}"
    [ -n "${!k:-}" ] || export "$k=$v"
  done < "$f"
}

pass()   { N_PASS=$((N_PASS+1));   printf 'PASS    %s%s\n' "$1" "${2:+  [$2]}"; }
fail()   { N_FAIL=$((N_FAIL+1));   printf 'FAIL    %s\n          expected: %s\n          actual:   %s\n' "$1" "$2" "$3"; }
info()   { N_INFO=$((N_INFO+1));   printf 'INFO    %s: %s\n' "$1" "$2"; }
skip()   { N_SKIP=$((N_SKIP+1));   printf 'SKIP    %s: %s\n' "$1" "$2"; }
manual() { N_MANUAL=$((N_MANUAL+1)); printf 'MANUAL  %s: %s\n' "$1" "$2"; }
check()  { # check ID CONDITION_EXIT EXPECTED ACTUAL
  if [ "$2" = 0 ]; then pass "$1" "$4"; else fail "$1" "$3" "$4"; fi
}
nav8_summary() {
  echo
  echo "$N_PASS passed, $N_FAIL failed, $N_SKIP skipped, $N_INFO info, $N_MANUAL manual"
  [ "$N_FAIL" -eq 0 ]
}

# Replace IPv4/IPv6 literals so no address reaches a report or the terminal log that gets pasted into docs.
redact_ips() {
  sed -E -e 's/([0-9]{1,3}\.){3}[0-9]{1,3}/<ipv4>/g' \
         -e 's/([0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}/<ipv6>/g' \
         -e 's/([0-9A-Fa-f]{1,4}:)+:([0-9A-Fa-f]{1,4}(:[0-9A-Fa-f]{1,4})*)?/<ipv6>/g'
}

need() { command -v "$1" >/dev/null 2>&1; }
