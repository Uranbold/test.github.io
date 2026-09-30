# shellcheck shell=bash
# NAV-008 staging: shared helpers for infra/staging/bin/*. Sourced, not executed. Owner: backend-engineer.
#
# Paths (override in the environment for tests):
#   NAV_ROOT       git checkout at a pinned tag                    (default: two levels above this file)
#   NAV_ENV_FILE   the ONE staging config file, never in git       (default: $NAV_ROOT/infra/staging/.env)
#   NAV_STATE_DIR  heartbeat/rebuild state, rollback copy          (default: /var/lib/nav)
# Logs: one JSON object per line on stdout/journald. Only URLs of public sources, sizes, durations and exit
# codes; never client data, never secrets (push URLs and passwords are not printed).

set -Eeuo pipefail

NAV_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAV_ROOT="${NAV_ROOT:-$(cd "$NAV_BIN/../../.." && pwd)}"
NAV_STAGING="$NAV_ROOT/infra/staging"
NAV_ENV_FILE="${NAV_ENV_FILE:-$NAV_STAGING/.env}"
NAV_STATE_DIR="${NAV_STATE_DIR:-/var/lib/nav}"
NAV_JOB="${NAV_JOB:-$(basename "${0%.sh}")}"

_nav_json_escape() {
    local s=${1//\\/\\\\}
    s=${s//\"/\\\"}
    s=${s//$'\n'/ }
    s=${s//$'\t'/ }
    printf '%s' "$s"
}

# nav_log <level> <msg> [key=value ...]
nav_log() {
    local level=$1 msg=$2 kv out
    shift 2
    out="{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"job\":\"$NAV_JOB\",\"level\":\"$level\",\"msg\":\"$(_nav_json_escape "$msg")\""
    for kv in "$@"; do
        out+=",\"$(_nav_json_escape "${kv%%=*}")\":\"$(_nav_json_escape "${kv#*=}")\""
    done
    printf '%s}\n' "$out"
}

nav_die() { nav_log error "$@"; exit 1; }

# nav_env_get KEY [default]: value of KEY from $NAV_ENV_FILE (last assignment wins), else the default.
# The file is parsed, never sourced: values are taken literally (optional surrounding quotes removed).
nav_env_get() {
    local key=$1 def=${2-} line val=""
    local found=0
    if [[ -r "$NAV_ENV_FILE" ]]; then
        while IFS= read -r line || [[ -n "$line" ]]; do
            line=${line%$'\r'}
            [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?${key}=(.*)$ ]] || continue
            val=${BASH_REMATCH[2]}
            if [[ "$val" =~ ^\"(.*)\"$ || "$val" =~ ^\'(.*)\'$ ]]; then val=${BASH_REMATCH[1]}; fi
            found=1
        done < "$NAV_ENV_FILE"
    fi
    if [[ $found -eq 1 && -n "$val" ]]; then printf '%s' "$val"; else printf '%s' "$def"; fi
}

nav_require_env_file() {
    [[ -r "$NAV_ENV_FILE" ]] || nav_die "staging config missing: copy infra/staging/.env.example to infra/staging/.env and fill it in" file="$NAV_ENV_FILE"
}

# Refuse configurations that would break AC 11 / AC 13 / AC 14 on a public host.
nav_validate_env() {
    nav_require_env_file
    local host cors email
    host=$(nav_env_get STAGING_HOST)
    cors=$(nav_env_get CORS_ALLOWED_ORIGINS)
    email=$(nav_env_get ACME_EMAIL)
    [[ -n "$host" ]] || nav_die "STAGING_HOST is empty in infra/staging/.env"
    [[ "$host" =~ ^[A-Za-z0-9.-]+$ ]] || nav_die "STAGING_HOST must be a bare host name (no scheme, port or path)"
    [[ -n "$email" ]] || nav_die "ACME_EMAIL is empty in infra/staging/.env (use a role mailbox)"
    [[ -n "$cors" ]] || nav_die "CORS_ALLOWED_ORIGINS is empty in infra/staging/.env"
    [[ "$cors" != *"*"* ]] || nav_die "CORS_ALLOWED_ORIGINS must not contain '*' on staging (NAV-008 AC 11)"
    local lvl
    lvl=$(nav_env_get GATEWAY_ERROR_LOG_LEVEL crit)
    [[ "$lvl" == crit || "$lvl" == alert || "$lvl" == emerg ]] \
        || nav_die "GATEWAY_ERROR_LOG_LEVEL must stay crit on staging (error lines contain client IPs and query strings)"
}

# IPv6 on the edge network: EDGE_IPV6=auto (default) uses it when the kernel has IPv6 enabled.
nav_ipv6_enabled() {
    local mode
    mode=$(nav_env_get EDGE_IPV6 auto)
    case "$mode" in
        on|true|yes) return 0 ;;
        off|false|no) return 1 ;;
        *) [[ -r /proc/sys/net/ipv6/conf/all/disable_ipv6 && "$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6)" == 0 ]] ;;
    esac
}

# Compose project name (volume and container prefix). Environment wins over infra/staging/.env; default navmn.
nav_project() { printf '%s' "${NAV_COMPOSE_PROJECT:-$(nav_env_get NAV_COMPOSE_PROJECT navmn)}"; }

# nav_compose <compose args...>: the staging compose invocation (base file + overlay(s) + staging .env).
nav_compose() {
    local files=(-f "$NAV_ROOT/backend/compose.yaml" -f "$NAV_STAGING/compose.staging.yaml")
    if nav_ipv6_enabled; then files+=(-f "$NAV_STAGING/compose.staging.ipv6.yaml"); fi
    docker compose -p "$(nav_project)" --project-directory "$NAV_ROOT/backend" "${files[@]}" --env-file "$NAV_ENV_FILE" "$@"
}

# nav_heartbeat <ENV_KEY> <message>: push to an Uptime Kuma push monitor. The URL (a secret token) is never logged.
nav_heartbeat() {
    local key=$1 msg=$2 url
    url=$(nav_env_get "$key")
    if [[ -z "$url" ]]; then
        nav_log warn "no push URL configured; heartbeat skipped" key="$key"
        return 0
    fi
    # Uptime Kuma shows push URLs as .../api/push/<token>?status=up&msg=OK&ping= ; the query is rebuilt here.
    url=${url%%\?*}
    if curl -fsS -m 20 --retry 3 -o /dev/null -G "$url" --data-urlencode "status=up" --data-urlencode "msg=$msg" --data-urlencode "ping="; then
        nav_log info "heartbeat sent" key="$key"
    else
        nav_log error "heartbeat failed" key="$key"
        return 1
    fi
}

nav_free_gb() { df -P -BG "${1:-/}" | awk 'NR==2 {gsub("G","",$4); print $4}'; }
nav_used_pct() { df -P "${1:-/}" | awk 'NR==2 {gsub("%","",$5); print $5}'; }

nav_require_root() { [[ $EUID -eq 0 ]] || nav_die "run as root (sudo)"; }
