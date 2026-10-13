#!/usr/bin/env bash
# NAV-016 voice-pack generator runner behind the backend Makefile targets voice-pack-* (task B10).
#
#   run.sh test                                   unit tests (stub + fake server; no network, no token)
#   run.sh stub   OUT [VOICE] [PACK_VERSION]      stub pack (no token, no network)
#   run.sh build  OUT VOICE PACK_VERSION CACHE    REAL pack: Chimege, token from CHIMEGE_API_TOKEN in this shell only
#   run.sh verify DIR [POLICY]                    pack-format §8 checks (DIR = .../pack or its run directory)
#   run.sh render PACK PROMPTS OUT                render a prompt list (NAV-007 AC 10 harness)
#
# Environment: VOICE_PACK_RUNNER=image (default; the digest-pinned reference image, byte identity) | host (python +
# ffmpeg of this machine: VOICE_PACK_PYTHON with jsonschema, NAV_VOICE_PACK_FFMPEG or ffmpeg on PATH);
# VOICE_PACK_IMAGE (default navmn-voice-pack:1.0.0); PROMPTS=<prompt list> for stub/build; VOICE_PACK_DOCKER_ARGS
# (extra docker run arguments, e.g. --network host behind a local proxy; build only).
#
# The token is passed to the container by NAME only (docker run -e CHIMEGE_API_TOKEN); it never appears on a command
# line, in a file or in this script's output. OUT and CACHE must be outside the repository, or git-ignored inside it
# (pack-format §1.2); this script checks the host paths before mounting them.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
RUNNER=${VOICE_PACK_RUNNER:-image}
IMAGE=${VOICE_PACK_IMAGE:-navmn-voice-pack:1.0.0}
PY=${VOICE_PACK_PYTHON:-python3}
die() { echo "voice-pack: $*" >&2; exit 2; }

abspath() { python3 -c 'import os,sys; print(os.path.abspath(os.path.expanduser(sys.argv[1])))' "$1"; }

