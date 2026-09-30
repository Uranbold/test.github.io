#!/usr/bin/env bash
# NAV-008 repository checks: AC 21 (no secrets, no host IP, config keys documented, infra deliverables present),
# AC 23 (staging URL in openapi.yaml servers and backend/README.md) and AC 24 (dated counsel record). Owner: qa-engineer.
# Test plan: docs/qa/test-plans/NAV-008.md, cases RS-01 .. RS-09. Runs on any clone; no network, no server access.
#
#   tests/staging/nav008/repo-checks.sh [--stage pre|accept]
#
#   --stage pre     (default) before staging acceptance: AC 23/24 gaps are INFO (expected while the domain and the
#                   counsel review are pending)
#   --stage accept  at acceptance: AC 23 and AC 24 gaps FAIL
#
# The host IP is read ONLY from STAGING_IPV4 / STAGING_IPV6 in the untracked staging.local.env (or the environment),
# and the scan reports "found / not found" without printing it. Optional: gitleaks on PATH adds a full-history scan.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
nav8_load_env
STAGE=pre
while [ $# -gt 0 ]; do case "$1" in --stage) STAGE="$2"; shift 2 ;; -h|--help) sed -n '2,15p' "$0"; exit 0 ;; *) shift ;; esac; done
cd "$NAV8_REPO" || exit 2
gap() { if [ "$STAGE" = accept ]; then fail "$1" "$2" "$3"; else info "$1" "not yet: $3 (expected before acceptance; FAIL with --stage accept)"; fi; }
echo "NAV-008 repo checks: repo=$NAV8_REPO stage=$STAGE"
# Files considered: tracked files plus new untracked ones (work in progress), minus vendored and generated trees.
files() { { git ls-files; git ls-files --others --exclude-standard; } | sort -u \
          | grep -vE '(^|/)(node_modules|\.venv|test-results|results|data)/|package-lock\.json$|\.(png|jpg|jpeg|gif|pdf|pmtiles|pbf|zst|ico|woff2?)$' \
          | while read -r f; do [ -f "$f" ] && printf '%s\0' "$f"; done; }

# RS-01 no tracked .env with real values
envs="$(git ls-files | grep -E '(^|/)\.env$|(^|/)[^/]*\.local\.env$' || true)"
[ -z "$envs" ]; check "RS-01.no_env_files_in_git (AC 21)" $? "no .env / *.local.env tracked" "${envs:-none}"

# RS-02 strong secret patterns (fail)
strong='-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{40,}|xox[baprs]-[A-Za-z0-9-]{10,}|sk_live_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{35}|glpat-[A-Za-z0-9_-]{20}'
hits="$(files | xargs -0 grep -nIE -- "$strong" 2>/dev/null | cut -c1-140 || true)"
[ -z "$hits" ]; check "RS-02.no_private_keys_or_tokens (AC 21)" $? "no key/token patterns" "$(head -3 <<<"$hits" | sed -E 's/(:[0-9]+:).*/\1<masked>/' | tr '\n' ' ')"

# RS-03 secret-looking assignments with a value (review list; fail only in env/compose/infra files)
assign='(^|[^A-Za-z_])(PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|RESTIC_PASSWORD|UPTIME_PUSH_URL[A-Z_]*|PUSH_TOKEN)[A-Za-z_]*[ ]*[=:][ ]*["'"'"']?[^ "'"'"'<>{}$#][^ "'"'"']{5,}'
hits="$(files | xargs -0 grep -nIE -- "$assign" 2>/dev/null | grep -E '(\.env[^/]*|compose[^/]*\.ya?ml|^infra/|Caddyfile|\.service|\.timer):' \
        | grep -vE '_FILE[ ]*=|=[ ]*(/|\./|\$\{|changeme|example|<)|PASSWORD_FILE' | cut -c1-120 || true)"
[ -z "$hits" ]; check "RS-03.no_secret_values_in_config (AC 21)" $? "secret keys empty or pointing to a file outside git" \
  "$(sed -E 's/([=:]).*/\1<masked>/' <<<"$hits" | head -5 | tr '\n' ' ')"

# RS-04 host IP never committed (value only from the local env; never printed). Also git history.
for v in STAGING_IPV4 STAGING_IPV6; do
  val="${!v:-}"
  if [ -z "$val" ]; then skip "RS-04.$v.not_in_repo (AC 21)" "$v not set in staging.local.env (set it to scan for the real address)"; continue; fi
  wt="$(files | xargs -0 grep -lF -- "$val" 2>/dev/null | grep -v 'staging.local.env' || true)"
  hist="$(git log --all -p -S "$val" --format='%h' 2>/dev/null | grep -cE '^[0-9a-f]{7,}$' || true)"
  [ -z "$wt" ] && [ "${hist:-0}" = 0 ]; check "RS-04.$v.not_in_repo (AC 21)" $? "address absent from files and history" \
    "files: ${wt:-none}; commits touching it: ${hist:-0}"
done

# RS-05 every KEY= in each .env.example has a comment line directly above it (or inline), and secret keys are empty
for ex in backend/.env.example infra/staging/.env.example; do
  if [ ! -f "$ex" ]; then
    [ "$ex" = infra/staging/.env.example ] && gap "RS-05.env_example_present.$ex (AC 21)" "$ex exists" "missing"
    continue
  fi
  # A comment may cover a pair of adjacent keys (e.g. "Photon release jar and its sha256." above *_URL and *_SHA256).
  bad="$(awk '/^[A-Z][A-Z0-9_]*=/{ if (p1 !~ /^#/ && !(p1 ~ /^[A-Z][A-Z0-9_]*=/ && p2 ~ /^#/) && $0 !~ / #/) print FILENAME":"NR": "$1 }
             {p2=p1; p1=$0}' "$ex" | cut -d= -f1)"
  [ -z "$bad" ]; check "RS-05.every_key_commented.$ex (AC 21)" $? "a '# ...' line above every KEY=" "$(tr '\n' ' ' <<<"$bad")"
  sec="$(grep -E '^(RESTIC_PASSWORD|UPTIME_PUSH_URL[A-Z_]*|[A-Z_]*(TOKEN|SECRET|PASSWORD))=.+' "$ex" | cut -d= -f1 || true)"
  [ -z "$sec" ]; check "RS-06.secret_keys_empty.$ex (AC 21)" $? "secret keys present but empty" "$(tr '\n' ' ' <<<"$sec")"
done
keys="$(cat backend/.env.example infra/staging/.env.example 2>/dev/null | grep -oE '^#? ?[A-Z][A-Z0-9_]*=' | tr -d '# =' | sort -u)"
for k in STAGING_HOST ACME_EMAIL CADDY_IMAGE CORS_ALLOWED_ORIGINS OSM_PBF_URL; do
  grep -qx "$k" <<<"$keys" || gap "RS-07.key_documented.$k (AC 21, design section 13)" "$k in a .env.example" "missing (names are backend's choice; confirm the equivalent)"
done
grep -qE '^(GATEWAY_RATE|RATE_)' <<<"$keys" && pass "RS-07.rate_limit_keys_documented (AC 21)" || gap "RS-07.rate_limit_keys_documented (AC 21)" "rate-limit keys" "missing"

# RS-08 infra deliverables present (AC 21 list). Presence only; content is reviewed by hand (test plan section 5.9).
for item in "provision:bootstrap|provision|setup-host|playbook" "deploy:deploy" "rebuild:rebuild" "backup:backup|restic" \
            "restore:restore" "monitoring:uptime|monitor|diskcheck|heartbeat" "certificate:Caddyfile|caddy" "runbook:runbook|RUNBOOK"; do
  name="${item%%:*}"; pat="${item#*:}"
  f="$( { git ls-files infra; git ls-files --others --exclude-standard infra; } 2>/dev/null | grep -iE "$pat" | head -1)"
  [ -z "$f" ] && f="$( { git ls-files docs backend; git ls-files --others --exclude-standard docs backend; } | grep -iE "staging.*($pat)|($pat).*staging" | head -1)"
  [ -n "$f" ] && pass "RS-08.infra_has_$name (AC 21)" "$f" || gap "RS-08.infra_has_$name (AC 21)" "a $name script/doc under infra/ (or the runbook)" "none found"
done
if need gitleaks; then
  gitleaks detect --no-banner --redact --log-level error >/dev/null 2>&1; check "RS-09.gitleaks_history (AC 21)" $? "gitleaks: no leaks" "gitleaks reported leaks (run: gitleaks detect --redact -v)"
else skip "RS-09.gitleaks_history" "gitleaks not installed (optional full-history scan)"; fi

# RS-10 AC 23: staging URL in openapi servers and backend/README.md, not the placeholder
srv="$(grep -oE 'url: https://[^ ]+' docs/architecture/api/openapi.yaml | grep -v localhost | sed 's/url: //' | head -1)"
if [ -z "$srv" ] || grep -q 'example-placeholder' <<<"$srv"; then gap "RS-10.openapi_staging_server (AC 23)" "real https://staging.<domain> in servers" "${srv:-none}"
else
  pass "RS-10.openapi_staging_server (AC 23)" "$srv"
  [ -n "${STAGING_HOST:-}" ] && { [ "$srv" = "https://$STAGING_HOST" ]; check "RS-10.openapi_matches_local_host (AC 23)" $? "https://$STAGING_HOST" "$srv"; }
  grep -qF "$srv" backend/README.md; check "RS-11.readme_lists_staging_url (AC 23)" $? "$srv in backend/README.md" "$(grep -c staging backend/README.md) staging mentions, URL absent"
fi

# RS-12 AC 24: a dated legal-review row (date, reviewer role, outcome) in decisions.md
row="$(grep -iE 'legal (counsel|review)|counsel review' docs/requirements/decisions.md | grep -iE '\| *(go|go with conditions|no go)\b|outcome' | grep -oE '^\| *D[0-9]+ *\| *[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)"
if [ -n "$row" ]; then info "RS-12.counsel_record (AC 24)" "candidate row: $row  -> verify by hand: date <= first outside invitation, role, outcome, conditions (checklist Q10)"
else gap "RS-12.counsel_record (AC 24)" "a decisions.md row recording the counsel review with date and outcome" "none"; fi
manual "RS-13.first_invitation_date (AC 24)" "record the date the staging URL was first given to anyone outside the team; it must be >= the counsel record date"
nav8_summary
