# NAV-008 staging runbook (owner: backend-engineer; operated by the named PO-side operator)

- **Story:** [NAV-008](../../docs/requirements/stories/NAV-008-backend-hosting-staging.md). **Design:** [deployment-staging.md](../../docs/architecture/deployment-staging.md). **Decision:** [ADR-0005](../../docs/architecture/adr/0005-backend-hosting-staging.md) (Hostinger VPS KVM 4, Singapore; D25, D26).
- **Who runs this:** the named PO-side operator (D7), on the servers, by hand. Nobody on the development side has server access, and nobody ever asks for or receives a password or a private key. Only **public** keys are handed over.
- **Placeholders:** `staging.<domain>` = the API host name (STAGING_HOST), `<ops-host>` = the ops VM's public DNS name (OPS_HOST, e.g. `ops-staging.<domain>`), `<ip>` / `<ops-ip>` = the servers' addresses from the provider panels, `<tag>` = a git tag. Real names and IP addresses go **only** into `infra/staging/.env` on the server and into the operator's own notes, never into git (`infra/ci/staging-static-checks.sh` fails on any public IPv4 address under `infra/`).
- **Times:** the servers run in UTC. Asia/Ulaanbaatar = UTC+8 (Singapore too).

| When | UTC | Asia/Ulaanbaatar | What |
|---|---|---|---|
| Daily | 19:30 (+ up to 15 min) | 03:30 | `nav-rebuild.timer`: NAV-006 rebuild into a new slot, verified, then a pointer switch (0 s downtime) |
| Daily | 20:30 (+ up to 10 min) | 04:30 | `nav-backup.timer`: restic backup to the ops VM |
| Daily, only if an update needs it | 21:30 | 05:30 | unattended-upgrades reboot |
| Sundays | 18:30 | 02:30 | `nav-backup-check.timer`: `restic check` |
| Every 5 min | | | `nav-diskcheck.timer`: disk heartbeat |

---

## 1. Buy the right product (Hostinger or any KVM VPS)

The code is provider-neutral. On Hostinger:

1. Buy **VPS hosting, plan KVM 4** (4 vCPU, 16 GB RAM, 200 GB NVMe). **Not** "Web hosting", "Cloud hosting", "WordPress" or "Business" plans: those are shared hosting (hPanel "Websites", LiteSpeed web server, no root, no Docker) and **cannot run this stack**.
2. Location: **Singapore** (D25). Operating system: **plain Ubuntu 24.04 LTS** (no control panel, no application template).
3. At creation, add the operator's **SSH public key** (section 2) and let the panel set a root password. The root password stays in the company password manager. After bootstrap it no longer opens SSH (root and password logins are refused); break-glass is the panel's **Emergency mode** (section 4 C, section 11), not this password.
4. Keep Hostinger's included weekly backups on. They are on-provider copies and do **not** replace the off-host backup (section 9).
5. Record the invoice in the cost table (section 14).

**Where the addresses are:** hPanel -> **VPS** -> *Manage* next to the server -> **Overview** ("VPS information"): the **IPv4** address and, if one is assigned, the **IPv6** address, plus the plan (KVM 4) and the operating system. Write them into the operator's own notes (never into git) and use them for `<ip>` and for DNS (section 3).