# pack-format §1.2: outside the repository, or inside it only where git check-ignore says ignored.
allowed() {
  local p; p=$(abspath "$1")
  case "$p/" in
    "$REPO"/*)
      git -C "$REPO" check-ignore -q -- "$p/.voicepack-probe" 2>/dev/null \
        || die "$2 $p is inside the repository and not git-ignored; use a path outside it"
      ;;
  esac
  printf '%s\n' "$p"
}

image_run() {
  docker image inspect "$IMAGE" >/dev/null 2>&1 || die "image $IMAGE not found: run make -C backend voice-pack-image"
  # shellcheck disable=SC2054  # commas are tmpfs options
  docker run --rm --user "$(id -u):$(id -g)" --read-only --tmpfs /tmp:rw,size=1g --cap-drop ALL \
    --security-opt no-new-privileges:true -v "$REPO:/repo:ro" "$@"
}

prompts_mount=()
prompts_arg=()
if [[ -n "${PROMPTS:-}" ]]; then
  pf=$(abspath "$PROMPTS"); [[ -f "$pf" ]] || die "PROMPTS $pf not found"
  if [[ $RUNNER == image ]]; then prompts_mount=(-v "$pf:/in/prompts.tsv:ro"); prompts_arg=(--prompts /in/prompts.tsv)
  else prompts_arg=(--prompts "$pf"); fi
fi

cmd=${1:-}; shift || true
case "$cmd" in
  test)
    if [[ $RUNNER == image ]]; then
      image_run --network none "$IMAGE" python -m unittest discover -s tests -t . -v
      # Repository scans need git, which the image does not have: run them here with the host python (stdlib only).
      (cd "$HERE" && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tests.test_repo tests.test_cache.PathRuleTest -v)
    else
      (cd "$HERE" && PYTHONDONTWRITEBYTECODE=1 "$PY" -m unittest discover -s tests -t . -v)
    fi
    ;;
  stub)
    [[ $# -ge 1 && -n "$1" ]] || die "usage: run.sh stub OUT [VOICE] [PACK_VERSION]"
    out=$(allowed "$1" OUT); voice=${2:-FEMALE5v2}; ver=${3:-1}; mkdir -p "$out"
    if [[ $RUNNER == image ]]; then
      image_run --network none -v "$out:/out" "${prompts_mount[@]}" "$IMAGE" \
        python -m voicepack build --voice "$voice" --pack-version "$ver" --synth stub --out /out --replace "${prompts_arg[@]}"
    else
      (cd "$HERE" && "$PY" -m voicepack build --voice "$voice" --pack-version "$ver" --synth stub --out "$out" --replace "${prompts_arg[@]}")
    fi
    echo "stub pack (host path): $out/$voice-v$ver/pack"
    ;;
  build)
    [[ $# -eq 4 ]] || die "usage: run.sh build OUT VOICE PACK_VERSION CACHE"
    out=$(allowed "$1" OUT); voice=$2; ver=$3; cache=$(allowed "$4" CACHE)
    [[ -n "${CHIMEGE_API_TOKEN:-}" ]] || die "CHIMEGE_API_TOKEN is not set (put it into this shell's environment only; see tools/voice-pack/README.md)"
    mkdir -p "$out" "$cache"
    if [[ $RUNNER == image ]]; then
      # shellcheck disable=SC2086  # VOICE_PACK_DOCKER_ARGS is a word list on purpose
      image_run ${VOICE_PACK_DOCKER_ARGS:-} -e CHIMEGE_API_TOKEN -e HTTPS_PROXY -e NO_PROXY -v "$out:/out" -v "$cache:/cache" \
        "${prompts_mount[@]}" "$IMAGE" \
        python -m voicepack build --voice "$voice" --pack-version "$ver" --synth chimege --out /out --cache /cache "${prompts_arg[@]}"
    else
      (cd "$HERE" && "$PY" -m voicepack build --voice "$voice" --pack-version "$ver" --synth chimege --out "$out" --cache "$cache" "${prompts_arg[@]}")
    fi
    echo "pack (host path): $out/$voice-v$ver/pack   review: $out/$voice-v$ver/review/index.html"
    ;;
  verify)
    [[ $# -ge 1 && -n "$1" ]] || die "usage: run.sh verify DIR [POLICY]"
    dir=$(abspath "$1"); policy=${2:-release}
    [[ -f "$dir/pack/manifest.json" ]] && dir=$dir/pack
    [[ -d "$dir" ]] || die "DIR $dir is not a directory"
    if [[ $RUNNER == image ]]; then
      image_run --network none -v "$dir:/pack:ro" "$IMAGE" python -m voicepack verify /pack --policy "$policy"
    else
      (cd "$HERE" && "$PY" -m voicepack verify "$dir" --policy "$policy")
    fi
    ;;
  render)
    [[ $# -eq 3 ]] || die "usage: run.sh render PACK PROMPTS OUT"
    pack=$(abspath "$1"); [[ -f "$pack/pack/manifest.json" ]] && pack=$pack/pack
    pf=$(abspath "$2"); out=$(allowed "$3" OUT); mkdir -p "$out"
    if [[ $RUNNER == image ]]; then
      image_run --network none -v "$pack:/pack:ro" -v "$pf:/in/prompts.tsv:ro" -v "$out:/out" "$IMAGE" \
        python -m voicepack render --pack /pack --prompts /in/prompts.tsv --out /out
    else
      (cd "$HERE" && "$PY" -m voicepack render --pack "$pack" --prompts "$pf" --out "$out")
    fi
    ;;
  *)
    die "usage: run.sh test | stub OUT [VOICE] [PACK_VERSION] | build OUT VOICE PACK_VERSION CACHE | verify DIR [POLICY] | render PACK PROMPTS OUT"
    ;;
esac
