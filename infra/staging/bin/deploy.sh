#!/usr/bin/env bash
# NAV-008 staging: deploy a pinned git tag (deployment-staging.md §12, B8). Owner: backend-engineer.
#
#   sudo infra/staging/bin/deploy.sh <git-tag>        # deploy that tag
#   sudo infra/staging/bin/deploy.sh --rollback       # deploy the previously deployed tag again
#   sudo infra/staging/bin/deploy.sh --status         # show the deployed and previous tags
#
# Steps: validate infra/staging/.env -> git fetch + checkout of the tag (as the checkout owner) -> re-exec the
# tag's own deploy.sh -> install/refresh systemd units -> docker compose pull (images pinned by digest/tag)
# -> up -d --wait (gateway, Caddy) -> the active NAV-006 lane (blue|green) up -d --wait -> check that the gateway
# rate limits are active -> smoke suite. On a failed smoke the command to roll back is printed; nothing is
# rolled back automatically.
# NAV-006 (ADR-0014): compose model = backend/compose.slots.yaml + overlays; data in NAV_DATA_ROOT. When no slot
# is active yet (first deploy on an empty host), the pipeline's first build runs under this deploy's lock
# (about 8-10 min on 4 vCPU plus downloads). A deploy that changes a lane's image recreates the active lane:
# a few seconds of route/search 502 (accepted like any deploy; data switches stay at 0 s).
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

STATE_TAG="$NAV_STATE_DIR/deployed-tag"
PREV_TAG="$NAV_STATE_DIR/deployed-tag.previous"
HISTORY="$NAV_STATE_DIR/deployments.log"

usage() { sed -n '2,13p' "$0"; exit 2; }
[[ $# -ge 1 ]] || usage
nav_require_root
mkdir -p "$NAV_STATE_DIR"

case "$1" in
    --status)
        echo "deployed: $(cat "$STATE_TAG" 2>/dev/null || echo none)"
        echo "previous: $(cat "$PREV_TAG" 2>/dev/null || echo none)"
        tail -n 10 "$HISTORY" 2>/dev/null || true
        exit 0 ;;
    --rollback)
        [[ -s "$PREV_TAG" ]] || nav_die "no previous tag recorded" file="$PREV_TAG"
        exec "$0" "$(cat "$PREV_TAG")" ;;
    --after-checkout)
        TAG=${2:?}; AFTER=1 ;;
    -*) usage ;;
    *) TAG=$1; AFTER=0 ;;
esac
[[ "$TAG" =~ ^[A-Za-z0-9._/-]+$ ]] || nav_die "invalid tag name" tag="$TAG"

nav_validate_env
exec 9>"${NAV_LOCK_FILE:-/run/lock/nav-stack.lock}"
flock -n 9 || nav_die "another rebuild, deploy or rollback holds the lock; try again later"

owner=$(stat -c '%U' "$NAV_ROOT")
as_owner() { if [[ "$owner" == root ]]; then "$@"; else runuser -u "$owner" -- "$@"; fi; }

if [[ $AFTER -eq 0 ]]; then
    current=$(as_owner git -C "$NAV_ROOT" describe --tags --exact-match 2>/dev/null || as_owner git -C "$NAV_ROOT" rev-parse --short HEAD)
    if [[ -n "$(as_owner git -C "$NAV_ROOT" status --porcelain --untracked-files=no)" ]]; then
        nav_die "the checkout has local changes to tracked files; refusing to deploy over them" dir="$NAV_ROOT"
    fi
    nav_log info "deploy starting" tag="$TAG" current="$current"
    # Runs as the checkout owner (nav-ops after bootstrap): a private repository's read-only deploy key and its
    # ssh config must belong to that account (RUNBOOK.md section 4), not to root.
    as_owner git -C "$NAV_ROOT" fetch --tags --force --quiet origin \
        || nav_die "git fetch from origin failed as $owner; private repository: ~$owner/.ssh/nav_deploy + 'Host github.com' in ~$owner/.ssh/config (RUNBOOK.md section 4)"
    as_owner git -C "$NAV_ROOT" rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || nav_die "tag not found in origin" tag="$TAG"
    as_owner git -C "$NAV_ROOT" -c advice.detachedHead=false checkout --quiet --force "refs/tags/$TAG"
    if [[ "$current" != "$TAG" ]]; then printf '%s\n' "$current" > "$PREV_TAG.pending"; fi
    # Continue with the deploy logic of the tag that is now checked out (the lock fd 9 is inherited).
    exec "$NAV_ROOT/infra/staging/bin/deploy.sh" --after-checkout "$TAG"
fi

t0=$(date +%s)
"$NAV_BIN/install-units.sh"
nav_ensure_data_root
nav_compose --profile '*' pull --quiet --ignore-buildable
nav_compose up -d --wait --remove-orphans
lane=$(nav_active_lane)
if [[ -n "$lane" ]]; then
    nav_compose up -d --wait --no-deps "valhalla-$lane" "photon-$lane"
else
    nav_log info "no active data slot yet; running the first NAV-006 build under this deploy's lock"
    NAV_COMPOSE_OVERLAYS=$(nav_overlays) python3 "$NAV_ROOT/backend/pipeline/nav_pipeline.py" \
        --env-file "$NAV_ENV_FILE" rebuild --assume-locked || nav_die "first data build failed (see the run log above and RUNBOOK.md NAV-006 section)"
fi
nav_compose ps --format 'table {{.Service}}\t{{.Status}}'

# Staging must never serve without rate limits (NAV-008 AC 13); the entrypoint writes this file at start.
nav_compose exec -T gateway grep -q 'limit_req zone=nav_route' /etc/nginx/conf.d/01-rate-limits.conf \
    || nav_die "gateway started without rate limits (01-rate-limits.conf missing or empty)"

rc=0
host=$(nav_env_get STAGING_HOST)
smoke_url=$(nav_env_get SMOKE_BASE_URL "https://$host")
if [[ -x "$NAV_ROOT/tests/smoke/run.sh" ]]; then
    BASE_URL="$smoke_url" "$NAV_ROOT/tests/smoke/run.sh" || rc=$?
else
    python3 "$NAV_ROOT/backend/scripts/smoke.py" --base-url "$smoke_url" || rc=$?
fi

if [[ -s "$PREV_TAG.pending" ]]; then mv -f "$PREV_TAG.pending" "$PREV_TAG"; fi
printf '%s\n' "$TAG" > "$STATE_TAG"
printf '%s tag=%s smoke_exit=%s seconds=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TAG" "$rc" "$(( $(date +%s) - t0 ))" >> "$HISTORY"
if [[ $rc -ne 0 ]]; then
    nav_log error "deployed, but the smoke suite failed" tag="$TAG" \
        rollback="sudo $NAV_ROOT/infra/staging/bin/deploy.sh --rollback  (previous: $(cat "$PREV_TAG" 2>/dev/null || echo none))"
    exit 1
fi
nav_log info "deploy finished, smoke passed" tag="$TAG" seconds="$(( $(date +%s) - t0 ))"
