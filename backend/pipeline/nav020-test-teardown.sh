#!/usr/bin/env bash
# NAV-020 dev-container test teardown (AC 32): remove every container (including pack one-shots), volume and network
# of the test project and delete the test root. The shared dev stack (project navmn) and backend/data are not touched.
#   backend/pipeline/nav020-test-teardown.sh [TEST_ROOT]        default /var/tmp/nav020-test
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
ROOT=${1:-/var/tmp/nav020-test}
ENV=$ROOT/nav020.env
[[ "$ROOT" == /* && "$ROOT" != / ]] || { echo "TEST_ROOT must be an absolute path other than /" >&2; exit 2; }

project=navmn-nav020
if [[ -r "$ENV" ]]; then
    project=$(sed -n 's/^NAV_COMPOSE_PROJECT=//p' "$ENV" | tail -n1)
fi
[[ "$project" != navmn && -n "$project" ]] || { echo "refusing: project '$project' is the shared dev stack" >&2; exit 2; }

if [[ -r "$ENV" ]]; then
    docker compose -p "$project" --project-directory "$BACKEND" -f "$BACKEND/compose.slots.yaml" --env-file "$ENV" \
        --profile '*' down -v --remove-orphans --timeout 20 || true
fi
ids=$(docker ps -aq --filter "label=com.docker.compose.project=$project"; docker ps -aq --filter "name=^$project-pack-")
if [[ -n "$ids" ]]; then docker rm -f $ids >/dev/null; fi
for n in $(docker network ls -q --filter "label=com.docker.compose.project=$project"); do docker network rm "$n" >/dev/null; done
for v in $(docker volume ls -q --filter "label=com.docker.compose.project=$project"); do docker volume rm "$v" >/dev/null; done
rm -rf "$ROOT"
echo "{\"job\":\"nav020-test-teardown\",\"project\":\"$project\",\"containers\":$( (docker ps -aq --filter "label=com.docker.compose.project=$project"; docker ps -aq --filter "name=^$project-pack-") | wc -l),\"networks\":$(docker network ls -q --filter "label=com.docker.compose.project=$project" | wc -l),\"volumes\":$(docker volume ls -q --filter "label=com.docker.compose.project=$project" | wc -l),\"root_exists\":$([[ -e "$ROOT" ]] && echo true || echo false)}"
