#!/usr/bin/env bash
# NAV-008 staging: deploy a pinned git tag (deployment-staging.md §12, B8). Owner: backend-engineer.
#
#   sudo infra/staging/bin/deploy.sh <git-tag>        # deploy that tag
#   sudo infra/staging/bin/deploy.sh --rollback       # deploy the previously deployed tag again
#   sudo infra/staging/bin/deploy.sh --status         # show the deployed and previous tags
#
# Steps: validate infra/staging/.env -> git fetch of the tag (as the checkout owner) -> refuse a moved tag -> verify
# the tag's SSH signature against DEPLOY_ALLOWED_SIGNERS (SEC-4A BE-9) -> checkout of the verified commit -> re-exec
# the tag's own deploy.sh -> install/refresh systemd units -> docker compose pull (images pinned by digest)
# -> up -d --wait (gateway, Caddy) -> the active NAV-006 lane (blue|green) up -d --wait -> check that the gateway
# rate limits are active -> smoke suite. On a failed smoke the command to roll back is printed; nothing is
# rolled back automatically.
# NAV-006 (ADR-0014): compose model = backend/compose.slots.yaml + overlays; data in NAV_DATA_ROOT. When no slot
# is active yet (first deploy on an empty host), the pipeline's first build runs under this deploy's lock
# (about 8-10 min on 4 vCPU plus downloads). A deploy that changes a lane's image recreates the active lane:
# a few seconds of route/search 502 (accepted like any deploy; data switches stay at 0 s).
#
# SEC-4A BE-9 (RUNBOOK.md section 6.1): only annotated tags signed with an SSH key listed in the allowed-signers file
# DEPLOY_ALLOWED_SIGNERS (root-owned, outside the checkout) are deployed; `git verify-tag` runs BEFORE the checkout
# and the re-exec, with the verification settings forced on the command line (the checkout's own git config cannot
# weaken them). A tag that already exists locally and points elsewhere at origin (a moved tag) is refused; tags are
# never fetched with --force. A tag object whose own name ("tag <name>" header) differs from the requested tag is refused
# (SEC BE-22: an old signed release cannot be deployed under a new name). No DEPLOY_ALLOWED_SIGNERS = refused, unless the one-off escape hatch for the first
# bootstrap is given on the command line: sudo DEPLOY_ALLOW_UNSIGNED=1 deploy.sh <tag> (logged as a warning and in
# deployments.log). The very first deploy of a SEC-4A tag is still done by the previous deploy.sh.
# shellcheck source=nav-env.sh
source "$(dirname "$(readlink -f "$0")")/nav-env.sh"

STATE_TAG="$NAV_STATE_DIR/deployed-tag"
PREV_TAG="$NAV_STATE_DIR/deployed-tag.previous"
HISTORY="$NAV_STATE_DIR/deployments.log"

