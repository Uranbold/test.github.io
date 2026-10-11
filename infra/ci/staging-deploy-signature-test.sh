#!/usr/bin/env bash
# SEC-4A BE-9: local test of the signed-tag rules of infra/staging/bin/deploy.sh (about 5 s; needs git >= 2.34,
# ssh-keygen and root, no Docker, no network). Runs the real deploy.sh + nav-env.sh against a scratch "origin" and a
# scratch checkout whose tags carry a stub deploy.sh (so the re-exec after the checkout only prints what it got).
#   infra/ci/staging-deploy-signature-test.sh
# Cases: no DEPLOY_ALLOWED_SIGNERS -> refused; one-off DEPLOY_ALLOW_UNSIGNED=1 -> deployed with a warning and
# "signer=unsigned" in deployments.log; DEPLOY_ALLOW_UNSIGNED in .env -> refused; with an allowed-signers file:
# unsigned annotated tag, lightweight tag, tag signed by an unknown key -> refused; tag signed by the allowed key ->
# verified + deployed (checkout = the tag's commit); the same tag moved at origin -> refused; signers file writable by
# group, a symlink, inside the checkout, or not owned by root -> refused.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
fail() { echo "FAIL: $*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || { echo "SKIP: needs root (deploy.sh refuses other users)"; exit 0; }
command -v ssh-keygen >/dev/null || { echo "SKIP: ssh-keygen not installed (apt-get install openssh-client)"; exit 0; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
chmod 755 "$T"
export GIT_CONFIG_GLOBAL=$T/gitconfig GIT_CONFIG_NOSYSTEM=1
git config --global user.name "nav release"; git config --global user.email "release@example.invalid"
git config --global init.defaultBranch main; git config --global advice.detachedHead false
git config --global gpg.format ssh

# the real scripts, outside the checkout (NAV_ROOT points at the checkout)
mkdir -p "$T/runner/infra/staging/bin"
cp "$ROOT/infra/staging/bin/deploy.sh" "$ROOT/infra/staging/bin/nav-env.sh" "$T/runner/infra/staging/bin/"
DEPLOY=$T/runner/infra/staging/bin/deploy.sh

# origin with commits whose deploy.sh is a stub
git init -q --bare "$T/origin.git"
git clone -q "$T/origin.git" "$T/work" 2>/dev/null
mkdir -p "$T/work/infra/staging/bin"
cat > "$T/work/infra/staging/bin/deploy.sh" <<'EOF'
#!/usr/bin/env bash
echo "STUB-AFTER $1 $2 $(git -C "$NAV_ROOT" rev-parse HEAD)"
EOF
chmod 755 "$T/work/infra/staging/bin/deploy.sh"
echo one > "$T/work/f"; git -C "$T/work" add -A; git -C "$T/work" commit -q -m one
echo two > "$T/work/f"; git -C "$T/work" commit -q -am two
C1=$(git -C "$T/work" rev-parse HEAD~1); C2=$(git -C "$T/work" rev-parse HEAD)
ssh-keygen -q -t ed25519 -N '' -C release -f "$T/good"
ssh-keygen -q -t ed25519 -N '' -C stranger -f "$T/evil"
sign() { git -C "$T/work" -c user.signingkey="$1" tag -s -m "release $2" "$2" "$3"; }
sign "$T/good.pub" v1-good "$C1"
sign "$T/evil.pub" v1-evil "$C1"
git -C "$T/work" tag -a -m unsigned v1-unsigned "$C1"
git -C "$T/work" tag v1-light "$C1"
git -C "$T/work" push -q origin main --tags 2>/dev/null
git clone -q "$T/origin.git" "$T/nav" 2>/dev/null
git -C "$T/nav" checkout -q "$C1"
git -C "$T/nav" tag -l | xargs -r git -C "$T/nav" tag -d >/dev/null   # the host starts with no tags

# allowed signers: root-owned file in a root-owned directory, outside the checkout
install -d -m 755 "$T/etc"
printf 'release@example.invalid namespaces="git" %s\n' "$(cut -d' ' -f1,2 "$T/good.pub")" > "$T/etc/allowed_signers"
chmod 644 "$T/etc/allowed_signers"
envf() {  # envf [extra lines]
    { printf 'STAGING_HOST=staging.example.invalid\nACME_EMAIL=ops@example.invalid\n'
      printf 'CORS_ALLOWED_ORIGINS=https://demo.example.invalid\nNAV_DATA_ROOT=%s/data\nNAV_LOCK_FILE=%s/lock\n' "$T" "$T"
      printf '%s\n' "$@"; } > "$T/stg.env"
}
run() {  # run <tag> [VAR=value ...] -> output in $T/out, exit code in $rc
    local tag=$1; shift
    rc=0; env NAV_ROOT="$T/nav" NAV_ENV_FILE="$T/stg.env" NAV_STATE_DIR="$T/state" "$@" "$DEPLOY" "$tag" > "$T/out" 2>&1 || rc=$?
}
expect_refused() {  # expect_refused <label> <text>
    [[ $rc -ne 0 ]] || { cat "$T/out"; fail "$1: deployed, expected a refusal"; }
    grep -q "$2" "$T/out" || { cat "$T/out"; fail "$1: refusal without '$2'"; }
    ! grep -q STUB-AFTER "$T/out" || fail "$1: the tag's deploy.sh ran"
    echo "   refused: $1"
}

echo "== no allowed-signers file"
envf
run v1-good; expect_refused "DEPLOY_ALLOWED_SIGNERS unset" "DEPLOY_ALLOWED_SIGNERS is not set"
envf "DEPLOY_ALLOW_UNSIGNED=1"
run v1-good; expect_refused "DEPLOY_ALLOW_UNSIGNED in .env" "must not be set in infra/staging/.env"
envf
run v1-unsigned DEPLOY_ALLOW_UNSIGNED=1
[[ $rc -eq 0 ]] && grep -q "STUB-AFTER --after-checkout v1-unsigned $C1" "$T/out" || { cat "$T/out"; fail "escape hatch did not deploy"; }
grep -q '"level":"warn","msg":"UNSIGNED DEPLOY' "$T/out" || fail "unsigned deploy not logged as a warning"
grep -q "tag=v1-unsigned .* signer=unsigned" "$T/state/deployments.log" || fail "unsigned deploy not in deployments.log"
echo "   one-off DEPLOY_ALLOW_UNSIGNED=1: deployed, warning logged, signer=unsigned in deployments.log"

echo "== allowed-signers file configured"
envf "DEPLOY_ALLOWED_SIGNERS=$T/etc/allowed_signers"
run v1-unsigned; expect_refused "unsigned annotated tag" "does not verify"
run v1-light; expect_refused "lightweight tag" "lightweight tag"
run v1-evil; expect_refused "tag signed by an unknown key" "does not verify"
run v1-unsigned DEPLOY_ALLOW_UNSIGNED=1; expect_refused "escape hatch ignored once a signers file exists" "does not verify"
run v1-good
[[ $rc -eq 0 ]] && grep -q "STUB-AFTER --after-checkout v1-good $C1" "$T/out" || { cat "$T/out"; fail "signed tag was not deployed"; }
grep -q '"msg":"tag signature verified".*"signer":"release@example.invalid ED25519 SHA256:' "$T/out" || { cat "$T/out"; fail "signer not logged"; }
grep -q "tag=v1-good object=.* commit=$C1 signer=release@example.invalid_ED25519_SHA256:" "$T/state/deployments.log" || fail "deployments.log entry"
echo "   signed tag: verified (principal + key fingerprint logged), checkout = $C1, stub re-exec ran"

echo "== moved tag"
git -C "$T/work" tag -d v1-good >/dev/null
sign "$T/good.pub" v1-good "$C2"
git -C "$T/work" push -q --force origin refs/tags/v1-good 2>/dev/null
run v1-good; expect_refused "tag moved at origin (even with a valid signature)" "was moved at origin"
[[ "$(git -C "$T/nav" rev-parse 'refs/tags/v1-good^{commit}')" == "$C1" ]] || fail "the local tag was overwritten"

echo "== unusable allowed-signers files"
chmod 664 "$T/etc/allowed_signers"; run v1-evil; expect_refused "signers file writable by group" "writable by group or others"
chmod 644 "$T/etc/allowed_signers"
ln -s "$T/etc/allowed_signers" "$T/etc/link"; envf "DEPLOY_ALLOWED_SIGNERS=$T/etc/link"
run v1-evil; expect_refused "signers file is a symlink" "not a regular file"
cp "$T/etc/allowed_signers" "$T/nav/allowed_signers"; envf "DEPLOY_ALLOWED_SIGNERS=$T/nav/allowed_signers"
run v1-evil; expect_refused "signers file inside the checkout" "inside the git checkout"
rm -f "$T/nav/allowed_signers"
chown 65534 "$T/etc/allowed_signers"; envf "DEPLOY_ALLOWED_SIGNERS=$T/etc/allowed_signers"
run v1-evil; expect_refused "signers file not owned by root" "not owned by root"
echo "OK: deploy signature checks passed"
