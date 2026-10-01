#!/usr/bin/env bash
# Builds the Ferrostar Rust core for the HOST (linux-x86_64 / macOS) so JVM unit tests can load the real
# NavigationSession through JNA (ADR-0009 §10, NAV-005 M-1). The Android AAR only ships Android ABIs.
#
#   tools/build-host-ferrostar.sh [version]          # default: the version pinned in gradle/libs.versions.toml
#
# Output: $NAV_FERROSTAR_HOST_CACHE/<version>/libferrostar.{so,dylib}
#         (default cache: ${XDG_CACHE_HOME:-$HOME/.cache}/navmn/ferrostar-host), never inside the repository.
#
# Steps: download ferrostar-<version>.crate from static.crates.io, verify it against the crates.io index checksum,
# build it with `cargo build --release --lib` (--locked when the crate ships a Cargo.lock) and assert that uniffi
# resolves to the version Ferrostar's Kotlin bindings were generated with (UniFFI checksums must match).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
VERSION="${1:-$(sed -n 's/^ferrostar *= *"\([^"]*\)".*/\1/p' "$ROOT/gradle/libs.versions.toml")}"
EXPECTED_UNIFFI="${NAV_FERROSTAR_UNIFFI:-0.31.1}"
CACHE="${NAV_FERROSTAR_HOST_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/navmn/ferrostar-host}"
OUT="$CACHE/$VERSION"

case "$(uname -s)" in
  Darwin) LIB=libferrostar.dylib ;;
  *) LIB=libferrostar.so ;;
esac

if [[ -f "$OUT/$LIB" && -f "$OUT/OK" ]]; then
  echo "host Ferrostar $VERSION already built: $OUT/$LIB"
  exit 0
fi

command -v cargo >/dev/null || { echo "cargo not found (install Rust: https://rustup.rs)" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# crates.io sparse index path for "ferrostar": fe/rr/ferrostar
EXPECTED_SHA="$(curl -fsS https://index.crates.io/fe/rr/ferrostar \
  | grep "\"vers\":\"$VERSION\"" \
  | sed -n 's/.*"cksum":"\([0-9a-f]\{64\}\)".*/\1/p')"
[[ -n "$EXPECTED_SHA" ]] || { echo "ferrostar $VERSION not found in the crates.io index" >&2; exit 3; }

curl -fsSL -o "ferrostar-$VERSION.crate" "https://static.crates.io/crates/ferrostar/ferrostar-$VERSION.crate"
ACTUAL_SHA="$( (sha256sum "ferrostar-$VERSION.crate" 2>/dev/null || shasum -a 256 "ferrostar-$VERSION.crate") | cut -d' ' -f1)"
if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
  echo "checksum mismatch for ferrostar-$VERSION.crate: $ACTUAL_SHA != $EXPECTED_SHA" >&2
  exit 4
fi
tar xzf "ferrostar-$VERSION.crate"
cd "ferrostar-$VERSION"

LOCKED=()
if [[ -f Cargo.lock ]]; then LOCKED=(--locked); fi
cargo build --release --lib "${LOCKED[@]}"

RESOLVED_UNIFFI="$(cargo tree -i uniffi --depth 0 -e normal 2>/dev/null | head -1 | sed -n 's/^uniffi v\([0-9.]*\).*/\1/p')"
if [[ "$RESOLVED_UNIFFI" != "$EXPECTED_UNIFFI" ]]; then
  echo "uniffi resolved to '$RESOLVED_UNIFFI', expected $EXPECTED_UNIFFI (the AAR's bindings would not match)" >&2
  exit 5
fi

mkdir -p "$OUT"
cp "target/release/$LIB" "$OUT/$LIB"
printf 'ferrostar=%s\nuniffi=%s\ncrate_sha256=%s\n' "$VERSION" "$RESOLVED_UNIFFI" "$ACTUAL_SHA" > "$OUT/OK"
echo "built host Ferrostar $VERSION (uniffi $RESOLVED_UNIFFI): $OUT/$LIB"