usage() { sed -n '2,21p' "$0"; exit 2; }
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
    # The unsigned-deploy escape hatch is a one-off on the command line, never a setting.
    [[ -z "$(nav_env_get DEPLOY_ALLOW_UNSIGNED)" ]] \
        || nav_die "DEPLOY_ALLOW_UNSIGNED must not be set in infra/staging/.env (one-off only: sudo DEPLOY_ALLOW_UNSIGNED=1 $0 <tag>, RUNBOOK.md section 6.1)"
    signers=$(nav_env_get DEPLOY_ALLOWED_SIGNERS)
    if [[ -n "$signers" ]]; then
        why=$(nav_signers_problem "$signers")
        [[ -z "$why" ]] || nav_die "DEPLOY_ALLOWED_SIGNERS unusable: $why (RUNBOOK.md section 6.1)" file="$signers"
    fi
    if [[ -z "$signers" && "${DEPLOY_ALLOW_UNSIGNED:-0}" != 1 ]]; then
        nav_die "refusing to deploy: DEPLOY_ALLOWED_SIGNERS is not set in infra/staging/.env, so the tag's signature cannot be verified. Set it to the root-owned allowed-signers file (RUNBOOK.md section 6.1). First bootstrap only: sudo DEPLOY_ALLOW_UNSIGNED=1 $0 $TAG" tag="$TAG"
    fi
    # Runs as the checkout owner (nav-ops after bootstrap): a private repository's read-only deploy key and its
    # ssh config must belong to that account (RUNBOOK.md section 4), not to root.
    # Only this tag, into a scratch ref (never --force on refs/tags/*): a tag that moved at origin is refused below.
    incoming="refs/nav-incoming/tags/$TAG"
    as_owner git -C "$NAV_ROOT" fetch --no-tags --quiet origin "+refs/tags/$TAG:$incoming" \
        || nav_die "git fetch of tag $TAG from origin failed as $owner (tag missing at origin? private repository: ~$owner/.ssh/nav_deploy + 'Host github.com' in ~$owner/.ssh/config, RUNBOOK.md section 4)" tag="$TAG"
    new=$(as_owner git -C "$NAV_ROOT" rev-parse -q --verify "$incoming") || nav_die "tag not found in origin" tag="$TAG"
    as_owner git -C "$NAV_ROOT" update-ref -d "$incoming"
    old=$(as_owner git -C "$NAV_ROOT" rev-parse -q --verify "refs/tags/$TAG" || true)
    if [[ -n "$old" && "$old" != "$new" ]]; then
        nav_die "refusing to deploy: tag $TAG was moved at origin (local $old, origin $new). A released tag must never change; create a new tag (RUNBOOK.md section 6.1)" tag="$TAG"
    fi
    [[ -n "$old" ]] || as_owner git -C "$NAV_ROOT" update-ref "refs/tags/$TAG" "$new" "" \
        || nav_die "could not record tag $TAG locally" tag="$TAG"
    commit=$(as_owner git -C "$NAV_ROOT" rev-parse -q --verify "$new^{commit}") || nav_die "tag does not point to a commit" tag="$TAG"
    # SEC BE-22: an annotated tag object carries its own name ("tag <name>" header). A signature covers that object,
    # so whoever can push tags could re-publish an OLD signed release under a NEW name (refs/tags/v9 -> the v1 object)
    # and the signature would still verify. Refuse any tag object whose own name differs from the requested one.
    if [[ "$(as_owner git -C "$NAV_ROOT" cat-file -t "$new")" == tag ]]; then
        object_name=$(as_owner git -C "$NAV_ROOT" cat-file tag "$new" | awk '/^$/ {exit} /^tag / {print substr($0, 5); exit}')
        [[ "$object_name" == "$TAG" ]] \
            || nav_die "refusing to deploy: the tag object fetched as $TAG is named '$object_name' (an existing release re-pushed under a new name, SEC BE-22). A release is never renamed: create a new signed tag (RUNBOOK.md section 6.1)" tag="$TAG" object_name="$object_name"
    fi
    signer=unsigned
    if [[ -n "$signers" ]]; then
        [[ "${DEPLOY_ALLOW_UNSIGNED:-0}" != 1 ]] || nav_log warn "DEPLOY_ALLOW_UNSIGNED=1 ignored: an allowed-signers file is configured, the signature is verified"
        [[ "$(as_owner git -C "$NAV_ROOT" cat-file -t "$new")" == tag ]] \
            || nav_die "refusing to deploy: $TAG is a lightweight tag (no signature). Create a signed annotated tag: git tag -s (RUNBOOK.md section 6.1)" tag="$TAG"
        vout=$(as_owner git -C "$NAV_ROOT" -c gpg.format=ssh -c gpg.ssh.program=ssh-keygen \
                 -c gpg.ssh.allowedSignersFile="$signers" verify-tag "$new" 2>&1) \
            || nav_die "refusing to deploy: the signature of tag $TAG does not verify against DEPLOY_ALLOWED_SIGNERS (unsigned, unknown key or tampered tag)" \
                   tag="$TAG" detail="$(printf '%s' "$vout" | tr '\n' ' ' | tail -c 300)"
        signer=$(sed -n 's/^Good "git" signature for \(.*\) with \([A-Z0-9-]*\) key \(SHA256:[A-Za-z0-9+\/=]*\).*/\1 \2 \3/p' <<<"$vout" | head -n1)
        [[ -n "$signer" ]] || nav_die "refusing to deploy: unexpected git verify-tag output" tag="$TAG" detail="$(printf '%s' "$vout" | tr '\n' ' ' | tail -c 300)"
        nav_log info "tag signature verified" tag="$TAG" signer="$signer" commit="$commit"
    else
        nav_log warn "UNSIGNED DEPLOY: DEPLOY_ALLOW_UNSIGNED=1 and no DEPLOY_ALLOWED_SIGNERS; the tag's code runs as root without a signature check (first bootstrap only, RUNBOOK.md section 6.1)" tag="$TAG" commit="$commit"
    fi
    as_owner git -C "$NAV_ROOT" -c advice.detachedHead=false checkout --quiet --force "$commit"
    printf '%s tag=%s object=%s commit=%s signer=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TAG" "$new" "$commit" "${signer// /_}" >> "$HISTORY"
    if [[ "$current" != "$TAG" ]]; then printf '%s\n' "$current" > "$PREV_TAG.pending"; fi
    # Continue with the deploy logic of the tag that is now checked out (the lock fd 9 is inherited).
    exec "$NAV_ROOT/infra/staging/bin/deploy.sh" --after-checkout "$TAG"
fi

t0=$(date +%s)
"$NAV_BIN/install-units.sh"
nav_ensure_data_root
nav_give_photon_indexes   # SEC-4A: slots built before SEC-4A have a root-owned photon/; the lane runs as uid 101
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