**Check that you really have a VPS** before going on (from the operator's laptop):
```sh
ssh root@<ip> 'cat /etc/os-release | grep PRETTY; nproc; free -g | head -2; df -h / | tail -1'
# expected: Ubuntu 24.04 LTS, 4, ~15 GB total, ~190-200 GB disk
```
If `ssh root@...` does not give a shell, or the address serves a Hostinger/LiteSpeed web page, it is web hosting, not a VPS. The address the PO first provided looked like that from outside; confirm the product in the panel.

**Definitive check, after bootstrap (section 4), as `nav-ops`:**
```sh
systemd-detect-virt                 # expected: kvm   (openvz / lxc / none = wrong product, stop and ask the PO)
docker run --rm hello-world         # expected: "Hello from Docker!"  (the full stack needs a working Docker Engine)
```

**Ops VM** (monitoring + backup target, section 10): a small KVM VPS (1 vCPU, 1-2 GB RAM, 20+ GB disk, Ubuntu 24.04) at a **different provider** than the staging host, with a public IPv4 address. It runs Uptime Kuma, a push-only Caddy on TCP 80/443 and the backup account.

## 2. SSH keys (handover without secrets)

On the operator's own laptop (once):
```sh
ssh-keygen -t ed25519 -C "nav-ops (role)" -f ~/.ssh/nav_ops_ed25519    # set a passphrase; the private key never leaves the laptop
cat ~/.ssh/nav_ops_ed25519.pub                                          # this PUBLIC line goes into the panel / to the server
```
Only the `.pub` line is ever copied anywhere. `bootstrap.sh` refuses a file that contains a private key and never prints keys (only fingerprints).

## 3. Panel firewall and DNS

- **Panel firewall** (Hostinger hPanel, VPS -> Firewall): allow inbound **TCP 22, 80, 443** (IPv4 and IPv6); drop everything else. This layer also covers anything Docker might publish by mistake (Docker's published ports bypass UFW).
- **Does the panel firewall also filter IPv6?** Check it, don't assume it (only if the VPS has an IPv6 address). Do it **before bootstrap** (UFW is still off then), with the panel rules above active:
  ```sh
  # on the VPS, as root: a temporary listener on a port the panel does NOT allow
  python3 -m http.server 8025 --bind :: >/dev/null 2>&1 &
  # from a machine OUTSIDE Hostinger that has IPv6 (the operator's laptop on an IPv6 network, or the ops VM):
  nc -6 -vz -w 5 <ipv6> 8025     # timeout/refused = the panel filters IPv6; "succeeded"/"open" = it does NOT
  nc -4 -vz -w 5 <ip> 8025       # same test over IPv4 must also fail (sanity check of the rules)
  # on the VPS: stop the listener
  kill %1
  ```
  Record the answer in section 15. If the panel does **not** filter IPv6, UFW's IPv6 rules (section 4) and "only Caddy publishes ports" are the IPv6 layers, and the external IPv6 port scan (AC 12) must still show only 22, 80, 443.
- **DNS** (company zone, DNS editor role): `staging.<domain>` **A** -> the VPS IPv4, and **AAAA** -> the VPS IPv6 if one is assigned. **TTL 300** during setup. Check: `dig +short staging.<domain> A` from outside. Caddy can only get a certificate after this resolves to the VPS.
- **DNS for the ops VM** (with the record above): `<ops-host>` (e.g. `ops-staging.<domain>`) **A** -> the ops VM's IPv4 (**AAAA** only if its IPv6 works). The ops VM's Caddy gets its certificate for this name, and the staging host's push URLs use it (section 10). Its panel firewall, if that provider has one, allows TCP 22, 80, 443.

## 4. Bootstrap (host baseline, idempotent)

As root on the new VPS (first and only root SSH session). First get the code into `/opt/nav` with **A** or **B**, then run bootstrap (**C**).

**A. Public repository**
```sh
apt-get update && apt-get install -y git
git clone https://github.com/<org>/<repo>.git /opt/nav && git -C /opt/nav checkout --quiet <tag>
```

**B. Private repository: the read-only deploy key belongs to `nav-ops`, never to root.** `deploy.sh` runs `git fetch` as the owner of `/opt/nav`, which is `nav-ops` after bootstrap, so the key and its ssh config must be in `nav-ops`'s home. The private key is created on the VPS and never leaves it; only its `.pub` line goes to GitHub.
```sh
apt-get update && apt-get install -y git
id nav-ops >/dev/null 2>&1 || useradd --create-home --shell /bin/bash nav-ops      # bootstrap.sh (C) completes this account
install -d -m 755 -o nav-ops -g nav-ops /opt/nav
runuser -u nav-ops -- sh -c 'umask 077 && mkdir -p ~/.ssh && ssh-keygen -q -t ed25519 -N "" -C "nav-staging deploy (read-only)" -f ~/.ssh/nav_deploy'
runuser -u nav-ops -- sh -c 'umask 077 && cat >> ~/.ssh/config' <<'EOF'
Host github.com
    User git
    IdentityFile ~/.ssh/nav_deploy
    IdentitiesOnly yes
EOF
runuser -u nav-ops -- sh -c 'ssh-keyscan -t ed25519 github.com 2>/dev/null >> ~/.ssh/known_hosts && ssh-keygen -lf ~/.ssh/known_hosts'
#   the SHA256 value printed must equal GitHub's published ED25519 fingerprint:
#   https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints
cat ~nav-ops/.ssh/nav_deploy.pub
#   GitHub -> repository -> Settings -> Deploy keys -> Add deploy key: paste this PUBLIC line, "Allow write access" OFF
runuser -u nav-ops -- git clone --quiet git@github.com:<org>/<repo>.git /opt/nav
runuser -u nav-ops -- git -C /opt/nav checkout --quiet <tag>
```
Check (also after bootstrap): `sudo runuser -u nav-ops -- git -C /opt/nav fetch --tags --dry-run origin && echo fetch-ok`.
Already cloned as root with `/root/.ssh/nav_deploy` (older runbook)? Move the key to `nav-ops` once, after bootstrap:
`sudo install -d -m 700 -o nav-ops -g nav-ops ~nav-ops/.ssh && sudo mv /root/.ssh/nav_deploy /root/.ssh/nav_deploy.pub ~nav-ops/.ssh/ && sudo chown nav-ops:nav-ops ~nav-ops/.ssh/nav_deploy ~nav-ops/.ssh/nav_deploy.pub`, then run the `config` and `known_hosts` lines of B, then the check.
Without any key on the VPS: copy a `git bundle` made on a laptop with `scp` and clone from it as `nav-ops`; later tags come the same way. No key or token is ever sent to anyone.

**C. Bootstrap**
```sh
printf '%s\n' 'ssh-ed25519 AAAA... nav-ops (role)' > /root/nav-ops.pub     # the operator's PUBLIC key line
/opt/nav/infra/staging/bootstrap/bootstrap.sh --ops-key-file /root/nav-ops.pub
#   or: --copy-root-keys   (reuses the public key(s) added in the panel)
```
Then, **before closing the root session**:
1. Open a **second** terminal: `ssh -i ~/.ssh/nav_ops_ed25519 nav-ops@<ip>` and `sudo -n true && echo sudo-ok`.
2. **Break-glass check (AC 20): confirm the options are offered, do not trigger them.** hPanel's "Browser terminal" is an SSH client (Hostinger: it works "exactly like a standard SSH connection"), so after hardening it refuses root and passwords like any other SSH client; it is **not** break-glass. Hostinger's break-glass paths, which do not depend on the hardened sshd:
   - **Emergency mode** (hPanel -> VPS -> *Manage* -> **Settings** -> **Emergency mode** tab): reboots the VPS into a rescue system that mounts the VPS disk under `/mnt`, with access details shown by the panel (active up to 24 h). Check that the tab and its toggle exist for this VPS. **Do not switch it on** (it reboots the host).
   - **SSH configuration reset** (hPanel -> VPS -> **Settings**, "Reset SSH configuration"; last resort): check that it is listed. **Do not click it.**
   - Record in section 15 "break-glass offered: Emergency mode yes/no, SSH reset yes/no". **Other providers:** confirm their out-of-band **VNC/serial console** or **rescue boot** instead (the path must not go through the VM's sshd).
3. Check the effective SSH settings: `sudo sshd -T | grep -E '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin|pubkeyauthentication|allowusers) '` -> `no`, `no`, `no`, `yes`, `nav-ops`.
4. Only then close the root session. Root SSH login is now refused, and so is any password login.

Re-running is safe: a second run prints `changes=0`. What it sets: UTC clock; `nav-ops` (sudo without password because the account has no password at all; key-only SSH); `/etc/ssh/sshd_config.d/00-nav-hardening.conf` (sorts before Ubuntu's `50-cloud-init.conf`, because sshd keeps the first value it reads); unattended-upgrades for the security pocket with reboot at 21:30 UTC; Docker Engine + Compose plugin from Docker's apt repository, **held** (`apt-mark showhold`), `daemon.json` with the `local` log driver (5 x 10 MB per container) and `live-restore`; journald 1 GB / 14 days; UFW `deny` incoming, `limit 22/tcp`, `allow 80,443/tcp`, IPv4+IPv6, **logging off**; 4 GB `/swapfile`, `vm.swappiness=10`; sysstat with 28 days of history; `/opt/nav` owned by `nav-ops`; `/var/lib/nav` for state.

Checks after bootstrap: `sudo ufw status verbose`, `swapon --show`, `docker version`, `systemctl status unattended-upgrades`, `sar -u 1 1`.

## 5. Configure

```sh
cd /opt/nav
cp infra/staging/.env.example infra/staging/.env && chmod 600 infra/staging/.env
nano infra/staging/.env     # STAGING_HOST, ACME_EMAIL (role mailbox), CORS_ALLOWED_ORIGINS; push URLs and RESTIC_* later
```
Every key is explained in `.env.example`. `infra/staging/.env` is the only config file on the server (git-ignored, backed up encrypted). `deploy.sh` refuses `*` in `CORS_ALLOWED_ORIGINS` and an error-log level other than `crit`.

> **Warning: no `make` service targets and no plain `docker compose` in `/opt/nav/backend` on staging.** Staging runs `backend/compose.slots.yaml` (NAV-006) under the project name `navmn`; `backend/compose.yaml` is the dev stack and has the same project name. `make up`, `down`, `restart`, `rebuild-data`, `clean-data`, `cors-check` or a plain `docker compose up/down` there would create `backend/.env` from the **dev** example (CORS `*`, dev OSM mirror), stop the staging gateway, delete the live data (`rebuild-data`) and start the gateway **without rate limits and off the `edge` network**: Caddy then answers `502` to everyone. `backend/Makefile` refuses those targets while `infra/staging/.env` exists. On staging use `infra/staging/bin/nav-compose <compose args>` (the same compose command with the staging files and `.env`), `deploy.sh`, and the NAV-006 targets `sudo make -C /opt/nav/backend rebuild | rollback | status` (they use `infra/staging/.env` automatically, section 7). Read-only targets (`make smoke`, `contract`, `ps`, `logs`) are harmless; `make stats`, `build-info` and `tiles-info` read the old `backend/data/`, use `make status` instead. If it happened anyway: `sudo rm -f /opt/nav/backend/.env && sudo /opt/nav/infra/staging/bin/deploy.sh "$(cat /var/lib/nav/deployed-tag)"` (rebuilds the data if it was deleted, about 10 min).

## 6. Deploy and roll back

```sh
sudo /opt/nav/infra/staging/bin/deploy.sh <tag>        # fetch, checkout tag, units, pull pinned images, up --wait, rate-limit check, smoke
sudo /opt/nav/infra/staging/bin/deploy.sh --status     # deployed / previous tag, last deployments
sudo /opt/nav/infra/staging/bin/deploy.sh --rollback   # deploy the previous tag again
```
- The **first** deploy on an empty host builds all data: `deploy.sh` sees no active slot and runs the NAV-006 pipeline's first build under its own lock (about 8-10 min of build on 4 vCPU plus about 2.5 GB of downloads; NAV-006 dev-container measurement 6 min with a warm cache). Record the real value in the log, section 15.
- Later deploys bring up the gateway, Caddy and the **active lane** (`valhalla-<lane>`, `photon-<lane>`, lane = `make status` `.active.lane`). A deploy that changes a lane image recreates that lane: a few seconds of route/search `502` (accepted like any deploy). Data switches never do this (section 7).
- Only Caddy publishes ports (80, 443 TCP). The gateway listens on `127.0.0.1:8080` (`curl -s localhost:8080/health` on the host works). The private verify gateway listens on `127.0.0.1:8089` only while a new slot is checked. Valhalla and Photon lanes publish nothing.
- `sudo infra/staging/bin/nav-compose ps` / `logs --tail=50 caddy gateway` / `restart gateway` = docker compose with the staging files.
- After the first deploy: `curl -sS https://staging.<domain>/health` -> `{"status":"ok"}`, and `curl -sI http://staging.<domain>/` -> `308` to `https://`.

## 7. Daily data rebuild (NAV-006)

**Design:** [ADR-0014](../../docs/architecture/adr/0014-daily-rebuild-slots-and-pointer-switch.md). **Story:** [NAV-006](../../docs/requirements/stories/NAV-006-daily-osm-rebuild-blue-green.md). This is the **only** path that changes served data (AC 43): the NAV-008 in-place rebuild and `nav-rollback-data.sh` are gone; `bin/nav-rebuild.sh` is now a thin wrapper.

### 7.1 How it works
Data lives in `NAV_DATA_ROOT` (default `/var/lib/nav/data`, outside the checkout, not backed up):
```
cache/      sources/ + tools/ (aux files, ~2.6 GB, one copy), osm/ photon-dump/ photon-index/ (by sha256)
slots/<id>/ one complete data set per build (id = run ID, UTC 20261004T193412Z): tiles/ valhalla/ photon/ build-info.json
lanes/      blue -> ../slots/<id>, green -> ../slots/<id>   (the slot each service lane mounts)
pointer/public/active.json   THE active slot; the gateway reads it on every request
state.json  previous slot, rolled-back checksums, last download, last run      runs/<id>.jsonl  per-run log (30 days)
```
One run: **lock** -> **reconcile** (leftovers of an interrupted run) -> **guards** (disk, memory) -> **fetch + validate** the extract from the ordered source list -> **Photon dump** (HEAD, download only if changed) -> **build** into `slots/<id>.partial` (tiles, routing graph and search index; the active lane keeps serving) -> rename to `slots/<id>` -> **verify** on the free lane through the private `gateway-verify` on `127.0.0.1:8089` (smoke, contract, reference routes P1->P3/P2/P6 within 20 %, artefact sizes >= 90 %) -> **switch** = one `rename(2)` of `pointer/public/active.json` (no reload, no restart, 0 failed requests) -> **post-switch smoke** through Caddy (automatic rollback on failure) -> **grace** 30 s -> stop the old lane -> keep exactly **2 slots** (active + previous).

### 7.2 Install the timer (once, part of the checklist in 7.9)
`deploy.sh` runs `install-units.sh`, which installs and enables `nav-rebuild.service` + `nav-rebuild.timer`: 19:30 UTC = 03:30 Asia/Ulaanbaatar (Mongolia has no DST), `RandomizedDelaySec=15min`, `Persistent=true` (a missed run is caught up after downtime). There is exactly one rebuild job. Check:
```sh
systemctl list-timers nav-rebuild.timer                 # NEXT ~ 19:30-19:45 UTC
systemctl cat nav-rebuild.service | grep -E 'ExecStart|KillMode|TimeoutStopSec'
#   ExecStart=/opt/nav/infra/staging/bin/nav-rebuild.sh   KillMode=mixed   TimeoutStopSec=90s
```

### 7.3 Commands
```sh
sudo make -C /opt/nav/backend status          # JSON, < 2 s, no lock (7.4)
sudo make -C /opt/nav/backend rebuild         # same lock, guards, validation, verification and switch as the timer
sudo make -C /opt/nav/backend rebuild FORCE=1             # build even if the extract is unchanged or was rolled back
sudo make -C /opt/nav/backend rebuild ACCEPT_SIZE_DROP=1  # one run: skip check 3(d) and the AC 13 size check (old/new size logged)
sudo make -C /opt/nav/backend rebuild ACCEPT_ROUTE_CHANGE=1  # one run: accept a reference route change > 20 %
sudo make -C /opt/nav/backend rebuild REFRESH_AUX=1       # download every auxiliary file and tool again (implies FORCE)
sudo make -C /opt/nav/backend rollback        # previous slot serves again within 60 s; refuses at once if there is none
sudo make -C /opt/nav/backend slot-checksums SLOT=<id>    # sha256 listing of a slot (Photon runtime files excluded)
journalctl -u nav-rebuild --since today       # the timer's run; one JSON line per step
ls -t /var/lib/nav/data/runs/ | head          # <run-id>.jsonl + smoke/contract/builder output of each run
```
`make` prints the pipeline's code as `Error <code>` and itself exits 2; the code is also in `make status` (`last_run.exit_code`). The timer (`nav-rebuild.sh`) keeps the pipeline's code. The overrides are refused on the timer's entry point.

### 7.4 Reading `make status`
```json
{"active":   {"slot":"20261004T193412Z","lane":"green","built_at":"...","osm_data_date":"2026-10-04T20:21:03Z",
              "osm_data_date_source":"replication_timestamp","photon_data_timestamp":"...","switched_at":"..."},
 "previous": {"slot":"20261003T193311Z", "...": "..."},
 "last_run": {"run_id":"...","started_at":"...","ended_at":"...","result":"success","step":null,"reason":null,"exit_code":0},
 "stale": false, "next_scheduled_run": "Sun 2026-10-05 19:37:12 UTC", "state_matches_pointer": true}
```
- `active` = what the gateway serves right now (read from the pointer). `osm_data_date_source`: `replication_timestamp` (Geofabrik header) or `last_modified` (HTTP header, sources without a header date).
- `previous` = the one-step rollback target; `null` after a rollback or on the first build.
- `stale: true` = the active OSM data date is older than 48 h: the alert hook fires and no heartbeat is sent until fresh data is switched in.
- `state_matches_pointer: false` = a run died between the pointer rename and the state write; the next run corrects `state.json` from the pointer (logged).
- `post_switch_pending` (normally `null`) = a run was interrupted after the switch but before its post-switch smoke finished. The old lane is still running; the next run checks the new slot first (7.6).

### 7.5 Exit codes and skip reasons
| Code | Result | Operator action |
|---|---|---|
| 0 | `success`, or `skipped: unchanged` (Geofabrik's file equals the active slot's; the active slot passed smoke and is <= 48 h old) | none; heartbeat sent |
| 2 | configuration or usage error (bad `.env` value, override on the timer, project with containers not from `compose.slots.yaml`) | read the message, fix `.env` |
| 10 | another rebuild, rollback, deploy or certificate dry run holds `/run/lock/nav-stack.lock` | wait; `systemctl status nav-rebuild` |
| 11 | `skipped: low disk` (free < `REBUILD_MIN_FREE_GB`) | free disk (section 11), then `make rebuild` |
| 12 | `skipped: low memory` (`MemAvailable` < `REBUILD_MIN_MEM_AVAILABLE_GB`) | find the memory user (`docker stats`), then `make rebuild` |
| 13 | `skipped: no valid source` (every source unreachable twice) | old data keeps serving; check Geofabrik reachability; stale alert after 48 h |
| 14 | `skipped: source was rolled back` (today's extract is the one you rolled back) | wait for a newer extract, or `make rebuild FORCE=1` after an OSM fix |
| 15 | `skipped: unchanged`, but the active slot failed smoke or is stale | `make status`; smoke output in `runs/<id>.unchanged-smoke.txt`; check Geofabrik updates |
| 20 | `failed` at validation (every source failed a check 3(a)-(f); the log names the check and the measured value) | 3(d) after a deliberate change: `make rebuild ACCEPT_SIZE_DROP=1`; truncated/HTML: retry later |
| 21 | `failed` at build (a builder exited non-zero, or free disk fell below the hard floor) | `runs/<id>.<builder>.log`; disk |
| 22 | `failed` at verification (smoke, contract, reference routes or sizes on the new slot) | `runs/<id>.verify-*.txt`; deliberate change: `ACCEPT_ROUTE_CHANGE=1` / `ACCEPT_SIZE_DROP=1`; data damage: fix in OSM (human mapper), wait for the next extract |
| 23 | `failed` after the switch (first build only: nothing to roll back to) | `runs/<id>.post-switch-smoke.txt`; Caddy/DNS |
| 24 | `rolled back` automatically: the post-switch smoke failed, the previous slot serves again. Step `reconcile`: the pending post-switch check of an interrupted run failed (7.6) | as 22; the rolled-back extract is skipped later (14) |
| 30 | rollback refused: no previous good slot (first build, or right after a rollback) | none possible; fix forward with `make rebuild FORCE=1` |
| 31 | rollback failed: the previous lane did not become healthy within 55 s; the active slot keeps serving | `nav-compose logs --tail=50 valhalla-<lane> photon-<lane>` |
| 40 | interrupted (SIGTERM/SIGINT); cleanup done within 60 s | `make status`; run again |

**Reference routes the live gateway cannot answer:** if the active (live) gateway returns an error or no route for P1->P3 or P1->P2 during verification, that route is not compared (AC 12 cannot apply) and the run is **not** failed for it. The run log has a `warn` event `reference route not compared: the live gateway gave no route` with `route`, `live_status` (HTTP status, 0 = no answer) and `new_status`. Check the live service (`make status`, smoke) before you trust that run: for that route, the new slot was checked only by smoke and contract. A missing route on the **new** slot still fails the run (22).

Failed, rolled-back, guard-skipped (11-14), unchanged-but-unhealthy (15), interrupted (40) and stale runs call `REBUILD_ALERT_CMD` once (placeholder, empty by default; 10 s timeout; never changes the exit code). The Uptime Kuma rebuild heartbeat (26 h) is pushed only after code 0.

### 7.6 Interrupted run, reboot, power cut (manual recovery)
- `systemctl stop nav-rebuild`, the 2 h start timeout or a reboot's SIGTERM: the pipeline stops the builders, the candidate lane and `gateway-verify`, deletes the partial slot and records `interrupted` within 60 s. The active slot serves throughout; the pointer is never touched by an interrupt. You can then run `make rollback` if needed.
- **Interrupted after the switch, during the post-switch smoke** (SIGTERM or `kill -9`): the new slot stays active, the **old lane keeps running**, and `state.json` keeps `post_switch_pending` (`make status`; after a SIGTERM the run's reason also says "post-switch check pending"). The next run's reconciliation runs `smoke.py` through `SMOKE_BASE_URL` first (`runs/<id>.pending-post-switch-smoke.txt`): pass -> the old lane is stopped and the run continues; fail -> the pointer goes back to the previous slot on the old lane (exit 24, step `reconcile`, alert). `make rollback` also clears the pending check.
- `kill -9`, power cut, host crash: nothing to do by hand. The kernel lock is gone with the process. After boot, Docker restarts the gateway and the lane that was running (`restart: unless-stopped`); the gateway serves exactly the slot in `pointer/public/active.json`. The **next run** reconciles: removes leftover builder and verify containers, stops a lane that is not the pointer's, deletes `*.partial`, `*.failed` and unreferenced slots, puts a set-aside aux cache back, and corrects `state.json`.
- To force that cleanup now: `sudo make -C /opt/nav/backend rebuild` (it reconciles first; with an unchanged extract it ends as `skipped: unchanged`).
- Never edit `pointer/public/active.json` by hand except in an emergency, and then only with a temp file + `mv` (the gateway reads it on every request).

### 7.7 Guards and how to change them
All in `infra/staging/.env` (each key is documented in `.env.example`); the next run uses the new value, no restart needed:

| Key | Staging default | Meaning |
|---|---|---|
| `REBUILD_MIN_FREE_GB` | 50 | free disk on `NAV_DATA_ROOT` before a run (`skipped: low disk`) |
| `REBUILD_HARD_FLOOR_FREE_GB` | 5 | the build stops below this, partial slot deleted |
| `REBUILD_MIN_MEM_AVAILABLE_GB` | 6.5 | `MemAvailable` before a run (`skipped: low memory`) |
| `REBUILD_MIN_EXTRACT_MB` | 40 | check 3(c) |
| `REBUILD_MIN_SIZE_RATIO` | 0.90 | check 3(d) (new extract vs active) |
| `REBUILD_REQUIRE_NOT_OLDER` | 1 | check 3(e) |
| `REBUILD_MAX_DATA_AGE_HOURS` | 48 | check 3(f) and `stale` |
| `REBUILD_ROUTE_MAX_DEVIATION` | 0.20 | AC 12 reference routes |
| `REBUILD_ARTEFACT_MIN_RATIO` | 0.90 | AC 13 PMTiles / graph / index sizes |
| `REBUILD_GRACE_SECONDS` | 30 | old lane keeps running after a switch (minimum 30) |
| `REBUILD_ALERT_CMD` | empty | alert hook (placeholder) |

Every run logs the values that are weaker than these defaults as `relaxed` in its `config` step. Record any change in the NAV-006 change log (change request).

### 7.8 Source list
`OSM_SOURCES` = space-separated, ordered `https://` URLs or `file:<absolute path>`. Each source is retried once after 30 s, then the next one is tried; the run log and the slot's `build-info.json` (`osm.source`) name the one used. Staging default: Geofabrik only (NAV-006 open question 1). Adding a fallback is a PO decision (integrity risk R2 for third-party mirrors); then e.g. `OSM_SOURCES=https://download.geofabrik.de/asia/mongolia-latest.osm.pbf https://<mirror>/mongolia.osm.pbf`. Geofabrik's `.md5` is compared with the active slot's before any download (an unchanged file is not downloaded), and an extract is downloaded at most once per Asia/Ulaanbaatar day.

**AC 15/16 measurement** (once, outside tester hours; there is no downtime now):
```sh
sudo bash -o pipefail -c '/opt/nav/infra/staging/bin/nav-stats-sampler.sh -- make -C /opt/nav/backend rebuild REFRESH_AUX=1 2>&1 | tee /var/lib/nav/ac15-$(date -u +%Y%m%dT%H%MZ).log'; echo "exit code: $?"
sudo make -C /opt/nav/backend status | jq '.last_run, .active'
grep -h '"step": "summary"' "$(ls -t /var/lib/nav/ac15-*.log | head -1)" | jq '{duration_s, builder_seconds}'   # AC 15 <= 1800 s
cat "$(ls -t /var/lib/nav/stats/*.summary.json | head -1)"   # peak_build_mb, steady_mb, ac16_ratio (<= 0.70), max_gap_s (<= 3)
```
`REFRESH_AUX=1` sets `cache/sources` and `cache/tools` aside, downloads every auxiliary file and tool again (the Protomaps jar is rebuilt with Maven) and builds a new slot. If the run fails, the old cache is put back; after a good run it is deleted. Downtime per service is **0 s** by design; the request loop of NAV-006 AC 15 is the evidence.

### 7.9 Checklist: install NAV-006 on the NAV-008 VPS (not executed yet; needs the host)
Fresh host (no interim rebuild ever ran): sections 4-6 as written; the first `deploy.sh` builds the first slot. Host that already runs the NAV-008 interim stack (`backend/compose.yaml` containers, data in `backend/data/`), all as root, outside tester hours:
1. `sudo systemctl disable --now nav-rebuild.timer` (stop the interim rebuild; the old stack keeps serving).
2. `git -C /opt/nav fetch --tags && git -C /opt/nav checkout <tag with NAV-006>` (do not run `deploy.sh` yet).
3. Add the new keys from `.env.example` to `infra/staging/.env` (`NAV_DATA_ROOT`, `NAV_LOCK_FILE`, `NAV_VERIFY_PORT`, `OSM_SOURCES`, `REBUILD_*`); remove `REBUILD_ROLLBACK_MIN_FREE_GB`, `REBUILD_SKIP_UNCHANGED`, `REBUILD_AUTO_ROLLBACK` (no longer read).
4. Seed the cache without downloading again (same filesystem, so `mv` is a rename): `sudo install -d -m 755 /var/lib/nav/data/cache && sudo mv /opt/nav/backend/data/sources /opt/nav/backend/data/tools /var/lib/nav/data/cache/` then `sudo rm -f /var/lib/nav/data/cache/sources/osm.pbf* /var/lib/nav/data/cache/sources/photon-dump*`. If `/var/lib/nav` is on another filesystem than `/opt/nav`, copy instead (`cp -a`, about 2.6 GB).
5. First slot while the old stack still serves: `sudo NAV_ALLOW_LEGACY_PROJECT=1 /opt/nav/infra/staging/bin/nav-pipeline rebuild`. It builds, verifies on `127.0.0.1:8089` and writes the pointer (the old gateway ignores it). Expected: `success`.
6. Switch the runtime (one deploy, a few seconds of Caddy's JSON backstop while the gateway is recreated): `sudo /opt/nav/infra/staging/bin/deploy.sh <tag>`. `--remove-orphans` removes the old `valhalla`, `photon` and builder containers.
7. `sudo make -C /opt/nav/backend status` (active slot, lane), `curl -sS https://staging.<domain>/health`, `python3 /opt/nav/backend/scripts/smoke.py --base-url https://staging.<domain>` -> exit 0.
8. `sudo systemctl enable --now nav-rebuild.timer`; `systemctl list-timers nav-rebuild.timer`; `systemctl cat nav-rebuild.service | grep ExecStart` -> `nav-rebuild.sh` (the NAV-006 wrapper).
9. Uptime Kuma: delete the daily maintenance window around the rebuild (section 10), keep the 26 h rebuild heartbeat.
10. After the next timer run (`make status`: `success` or `skipped: unchanged`): `sudo rm -rf /opt/nav/backend/data /var/lib/nav/rollback /var/lib/nav/last-rebuild.json /var/lib/nav/last-smoke.txt /var/lib/nav/osm.md5`.
11. Once outside tester hours: `make rebuild FORCE=1` with a request loop running (QA plan NAV-006) and `make rollback`; record both in section 15 (NAV-008 AC 15 downtime = 0 s).

## 7A. Offline Mongolia pack (NAV-020)

**Design:** [ADR-0017](../../docs/architecture/adr/0017-offline-mongolia-pack-android.md) §1, §4-§6 and Amendment A1; task file `docs/architecture/tasks/NAV-020-offline-pack-build-publication.md`. **Story:** [NAV-020](../../docs/requirements/stories/NAV-020-offline-pack-build-publication.md). **Contract:** `openapi.yaml` 0.6.x `getOfflinePackManifest`, `getOfflinePackFile`. Code: `backend/pipeline/nav_pack.py`, `backend/pack/`, `backend/gate2/`.

### 7A.1 What it does
After a NAV-006 run that ends `success` (switch done and post-switch smoke passed), the **pack step** runs in the same process and the same lock, after the grace period, the old-lane stop and slot cleanup. It never changes the NAV-006 result, exit code or heartbeat; its own result is the `pack` field of the run summary, `state.json` and `make status`. Nothing runs after `skipped: *`, `failed`, `rolled back` or `interrupted`.

The pack `mn` has three files, each with its own version (= the slot ID it was cut from), all gzip files under `NAV_DATA_ROOT/packs/mn/<version>/` (write-once directories) plus `packs/mn/manifest.json` (replaced only by one `rename(2)`):

| Kind | File | Source in the active slot | Cut when |
|---|---|---|---|
| `routing` | `routing.tar.gz` | `valhalla/valhalla_tiles.tar`, byte-identical | weekly part due: published routing >= `PACK_WEEKLY_MIN_AGE_DAYS` (7) old, and `PACK_WEEKDAY` (if set) is today in Asia/Ulaanbaatar |
| `search` | `search.sqlite.gz` | built from `sources/photon-dump` by `backend/pack/search_builder.py` in `PACK_SEARCH_BUILDER_IMAGE` (network none) | always together with `routing` (same version) |
| `tiles` | `basemap.pmtiles.gz` | `tiles/basemap.pmtiles` (z0-14), byte-identical | **only in a run that also cuts the weekly part**, when the published tiles are >= `PACK_TILES_MIN_AGE_DAYS` (28) old (so tiles are at most about 35 days old; ADR-0017 A1 item 6) |

Sub-steps (one JSON line each in the run log, `step` = `pack.<name>`): `select`, `copy` (hard links of the slot files into `packs/.work/<run>/`), `search_build`, `gzip` (deterministic: no name, mtime 0, level `PACK_GZIP_LEVEL`), `checksum` (from the bytes on disk; decompressed copies for the tests), `self_test` (PMTiles v3 z0-14 with P1-P6 + X1 inside the bounds; search DB `quick_check`, schema and `self_test.search` >= 1 row; every `.gz` decompresses to its `bytes`/`sha256`), `gate2` (below; also routes `self_test.route` on the candidate tar), `manifest` (schema-validated, version directory renamed into place, manifest renamed), `cleanup` (retention). An unchanged Photon dump gives a byte-identical `search.sqlite`, so phones skip it.

### 7A.2 Commands
```sh
sudo make -C /opt/nav/backend pack-status                 # the pack part of make status (< 2 s, no lock)
sudo make -C /opt/nav/backend pack-publish                # manual pack step: same lock as rebuild, reads only the active slot, cadence rules
sudo make -C /opt/nav/backend pack-publish FORCE=1        # cut the weekly part (routing + search) regardless of its age
sudo make -C /opt/nav/backend pack-publish TILES=1        # also cut the tiles (implies FORCE=1)
grep '"pack\.' /var/lib/nav/data/runs/<run-id>.jsonl | jq -c '{step,result,duration_s,reason}'
```
The timer never passes `FORCE`/`TILES`. A second command while the lock is held exits at once with code 10 and names the lock. A version directory is never rewritten: a second `FORCE=1` on the same active slot ends `not due (already published)`.

### 7A.3 Reading the pack part of `make status`
```json
"pack": {"enabled": true, "pack_version": "20261004T193412Z", "published_at": "2026-10-04T20:05:11Z",
         "files": {"tiles":   {"version": "20260927T193105Z", "data_timestamp": "2026-09-26T20:21:03Z", "download_bytes": 86400000},
                   "routing": {"version": "20261004T193412Z", "data_timestamp": "2026-10-03T20:21:02Z", "download_bytes": 25900000},
                   "search":  {"version": "20261004T193412Z", "data_timestamp": "2026-10-03T00:00:00Z", "download_bytes": 8300000}},
         "last_result": {"run_id": "...", "trigger": "rebuild", "result": "published", "step": null, "reason": null, "duration_s": 61.2},
         "gate2_engine": {"mode": "engine", "image": "navmn-gate2:0.6.3", "image_id": "sha256:...", "variant": "wrapper",
                          "valhalla_mobile_version": "0.6.3", "wrapper_commit": "b47ad5a9...", "valhalla_commit": "e2f017b1..."},
         "retained_manifests": 3}
```
`pack_version` = slot of the newest routing/search; `tiles.version` may be older (monthly). After a rollback republish (7A.7) `pack_version` and `published_at` go backwards on purpose (openapi 0.6.1).

### 7A.4 Results and operator action
`make pack-publish` exits with the code below; on the timer path the NAV-006 exit code is unchanged and the result is in the summary's `pack` field. Failed and low-disk results call `REBUILD_ALERT_CMD` exactly once (`NAV_RUN_RESULT=pack: <result>`, `NAV_RUN_STEP=pack.<sub-step>`). In every non-`published` case the served manifest is unchanged and phones keep what they have.

| Code | Result | Operator action |
|---|---|---|
| 0 | `published` | none |
| 0 | `not due` (the weekly part is younger than `PACK_WEEKLY_MIN_AGE_DAYS`, wrong weekday, or the version directory already exists) | none; `FORCE=1` only for a deliberate extra publication |
| 54 | `skipped (low disk)`: free < `PACK_MIN_FREE_GB` at start (the log has both values); nothing written | free disk (section 11), then `make pack-publish` |
| 55 | `skipped (not eligible)`: no complete active slot, the active slot was rolled back, or a NAV-006 post-switch check is pending | run `make rebuild` (it reconciles), then `make pack-publish` |
| 50 | `failed (gate 2)`: the Gate 2 engine differs from the server on a golden request (the log has one `gate 2 difference` line per request with the first differing field and both values), the image is missing or its labels do not match the pins, or the golden set file is missing | do **not** loosen the rule (ADR-0017 A1 item 13). Send the run log to the architect; a difference after a server or engine change needs the ADR-0017 §4 procedure (7A.5). The serving slot is not rolled back |
| 51 | `failed (self-test)`: tiles header (zoom / bounds), search DB (`quick_check`, schema, self-test query), a gzip round trip, or `self_test.route` not `Ok` on the candidate tar | `runs/<run-id>.jsonl` `pack.self_test` / `pack.gate2` line; usually bad source data: wait for the next rebuild or roll back the data |
| 52 | `failed (engine version mismatch)`: the app's `valhalla-mobile` pin (`mobile/android/gradle/libs.versions.toml`) differs from `PACK_GATE2_VALHALLA_MOBILE_VERSION` | rebuild the Gate 2 image for the app's version (7A.5), then set the key |
| 53 | `failed (build)`: search builder error, missing slot file, manifest rule or schema failure, `PACK_METHOD_URL` empty, configuration error | the `reason` names it; fix and `make pack-publish` |
| 40 | `failed (interrupted)`: SIGTERM/SIGINT during the step (stop, reboot); partial files deleted | none; the next run or `make pack-publish` starts clean |

**Interrupted step, `kill -9`, power cut:** the served manifest is always the last complete one. The next pack step deletes `packs/.work/*`, `packs/mn/*.partial`, temporary manifests, version directories no retained manifest refers to and stray pack containers, re-checks the SHA-256 of every file the served manifest refers to, and republishes the newest retained manifest that verifies if one is damaged (alert).

### 7A.5 Gate 2 engine: pin, build, change
The gate compares, strictly (distance to 1 m, duration to 1 s, manoeuvre type/modifier/exit, street names, every polyline6 point at 6 decimals, route count with `alternates: 2`, the OSRM code), the **shipped phone engine** on the candidate `routing.tar` with the **active server Valhalla** through `127.0.0.1:GATEWAY_PORT` (<= `PACK_GATE2_RATE` r/s), for every request of the golden set `PACK_GATE2_GOLDEN_SET` (QA fixture, NAV-020 AC 9). The engine is a host build of `valhalla-mobile` **0.6.3** = wrapper `b47ad5a9…` + Valhalla `e2f017b1…` (upstream 3.6.3), from `backend/gate2/Dockerfile`, unmodified; its config is the AAR's `default.json` (copy in `backend/gate2/`, SHA-256 checked) plus the ADR-0017 A1 item 5 overrides. **Never** use `ghcr.io/valhalla/valhalla:*` as the gate (the configuration refuses it).
- **Build** (once per pin change; needs >= 20 GB free, plan for 30 GB, 45-90 min on 4 vCPU): `sudo make -C /opt/nav/backend gate2-image` (fallback A1 item 2: `GATE2_VARIANT=upstream`), then `docker builder prune -f`. Or build elsewhere and move it: `docker save navmn-gate2:0.6.3 | gzip > gate2.tar.gz`, `docker load < gate2.tar.gz` (about 0.3-0.6 GB). Check: `docker image inspect navmn-gate2:0.6.3 --format '{{json .Config.Labels}}'` shows the two commits and `nav.gate2.variant`.
- **Change the pin** only together with an ADR-0017 note and the NAV-021 **Gate 1** re-run (ADR-0017 §4): new image tag, `PACK_GATE2_IMAGE`, `PACK_GATE2_VALHALLA_MOBILE_VERSION`, `PACK_GATE2_WRAPPER_COMMIT`, `PACK_GATE2_VALHALLA_COMMIT`, the AAR `default.json` copy and its SHA-256 in `nav_pack.py`. A server Valhalla upgrade (new `graph_builder`) needs the same ADR note first.
- `PACK_GATE2_MODE=evidence` (the server's own library on both sides) exists only for dev-container tests and is refused on `navmn`.

### 7A.6 Serving, rate limit, retention, disk
- The public gateway serves `/packs/mn/manifest.json` (`Cache-Control: no-cache`, strong `ETag`, 304) and `/packs/mn/<version>/<file>.gz` (Range/If-Range, `immutable`, no `Content-Encoding`, 416 JSON), from `NAV_DATA_ROOT/packs` mounted read-only (`compose.slots.yaml`). Other `/packs/` paths, dot files and `*.partial` are JSON 404. Caddy has no `encode` (it would add `Content-Encoding` and break the byte checks).
- **"packs" rate limit:** `GATEWAY_RATE_PACKS` (staging 2r/s) and `GATEWAY_BURST_PACKS` (20) per client IP, own zone; `/health`, tiles and `OPTIONS` are never limited; `/v1/*` limits are unchanged. A change needs a gateway recreate (`deploy.sh`). Starting values set with the architect; review after 2 weeks of real downloads.
- **Retention:** exactly the files referenced by the last `PACK_RETAIN_MANIFESTS` (3) published manifests are kept (copies in `packs/mn/.manifests/`, order in `.history.json`), so a download that started before a publication can still resume. Pack files are separate gzip files, so NAV-006 slot cleanup never affects them.
- **Disk:** about 0.2-0.4 GB steady state (three weekly parts of about 33 MB + one or two tiles files of about 86 MB) plus about 0.3 GB of `packs/.work/` during a run; logged as `packs_disk_bytes` by `pack.cleanup`. The Gate 2 image is about 0.3-0.6 GB, the builder image about 0.15 GB. All far below the NAV-006 guard (`REBUILD_MIN_FREE_GB` 50); `nav-diskcheck` (85 %) is unchanged.
- **Timing:** the pack step runs inside `nav-rebuild.service` (unchanged timer, 19:30 UTC + <= 15 min, `TimeoutStartSec=2h`). Target <= 15 min from start to the manifest rename on 4 vCPU (NAV-020 AC 27); measured in the dev container: see section 15. Rebuild (~10 min) + pack ends well before the 21:30 UTC reboot window. SIGTERM is handled within 60 s (`TimeoutStopSec=90s`).

### 7A.7 `make rollback` and the pack (NAV-020 AC 28)
When the served manifest refers to a file cut from the slot being rolled back, `make rollback` replaces it right after its pointer switch (one `rename(2)`, within 60 s) with a fresh copy of the newest retained manifest that refers to **no** file from that slot; `make status` shows `last_result.result = "rollback: republished"`. Phones whose installed `sha256` differs then download the older files (NAV-022). If no retained manifest is clean, the manifest stays, `rollback: no clean manifest` is recorded and the alert hook is called once; publish a clean pack with `make pack-publish FORCE=1 TILES=1` after the data is fixed. The rollback's own result and exit code never depend on this.

### 7A.8 Checklist: enable the pack step on the NAV-008 VPS (not executed yet; needs the host and the Gate 2 image)
1. NAV-006 is installed and `make status` shows a `success` run (section 7.9).
2. Add the `PACK_*` and `GATEWAY_*_PACKS` keys from `.env.example` to `infra/staging/.env`; set `PACK_METHOD_URL` to the public pipeline repository; keep `PACK_ENABLED=0`.
3. `sudo /opt/nav/infra/staging/bin/deploy.sh <tag>` (creates `NAV_DATA_ROOT/packs`, recreates the gateway with the `/srv/packs` mount and the packs zone). `curl -sS -o /dev/null -w '%{http_code}\n' https://staging.<domain>/packs/mn/manifest.json` -> `404` (nothing published yet).
4. Build or load the Gate 2 image (7A.5) and check its labels. QA's golden set file exists at `PACK_GATE2_GOLDEN_SET` in the deployed checkout.
5. `df -h /var/lib/nav` >= 5 GB free, then `sudo make -C /opt/nav/backend pack-publish` -> exit 0, `published`; `make pack-status` shows three files with the same version.
6. `python3 /opt/nav/backend/scripts/contract_check.py --base-url https://staging.<domain>` (from `backend/.venv`) -> exit 0 (pack cases included); `smoke.py` -> exit 0.
7. Set `PACK_ENABLED=1`. After the next timer run: `make status` -> `last_run.result=success` and `pack.last_result.result` = `published` or `not due`.
8. Record the first publication's sub-step durations (`grep '"pack\.' runs/<id>.jsonl`), `packs_disk_bytes` and the AC 27 total in section 15.

## 8. Certificates (Let's Encrypt via Caddy)

- Issued and renewed automatically by Caddy (TLS 1.2/1.3 only, HTTP -> HTTPS 308). No cron job.
- Check from anywhere: `echo | openssl s_client -connect staging.<domain>:443 -servername staging.<domain> 2>/dev/null | openssl x509 -noout -issuer -dates`
- **Renewal dry run (AC 7), outside tester hours:** `sudo infra/staging/bin/cert-dry-run.sh`. It stops Caddy (about 1 min), gets a certificate from the **Let's Encrypt staging CA** with an empty data directory (same ACME flow as a renewal), checks the issuer says `(STAGING)`, and starts the normal Caddy again. The result line is in `/var/lib/nav/cert-dry-run.log`; copy it into section 15.
- Certificate expiry is also watched from outside (Uptime Kuma, alert at <= 14 days).
- Repeated restores or dry runs never touch the production CA's rate limits: the ACME account and certificates are in the backup, and dry runs use the staging CA.

## 9. Backups and restore (AC 19, AC 20)

**What is backed up** daily to the ops VM (restic, encrypted client-side): `infra/staging/.env`, the Caddy volumes (ACME account key, certificates, autosaved config), `/etc/ufw`, the sshd drop-in, sudoers drop-in, nav-ops `authorized_keys` (public keys), `/etc/docker/daemon.json`, journald/sysctl/sysstat/unattended-upgrades config, `/etc/systemd/system/nav-*`, `/root/.ssh/config`, `/var/lib/nav` (state). List on the host: `sudo infra/staging/bin/nav-backup.sh paths`.

**`backend/data/` is NOT backed up.** It is rebuilt from OpenStreetMap by the first deploy or `nav-rebuild.sh` (AC 15). The rollback copy, container logs, the git checkout (it is in git), the restic password and SSH private keys (including the private-repository deploy key `~nav-ops/.ssh/nav_deploy`) are not backed up either. Retention: 7 daily + 4 weekly (`restic forget --keep-daily 7 --keep-weekly 4 --prune`), weekly `restic check` of 10 % of the data.

**Set up once.** The first two lines create the backup key; section 10 installs its public half in the backup account on the ops VM. Continue here after section 10.
```sh
sudo ssh-keygen -t ed25519 -N '' -C nav-staging-backup -f /root/.ssh/nav_backup_ed25519    # stays on this host
sudo cat /root/.ssh/nav_backup_ed25519.pub     # PUBLIC line for the ops VM (section 10, step 3)
sudo tee /root/.ssh/config >/dev/null <<'EOF'
Host nav-ops-vm
    HostName <ops-host>
    User nav-backup
    IdentityFile /root/.ssh/nav_backup_ed25519
    IdentitiesOnly yes
    StrictHostKeyChecking yes
EOF
sudo chmod 600 /root/.ssh/config
```
**Pin the ops VM's host key by fingerprint** (not blind `accept-new`). On the ops VM, in a `nav-ops` session (section 10): `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` and note the `SHA256:...` value. Then on the staging host:
```sh
sudo sh -c 'ssh-keyscan -t ed25519 <ops-host> 2>/dev/null > /root/.ssh/nav-ops-vm.hostkey'
sudo ssh-keygen -lf /root/.ssh/nav-ops-vm.hostkey
#   the SHA256 value must equal the one read on the ops VM. Equal: keep it. Different: STOP (wrong host or
#   interception), delete the file and check DNS for <ops-host>.
sudo sh -c 'cat /root/.ssh/nav-ops-vm.hostkey >> /root/.ssh/known_hosts && rm /root/.ssh/nav-ops-vm.hostkey'
sudo sftp -b /dev/null nav-ops-vm && echo sftp-ok      # connects without a host-key question, no shell
```
```sh
sudo sh -c 'umask 077; openssl rand -base64 32 > /root/.config/nav/restic-password'
#   store the SAME password in the company password manager now: without it the backup cannot be read.
# infra/staging/.env: RESTIC_REPOSITORY=sftp:nav-ops-vm:/srv/restic/nav-staging
sudo infra/staging/bin/nav-backup.sh init && sudo infra/staging/bin/nav-backup.sh && sudo infra/staging/bin/nav-backup.sh snapshots
```

**Restore drill (AC 20), timed, target <= 2 h:** start a clock, then on a **new empty VM** of the same plan:
1. Sections 3 (DNS only if the IP changes) and 4: code into `/opt/nav` (A or B), then bootstrap (C) with the operator's key. **Private repository:** the deploy key is not in the backup (it is a private key). Create a **new** one on the new VM exactly as in section 4 B (as `nav-ops`, with the `config` and `known_hosts` lines), add its `.pub` as a deploy key on GitHub, and delete the old host's deploy key there. Check with `sudo runuser -u nav-ops -- git -C /opt/nav fetch --tags --dry-run origin && echo fetch-ok` before step 5.
2. Put the backup key and `/root/.ssh/config` in place (a new key is fine: add its `.pub` on the ops VM with `setup-backup-account.sh`), pin the ops VM's host key by fingerprint exactly as in "Set up once" (known_hosts is not in the backup), and write the restic password from the password manager to `/root/.config/nav/restic-password` (`chmod 600`).
3. `sudo RESTIC_REPOSITORY=sftp:nav-ops-vm:/srv/restic/nav-staging /opt/nav/infra/staging/bin/nav-restore.sh --in-place` (restores `.env`, Caddy data, firewall, units; reloads sshd/UFW/systemd). Without `--in-place` it only restores to `/var/tmp/nav-restore-<time>` for inspection.
4. If the operator key changed, re-run `bootstrap.sh --ops-key-file ...` (the restored `authorized_keys` holds the old public key; keys are added, not replaced).
5. `sudo /opt/nav/infra/staging/bin/deploy.sh <tag>` (first data build), then from outside `BASE_URL=https://staging.<domain> tests/smoke/run.sh` -> exit 0. Stop the clock and record the time in section 15.
Caddy reuses the restored certificate if DNS still points at the same IP; after a DNS change it gets a new one by itself.

## 10. Ops VM and monitoring (AC 17, AC 18)

What runs there: Uptime Kuma (UI on `127.0.0.1:3001`, SSH tunnel only), a **push-only Caddy** on TCP 80/443 for `<ops-host>` that forwards only `/api/push/*` to Uptime Kuma and answers `404` to everything else (no access log: the push tokens are in the path), and the SFTP-only backup account. Prerequisites: the DNS record for `<ops-host>` (section 3) resolves to the ops VM, and that provider's panel firewall (if any) allows TCP 22, 80, 443.

**1. Copy the few needed files** (no git clone of the private repository on the ops VM). From the operator's laptop; the files come from the staging host's checkout, which is at the deployed tag:
```sh
ssh -i ~/.ssh/nav_ops_ed25519 nav-ops@<ip> 'git -C /opt/nav describe --tags'      # the tag you expect
mkdir -p nav-ops-files && cd nav-ops-files
for f in bootstrap/bootstrap.sh monitoring/ops-vm/compose.yaml monitoring/ops-vm/Caddyfile \
         monitoring/ops-vm/.env.example monitoring/ops-vm/setup-backup-account.sh; do
    mkdir -p "$(dirname "$f")"
    scp -q -i ~/.ssh/nav_ops_ed25519 "nav-ops@<ip>:/opt/nav/infra/staging/$f" "$f"
done
cd .. && scp -q -r nav-ops-files root@<ops-ip>:/opt/nav-ops                   # -> /opt/nav-ops/bootstrap/..., /opt/nav-ops/monitoring/ops-vm/...
scp -q ~/.ssh/nav_ops_ed25519.pub root@<ops-ip>:/root/nav-ops.pub               # the operator's PUBLIC key
ssh root@<ops-ip> 'chmod 755 /opt/nav-ops/bootstrap/bootstrap.sh /opt/nav-ops/monitoring/ops-vm/setup-backup-account.sh && head -c 12 /root/nav-ops.pub; echo'
#   prints "ssh-ed25519 " (a public key). Without scp for the key: on the ops VM as root,
#   printf '%s\n' 'ssh-ed25519 AAAA... nav-ops (role)' > /root/nav-ops.pub
```
(Variant with its own read-only deploy key, as in section 4 B, if the operator prefers `git`: clone into `/opt/nav-ops-repo` and use `infra/staging/...` paths from there.) **Later updates** (root SSH is refused after step 2): run the same loop for the new tag, then `scp -q -r nav-ops-files nav-ops@<ops-ip>:/tmp/nav-ops-new && ssh nav-ops@<ops-ip> 'sudo cp -r /tmp/nav-ops-new/. /opt/nav-ops/ && rm -rf /tmp/nav-ops-new && cd /opt/nav-ops/monitoring/ops-vm && sudo docker compose up -d --wait'`.

**2. Bootstrap** (as root on the ops VM, first and only root SSH session, like section 4 C):
```sh
/opt/nav-ops/bootstrap/bootstrap.sh --role ops --ops-key-file /root/nav-ops.pub --extra-allow-users nav-backup
```
`--role ops` gives the same SSH hardening as the staging host and opens UFW **22, 80, 443** (IPv4 and IPv6): 80 for the ACME HTTP-01 challenge and the HTTPS redirect, 443 for the push endpoint. Uptime Kuma's own port stays on `127.0.0.1`. (`--no-web` keeps SSH only, e.g. with the hosted-monitor alternative of deployment-staging.md §9; it closes 80/443 again.) Before closing the root session, do checks 1, 3 and 4 of section 4 C with `nav-ops@<ops-ip>`, and confirm that provider's break-glass path (its VNC/serial console or rescue mode) is offered, without triggering it. Read the host key fingerprint for section 9: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.

**3. Backup account** (the staging host's backup key from section 9, first two lines), from the operator's laptop (add `-i ~/.ssh/nav_ops_ed25519` to both `ssh` commands if that key is not your default):
```sh
ssh nav-ops@<ip> 'sudo cat /root/.ssh/nav_backup_ed25519.pub' | ssh nav-ops@<ops-ip> 'sudo tee /root/nav-staging-backup.pub'   # PUBLIC key only
ssh nav-ops@<ops-ip> 'sudo /opt/nav-ops/monitoring/ops-vm/setup-backup-account.sh /root/nav-staging-backup.pub'
```
The backup account is SFTP-only (no shell, no forwarding) and starts in `/srv/restic` (not chrooted; see section 18). Then finish section 9 "Set up once" on the staging host.

**4. Configure and start Uptime Kuma and the push-only Caddy** (as `nav-ops` on the ops VM):
```sh
cd /opt/nav-ops/monitoring/ops-vm
sudo cp .env.example .env && sudo chmod 600 .env
sudo nano .env               # OPS_HOST=<ops-host> (bare name, no https://), ACME_EMAIL=<role mailbox>
sudo docker compose up -d --wait
sudo docker compose ps       # uptime-kuma and caddy "healthy"
```
Checks **from outside** (the operator's laptop):
```sh
curl -sS -o /dev/null -w '%{http_code}\n' https://<ops-host>/                   # 404 (UI not exposed)
curl -sS -w ' %{http_code}\n' https://<ops-host>/api/push/not-a-token           # {"ok":false,"msg":"Monitor not found or not active."} 404 = Kuma reached
curl -sS -o /dev/null -w '%{http_code}\n' http://<ops-host>/api/push/x          # 308 (redirect to https)
echo | openssl s_client -connect <ops-host>:443 -servername <ops-host> 2>/dev/null | openssl x509 -noout -issuer -dates   # Let's Encrypt
sudo docker compose logs caddy | grep -cE 'api/push|remote_ip|"uri"'              # on the ops VM: 0 (no request data logged)
```

**5. Uptime Kuma UI** from the operator's laptop: `ssh -L 3001:127.0.0.1:3001 nav-ops@<ops-ip>`, then `http://localhost:3001` (the database is SQLite, preset; create the admin account; its password goes into the password manager). Create the notification channel of the operator, set **Settings -> Notifications -> TLS expiry days = 14 and 7**, then the monitors exactly as in [`monitoring/uptime-kuma-monitors.yaml`](monitoring/uptime-kuma-monitors.yaml):

| Monitor | Type | Rule |
|---|---|---|
| nav-staging health | HTTP(s) keyword `"status":"ok"` on `https://staging.<domain>/health` | every 60 s, 1 retry -> alert after the 2nd failure (about 2 min); certificate expiry alert <= 14 days |
| nav-staging disk | Push, 15 min | pushed every 5 min only while disk use < 85 % |
| nav-staging rebuild | Push, 26 h | pushed after each good rebuild + smoke |
| nav-staging backup (recommended) | Push, 26 h | pushed after each good backup |

**6. Push URLs: rewrite the origin.** Opened through the SSH tunnel, the Kuma UI shows each push URL as `http://localhost:3001/api/push/<token>?status=up&msg=OK&ping=`. That origin only exists on the laptop. Replace `http://localhost:3001` with `https://<ops-host>`:
```
shown:  http://localhost:3001/api/push/<token>?status=up&msg=OK&ping=
use:    https://<ops-host>/api/push/<token>?status=up&msg=OK&ping=
```
(Optional: Kuma **Settings -> General -> Primary Base URL** = `https://<ops-host>` makes the UI show the right origin directly; check it anyway.) In `infra/staging/.env` on the staging host set `OPS_HOST=<ops-host>` and the three URLs (`UPTIME_PUSH_URL_DISK`, `UPTIME_PUSH_URL_REBUILD`, `UPTIME_PUSH_URL_BACKUP`). They contain tokens: never paste them elsewhere. The heartbeat helper refuses `localhost`/IP origins, plain `http://` and any host other than `OPS_HOST`, and logs the reason without the URL.

**7. Push test from the staging host** (each monitor must turn **green** in the Kuma UI):
```sh
sudo /opt/nav/infra/staging/bin/nav-push-test.sh      # disk, rebuild, backup: "push test ok" x3, exit 0
sudo /opt/nav/infra/staging/bin/nav-diskcheck.sh      # the real disk heartbeat: "heartbeat sent"
```
A failure line names the monitor and the reason: `problem=...` (URL form), `http_status=404` with `kuma_msg="Monitor not found or not active."` (wrong token, or the monitor is paused), `http_status=000` (DNS, firewall, Caddy down, certificate). Record the result in section 15. A test push to the rebuild/backup monitor counts as their heartbeat for one interval (26 h); the nightly runs take over from there.

**Alert drill (AC 17):** `sudo infra/staging/bin/nav-compose stop gateway` -> Caddy answers `502 {"code":"UpstreamUnavailable"...}` -> DOWN notification within about 2 min; `sudo infra/staging/bin/nav-compose start gateway` -> recovery notice on the next check. Record both times in section 15.

**Host metrics (AC 18):** `sar -u` (CPU), `sar -r` (RAM), `sar -d` (disk I/O), `sar -f /var/log/sysstat/saDD` for older days; 28 days are kept.

## 11. Incidents

| Symptom | First checks | Action |
|---|---|---|
| Health monitor DOWN | `nav-compose ps`; `curl -s localhost:8080/health`; `nav-compose logs --tail=50 gateway caddy` | `nav-compose up -d gateway caddy`. If the host is unreachable: provider console, then `docker ps`, disk (`df -h`) |
| Everything 502 right after someone worked in `backend/` | `ls /opt/nav/backend/.env` exists? `docker inspect navmn-gateway-1 --format '{{json .NetworkSettings.Networks}}'` lacks `navmn_edge`? | A dev `make`/`docker compose` command ran on staging (section 5 warning): `sudo rm -f /opt/nav/backend/.env && sudo /opt/nav/infra/staging/bin/deploy.sh "$(cat /var/lib/nav/deployed-tag)"` |
| `deploy.sh`: "git fetch from origin failed as nav-ops" | `sudo runuser -u nav-ops -- git -C /opt/nav fetch --dry-run origin` | Private repository: the deploy key and `Host github.com` entry must be in `~nav-ops/.ssh/` (section 4 B), and the key must still be listed under the repository's Deploy keys |
| Rebuild heartbeat missing | `sudo make -C /opt/nav/backend status` (`last_run`, `stale`); `journalctl -u nav-rebuild -n 100` | Act on `last_run.exit_code` (section 7.5). The active slot keeps serving in every case; a failed build never switches |
| Data looks wrong after a rebuild | `make status` (`active.osm_data_date`, `previous`); `jq .osm /var/lib/nav/data/slots/<id>/build-info.json` | `sudo make -C /opt/nav/backend rollback` (previous slot within 60 s, no downtime); report the OSM problem to a human mapper (triage lane `osm-data`) |
| Disk monitor DOWN (>= 85 %) | `df -h`; `docker system df`; `du -sh /var/lib/nav/data/* /var/lib/nav/*` | `docker image prune -a` (unused images). Never delete slots by hand: the next `make rebuild` keeps exactly 2 slots and deletes failed/partial ones |
| Certificate alert (<= 14 days) | `nav-compose logs caddy \| grep -i tls`; DNS still points here? port 80 open in the panel? | Fix DNS/firewall; `nav-compose restart caddy` triggers a new attempt |
| Testers report 429 during normal use | Many phones behind one carrier NAT address | Raise `GATEWAY_RATE_*` / `GATEWAY_BURST_*` in `.env`, then `nav-compose up -d gateway`; log the change in the NAV-008 change log |
| Backup heartbeat missing | `journalctl -u nav-backup -n 50`; `sudo sftp -b /dev/null nav-ops-vm && echo sftp-ok` (returns at once; never use plain `ssh nav-ops-vm`, the SFTP-only account makes it hang); `sudo /opt/nav/infra/staging/bin/nav-backup.sh snapshots` | Host key changed: `Host key verification failed` -> re-pin by fingerprint (section 9), never `accept-new`. Disk full on the ops VM (`df -h /srv/restic`). Stale lock: `sudo /opt/nav/infra/staging/bin/nav-backup.sh unlock`. Backup ran but the monitor stays red: `sudo /opt/nav/infra/staging/bin/nav-push-test.sh backup` |
| Any push monitor red although the job ran (`"heartbeat failed"` or `"push URL unusable"` in the job's journal) | `sudo /opt/nav/infra/staging/bin/nav-push-test.sh`; from outside `curl -sS -w ' %{http_code}\n' https://<ops-host>/api/push/not-a-token` (expect Kuma's `404` JSON) | `problem=...`: fix the URL form in `.env` (origin `https://<ops-host>`, section 10 step 6). `http_status=404` + Kuma message: wrong token or monitor paused. `000`: ops VM Caddy down (`cd /opt/nav-ops/monitoring/ops-vm && sudo docker compose ps`), DNS of `<ops-host>`, ops firewall 443, certificate (`docker compose logs caddy \| grep -i tls`) |
| Rebuild interrupted (`systemctl stop`, reboot, timeout, Ctrl-C, power cut) | `make status` (`last_run.result` `interrupted`); `journalctl -u nav-rebuild -n 50` | Nothing served changed. Section 7.6: the next run (timer or `make rebuild`) cleans up by itself |
| Deploy fails creating the `edge` network with IPv6 (compose error naming `enable_ipv6`, `ip6tables` or the `fd4e:` subnet), or Caddy/gateway do not start only when IPv6 is on | `sysctl net.ipv6.conf.all.disable_ipv6`; `docker network inspect navmn_edge`; `nav-compose logs --tail=30 caddy gateway`; `docker info \| grep -i ipv6` | **Fallback:** `EDGE_IPV6=off` in `infra/staging/.env`, then `sudo infra/staging/bin/nav-compose rm -sf caddy gateway && sudo docker network rm navmn_edge; sudo /opt/nav/infra/staging/bin/deploy.sh "$(cat /var/lib/nav/deployed-tag)"` (API down about 1 min). **Effect:** IPv6 clients still connect (Docker publishes 80/443 on IPv6 through its userland proxy), but Caddy then sees all of them from one bridge address, so **all IPv6 clients share one rate-limit bucket** (a busy carrier NAT64 can hit 429 sooner; raise `GATEWAY_BURST_*` if testers report it). IPv4 clients are unaffected. Record the change in the NAV-008 change log and tell QA (their per-client IPv6 rate-limit check then expects one shared bucket). If the host's IPv6 itself is broken, also remove the AAAA record |
| Locked out of SSH | Key, user and IP right? `ssh -v -i ~/.ssh/nav_ops_ed25519 nav-ops@<ip>`. The hPanel "Browser terminal" does not help (it is an SSH client and refuses root/passwords after hardening) | **Hostinger Emergency mode** (hPanel -> VPS -> Settings -> Emergency mode; reboots into a rescue system, active up to 24 h): log in with the access details the panel shows, find the VPS root partition (`lsblk`; the largest `sda` partition, mounted under `/mnt/...`, mount it if needed), then fix `<mnt>/etc/ssh/sshd_config.d/00-nav-hardening.conf` or `<mnt>/home/nav-ops/.ssh/authorized_keys` (mode 600, owner nav-ops = the uid shown by `ls -ln <mnt>/home/nav-ops`), switch Emergency mode off (reboot), log in, `sudo sshd -t`. **Last resort:** hPanel **Settings -> Reset SSH configuration** (Hostinger: replaces `/etc/ssh/sshd_config` with the default; it can undo the hardening, and if our drop-in survives it may change nothing, then use Emergency mode). Log in the way the reset allows (for root with a password: the root password from the password manager, or set a new one in hPanel), then **at once** re-run `bootstrap.sh --ops-key-file ...` and repeat the section 4 C checks. **Other providers:** their VNC/serial console or rescue mode, same file fixes |
| Suspected compromise | | Snapshot in the panel (evidence), rotate the operator key, rebuild a new VM from section 9 restore drill, rotate push URLs and the restic password |

## 12. Upgrades

- **OS:** unattended-upgrades (security) with reboot at 21:30 UTC. Check: `cat /var/log/unattended-upgrades/unattended-upgrades.log`.
- **Docker Engine** (held): take a panel snapshot, `sudo apt-mark unhold docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin && sudo apt-get install --only-upgrade <same list> && sudo apt-mark hold <same list>`, then `deploy.sh <current tag>` (smoke).
- **Caddy / service images:** change the pinned digest in git (`CADDY_IMAGE` default in `compose.staging.yaml` or the `.env`), tag, `deploy.sh <new tag>`, then re-check that Caddy logs contain no request data (`nav-compose logs caddy | grep -E 'remote_ip|uri'` must be empty).
- **Before risky changes** (OS release upgrade, Docker major, data layout): manual panel snapshot.

## 13. Access roles (role names only, AC 21)

| Role | Holds | Held by |
|---|---|---|
| Provider account owner | Hostinger account, billing, panel firewall, snapshots, break-glass (Emergency mode, SSH-configuration reset); the same at the ops VM's provider | named PO-side operator (D7) |
| `nav-ops` | SSH (key only) and sudo on the staging host and the ops VM | named PO-side operator |
| DNS editor | company DNS records for the staging names | named PO-side operator or company IT |
| Alert receiver | Uptime Kuma notifications | named PO-side operator |
| Backup account `nav-backup` (ops VM) | SFTP-only, key-only; receives encrypted restic data | technical account, no person |
| Password manager entries | panel root passwords (not an SSH path after hardening), restic password, Uptime Kuma admin | named PO-side operator |
| Deploy key "nav-staging deploy (read-only)" (private repository only) | read-only access to the repository; private key only in `~nav-ops/.ssh/` on the staging host | technical key; added and removed on GitHub by the repository admin |

## 14. Cost record (AC 22)

| Invoice date | Item | Term (months) | Term total (USD) | Intro monthly (USD) | Renewal monthly (USD) | Effective monthly (USD) |
|---|---|---|---|---|---|---|
| | Hostinger VPS KVM 4, Singapore | | | about 12.99 (PO) | about 28.99 (PO) | |
| | Ops VM (other provider) | | | | | |
| | **Total** (must be <= 150) | | | | | |

Effective monthly = term total / months + monthly add-ons. The ADR-0005 cost table is the official record; copy the values there.

## 15. Log (append a row for every drill, dry run and measurement)

| Date (UTC) | What | Result | Measured value | By (role) |
|---|---|---|---|---|
| | Product check: `systemd-detect-virt`, `docker run --rm hello-world` | `kvm`, "Hello from Docker!" | | nav-ops |
| | Panel firewall filters IPv6? (section 3) | yes / no / no IPv6 | | nav-ops |
| | Break-glass offered (section 4 C step 2, not triggered) | Emergency mode yes/no, SSH reset yes/no; ops VM provider: console/rescue yes/no | | nav-ops |
| | Bootstrap second run | `changes=0`? | | nav-ops |
| | Ops VM push endpoint (section 10 step 4) and push test (step 7) | 404 / Kuma 404 JSON / 308; 3 monitors green | | nav-ops |
| | First deploy (cold data build) | | minutes | nav-ops |
| | Certificate dry run (LE staging CA) | pass/fail | issuer line | nav-ops |
| | Rebuild with empty aux cache (AC 15/16): `nav-stats-sampler.sh -- make -C /opt/nav/backend rebuild REFRESH_AUX=1` (section 7.8) | exit code, result | minutes, peak_build_mb + steady_mb = ac16_ratio, free disk, C9 hosts | nav-ops |
| | NAV-006 install (section 7.9) and first switch with a request loop + `make rollback` | result, failed requests | rebuild minutes, rollback seconds, downtime (expect 0 s) | nav-ops |
| 2026-10-04 | NAV-020 dev container (not staging; 4 vCPU shared with parallel builds; Gate 2 in **evidence** mode, not the engine): first publication of all three files | `published` | 31 s from start to rename: search build 11.1 s (builder 9.4 s, 43,892 rows), gzip 10.4 s, checksum 2.0 s, self-test 1.8 s, Gate 2 5.4 s (19 requests), manifest 0.3 s. Sizes raw/gzip: tiles 117,536,866 / 86,439,299; routing 63,713,280 / 25,911,251; search 23,117,824 / 8,288,113 B. Weekly-only publications 21-29 s. `packs/` after 6 publications: 189,047,358 B | backend |
| | NAV-020 first publication on the VPS (7A.8 step 8, real Gate 2 engine) | result | minutes to the manifest rename (AC 27 <= 15), `packs_disk_bytes` | nav-ops |
| | Alert drill (AC 17) | | alert after .. min, recovery after .. min | nav-ops |
| | Restore drill (AC 20) | | total minutes | nav-ops |

## 16. Privacy of logs (AC 14)

No client IP, query string, request body or coordinate is written by any request-path component:
- **Caddy:** no access log; runtime log = include-list `tls`, `http.acme_client`, `http.auto_https` (per-request error logs are dropped). The ops VM's push-only Caddy uses the same log settings (push tokens are in the request path, so nothing per request is logged there either).
- **Gateway:** JSON lines with method, path without query string, status, bytes, timings. Error log at `crit` (rate-limit rejections are logged at `info`, so they never appear).
- **Valhalla:** query strings replaced by `?<redacted>`. **Photon:** no queries at INFO.
- **Rebuild/backup/check jobs:** public source URLs, sizes, durations, exit codes.
- **Host:** sshd authentication events (operator and internet scanners' source IPs) in journald for <= 14 days; UFW logging off.
Search after a day of use (QA's `tests/staging/nav008/` helpers do this systematically):
```sh
sudo sh -c 'cd /opt/nav && infra/staging/bin/nav-compose logs --no-color caddy gateway valhalla photon' | grep -nE '([0-9]{1,3}\.){3}[0-9]{1,3}|lat=|lon=|json=' | head
```

## 17. Verified before hand-off, and what only the real host can show

Verified by backend in the dev environment (2026-09-30): the Caddy + gateway overlay on a separate compose project (TLS 1.2/1.3 only, HTTP->HTTPS 308, JSON backstop when the gateway is down, no request data in Caddy's log, 429 shape through Caddy); bootstrap in an Ubuntu 24.04 container (second and third runs `changes=0`, password and root SSH refused, drop-in wins over a `50-cloud-init.conf`); deploy by tag, rebuild (tiles and `/health` up the whole time), unchanged-source skip, lock, automatic rollback after a failed smoke, disk heartbeat, all on a sandbox stack; restic backup/check/restore over SFTP between two containers with the ops-VM account script, and an in-place restore on a third container. Added after review: the section 4 B private-repository commands run verbatim in an Ubuntu 24.04 container against a local SSH git server standing in for GitHub (clone as `nav-ops`, bootstrap completes the pre-created account, `deploy.sh` fetches and checks out the next tag as `nav-ops`); the older root-key layout fails with the new hint and the migration line fixes it; `nav-stats-sampler.sh -- nav-rebuild.sh --empty-aux-cache` on a sandbox stack with the Mongolia extract (all auxiliary files and tools downloaded again from a local mirror, the Protomaps jar rebuilt with Maven, full rebuild 329 s, route downtime 13 s, search 50 s, tiles 0 s, smoke 41/0, peak build 4.5 GB + steady 0.7 GB = 0.32 of 16 GB, samples every 2 s); a failed `--empty-aux-cache` run (a 404 auxiliary URL) put the moved files back unchanged and rolled the data back; `backend/Makefile` refuses service targets when `infra/staging/.env` exists.

Added in review round 2 (2026-09-30, deployment-staging.md §17.4): the ops VM stack (`monitoring/ops-vm/compose.yaml` with the push-only Caddy) as a separate compose project with Uptime Kuma on its own network (`infra/ci/staging-ops-push-test.sh`): only `/api/push/*` reaches Kuma (UI, API, badges, metrics, socket.io, assets: 404; http: 308), `nav-diskcheck.sh` and `nav-push-test.sh` push through Caddy and Kuma records UP heartbeats, the tunnel origin, plain http, a foreign host and a wrong token are refused without printing the URL, and Caddy's log holds no path, token or client address; `caddy validate` and adapted-config assertions for the ops Caddyfile in the pinned image; `bootstrap.sh --role ops` in an Ubuntu 24.04 container (UFW 22/80/443, second run `changes=0`, `--no-web` closes 80/443, refused for the staging role); the section 9 fingerprint pinning, `sftp -b /dev/null nav-ops-vm` (returns at once) and the old `ssh nav-ops-vm` (still hanging after 5 s) against `setup-backup-account.sh` in a container; `nav-rebuild.sh` killed with SIGTERM during the Photon import, during the download and during the Valhalla restart, and with services that do not come back (rollback copy restored), on a stub stack: Photon and Valhalla serving again with their markers restored (before the fix Photon stayed down and both markers were gone).

NAV-006 verified by backend in the dev container (2026-10-04, separate Compose project `navmn-nav006` on `127.0.0.1:18080/18089`, test root `/var/tmp/nav006-test`; the shared dev stack on :8080 was never touched): first slot build 6 min; `FORCE=1` builds 4 min (tiles 3 min with a warm cache); 3 switches under a request loop at 24 requests/s (half keep-alive, half new connections, no retries): 0 failed requests in 14,880 + 21,600 + 11,520 requests, p95 window ratio 1.38 / 1.19 / 0.79, the tiles ETag changing at the pointer rename; `make rollback` 5.9 s to the switch, 0 failures in 4,080 requests; a second rollback refused in 75 ms (code 30); forced verification failure (`REBUILD_TEST_FAULT=verify`): no switch, slot marked failed, 0 failures in 7,920 requests, alert once; automatic rollback after a failed post-switch check (code 24); `pmtiles` 4.5.0 kept one header across two switches and decoded 20,625 z14 tiles with 0 errors; lock: second `make rebuild` / `make rollback` refused in 74 / 122 ms naming the lock, `deploy.sh`'s `flock -n` refused; SIGTERM during a build: cleanup in 0.6 s (2.2 s through `nav-rebuild.sh`), partial slot and builders gone; `kill -9`: the next run reconciled without manual steps; Geofabrik blocked here, so the source list fell back to the geo2day mirror after one retry; peak memory 3.6 GB build + 0.7 GB serving (ratio 0.27); checksum listings of the active slot identical before and after builds. Not shown here: the systemd timer, Caddy in front of the switch, Geofabrik and its `.md5`, MapLibre Native (Android/iOS) on an ETag change, real staging timings.

Only the real VPS can show: systemd timers firing, UFW active with IPv6 rules, swap, unattended reboot, Let's Encrypt issuance and the dry run (and the ops VM's certificate), Geofabrik reachability and its `.md5`, real rebuild times and memory, IPv6 client addresses reaching the gateway, whether the panel firewall filters IPv6, that Emergency mode and the SSH reset are offered, heartbeats from the staging host reaching the ops VM across providers, and the external port scans (staging host and ops VM). Record them in section 15.

## 18. Accepted risks (staging) and production follow-ups (NAV-009)

| Risk | Why accepted for staging | Production (NAV-009) |
|---|---|---|
| The SFTP backup account `nav-backup` is **not chrooted**. `restrict,command="sftp-server -d /srv/restic"` blocks shells and forwarding, but `-d` only sets the start directory: with the staging host's backup key one can read world-readable files elsewhere on the ops VM | Staging holds configuration only, the key never leaves the staging host (root-only), and the ops VM holds no personal data | Chroot it: `/srv/restic` owned by `root:root` 755, the repository `/srv/restic/nav-staging` owned by `nav-backup`, and in `/etc/ssh/sshd_config.d/10-nav-backup.conf` a block `Match User nav-backup` / `ChrootDirectory /srv/restic` / `ForceCommand internal-sftp -d /nav-staging` / `AllowTcpForwarding no` / `X11Forwarding no`; `RESTIC_REPOSITORY` becomes `sftp:nav-ops-vm:/nav-staging` |
| The restic repository is **not append-only**. The staging host runs `forget --prune` itself, so whoever controls the staging host (root) can also delete or corrupt all snapshots | Configuration-only backups; the provider's weekly backups are a second copy; a compromised staging host is rebuilt from git plus a fresh configuration anyway (section 11) | Append-only target (`rest-server --append-only`, or object storage with object lock / versioning), with `forget --prune` run from the backup side under a separate key |
| `deploy.sh <tag>` fetches the tag and then runs **that tag's own** `deploy.sh` as root (re-exec after checkout), without verifying a tag signature. Whoever can push a tag to the repository can run code as root on the host at the next deploy | One operator deploys by hand, tags are created by the team, the deploy key is read-only, and staging holds no personal data | Signed tags (`git verify-tag` against an allowed-signers file kept on the host, outside the checkout) before the re-exec, protected tags on GitHub, or deploy from a CI-built, signed artefact |
| `nav-ops` has passwordless sudo and is in the `docker` group (deployment-staging.md §17.1) | Key-only account with a passphrase-protected key | Separate deploy and admin roles, sudo with re-authentication (e.g. hardware key) |
| Push tokens travel in the URL path | TLS only; neither Caddy logs them; tokens only in the mode-600 `infra/staging/.env` | Same; rotate on any suspicion (section 11 "Suspected compromise") |
