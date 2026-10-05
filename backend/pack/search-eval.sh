#!/usr/bin/env bash
# NAV-023: build search.sqlite twice from a Photon dump and measure it (host Python, no Docker, ~1-3 min).
#   D168 gate (AC 17, exit 1 on fail), AC 5 determinism (two builds: SHA-256 + golden results), and with ONLINE also
#   the D198 held-out report (AC 19, report only) and the AC 20 reverse comparison.
#
#   pack/search-eval.sh <dump> [online-base-url] [out-dir]
#   make search-eval DUMP=data/sources/photon-dump [ONLINE=http://127.0.0.1:8080] [OUT=<dir for JSON reports>]
#
# ONLINE must serve Photon from the slot the dump belongs to; only read-only GET /v1/search and /v1/reverse are sent
# (<= 2 per second). The two builds go to a temporary directory that is always deleted (disk: ~50 MB while running).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dump="${1:?usage: search-eval.sh <dump> [online-base-url] [out-dir]}"
online="${2:-}"
out="${3:-}"
py="${PYTHON:-python3}"
work="$(mktemp -d "${TMPDIR:-/tmp}/nav023-search-eval.XXXXXX")"
trap 'rm -rf "${work:?}"' EXIT
[[ -n "$out" ]] && mkdir -p "$out"
rep() { [[ -n "$out" ]] && echo "--out $out/$1.json" || true; }

"$py" "$here/search_builder.py" build --dump "$dump" --out "$work/a.sqlite"
"$py" "$here/search_builder.py" build --dump "$dump" --out "$work/b.sqlite" >/dev/null
a="$(sha256sum "$work/a.sqlite" | cut -d' ' -f1)"; b="$(sha256sum "$work/b.sqlite" | cut -d' ' -f1)"
echo "{\"job\": \"search-eval-build\", \"sha256_equal\": $([[ "$a" == "$b" ]] && echo true || echo false), \"sha256\": \"$a\", \"gzip_bytes\": $(gzip -6 -c "$work/a.sqlite" | wc -c)}"
"$py" "$here/search_builder.py" selftest --db "$work/a.sqlite" --query "Сүхбаатар"
rc=0
# shellcheck disable=SC2046
"$py" "$here/search_eval.py" golden --db "$work/a.sqlite" --db2 "$work/b.sqlite" ${online:+--online "$online"} $(rep golden) || rc=$?
if [[ -n "$online" ]]; then
  # shellcheck disable=SC2046
  "$py" "$here/search_eval.py" heldout --db "$work/a.sqlite" --online "$online" $(rep heldout)
  # shellcheck disable=SC2046
  "$py" "$here/search_eval.py" reverse --db "$work/a.sqlite" --online "$online" $(rep reverse)
fi
[[ "$a" == "$b" ]] || { echo "determinism: the two builds differ" >&2; rc=1; }
exit "$rc"
