# NAV-008 staging runbook (owner: backend-engineer; operated by the named PO-side operator)

- **Story:** [NAV-008](../../docs/requirements/stories/NAV-008-backend-hosting-staging.md). **Design:** [deployment-staging.md](../../docs/architecture/deployment-staging.md). **Decision:** [ADR-0005](../../docs/architecture/adr/0005-backend-hosting-staging.md) (Hostinger VPS KVM 4, Singapore; D25, D26).
- **Who runs this:** the named PO-side operator (D7), on the servers, by hand. Nobody on the development side has server access, and nobody ever asks for or receives a password or a private key. Only **public** keys are handed over.
- **Placeholders:** `staging.<domain>` = the API host name (STAGING_HOST), `<ops-host>` = the ops VM's public DNS name (OPS_HOST, e.g. `ops-staging.<domain>`), `<ip>` / `<ops-ip>` = the servers' addresses from the provider panels, `<tag>` = a git tag. Real names and IP addresses go **only** into `infra/staging/.env` on the server and into the operator's own notes, never into git (`infra/ci/staging-static-checks.sh` fails on any public IPv4 address under `infra/`).
- **Times:** the servers run in UTC. Asia/Ulaanbaatar = UTC+8 (Singapore too).

| When | UTC | Asia/Ulaanbaatar | What |
|---|---|---|---|
| Daily | 19:30 (+ up to 15 min) | 03:30 | `nav-rebuild.timer`: data rebuild, gateway stays up |
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

> **Warning: no `make` service targets and no plain `docker compose` in `/opt/nav/backend` on staging.** `backend/compose.yaml` uses the same project name (`navmn`) as the staging scripts. `make up`, `down`, `restart`, `rebuild-data`, `clean-data`, `cors-check` or a plain `docker compose up/down` there would create `backend/.env` from the **dev** example (CORS `*`, dev OSM mirror), stop the staging gateway, delete the live data (`rebuild-data`) and start the gateway **without rate limits and off the `edge` network**: Caddy then answers `502` to everyone. `backend/Makefile` refuses those targets while `infra/staging/.env` exists. On staging use `infra/staging/bin/nav-compose <compose args>` (the same compose command with the staging files and `.env`), `deploy.sh` and `nav-rebuild.sh`. Read-only targets (`make smoke`, `contract`, `ps`, `logs`, `stats`, `build-info`) are harmless. If it happened anyway: `sudo rm -f /opt/nav/backend/.env && sudo /opt/nav/infra/staging/bin/deploy.sh "$(cat /var/lib/nav/deployed-tag)"` (rebuilds the data if it was deleted, about 10 min).

## 6. Deploy and roll back

```sh
sudo /opt/nav/infra/staging/bin/deploy.sh <tag>        # fetch, checkout tag, units, pull pinned images, up --wait, rate-limit check, smoke
sudo /opt/nav/infra/staging/bin/deploy.sh --status     # deployed / previous tag, last deployments
sudo /opt/nav/infra/staging/bin/deploy.sh --rollback   # deploy the previous tag again
```
- The **first** deploy on an empty host builds all data: about 8-10 min of build on 4 vCPU plus about 2.5 GB of downloads (NAV-001 measurement on the dev machine; record the real value in the log, section 15).
- Only Caddy publishes ports (80, 443 TCP). The gateway listens on `127.0.0.1:8080` (`curl -s localhost:8080/health` on the host works). Valhalla and Photon publish nothing.
- `sudo infra/staging/bin/nav-compose ps` / `logs --tail=50 caddy gateway` / `restart gateway` = docker compose with the staging files.
- After the first deploy: `curl -sS https://staging.<domain>/health` -> `{"status":"ok"}`, and `curl -sI http://staging.<domain>/` -> `308` to `https://`.

## 7. Daily data rebuild

`nav-rebuild.timer` runs `bin/nav-rebuild.sh` at 19:30 UTC. Manual run: `sudo infra/staging/bin/nav-rebuild.sh [--force]`.

1. Preflight: Geofabrik URL answers 200, >= 50 GB free, gateway healthy. Otherwise nothing changes and no heartbeat is sent.
2. If Geofabrik's `.md5` equals the last good build, the build is skipped (smoke + age check still run).
3. Rollback copy of the current data to `/var/lib/nav/rollback` (about 250 MB; skipped if disk is short).
4. Download, md5 check, then tiles + routing graph **while everything keeps serving**. Photon stops only when the search dump changed (or with `--force`) and starts again as soon as its new index is imported.
5. Valhalla restarts on the new graph. **Tiles and `/health` never go down**; routing is unavailable for the Valhalla restart (seconds), search for the Photon re-import (about 1 min) when the dump changed or with `--force`. Clients get the gateway's JSON 502 with CORS headers meanwhile.
6. Smoke suite, then `osm.replication_timestamp` <= 48 h, then the rebuild heartbeat.
7. A failed build or smoke restores the rollback copy automatically and exits non-zero (no heartbeat -> alert).
8. An **interrupted** run (`systemctl stop nav-rebuild`, the 2 h start timeout, a reboot's SIGTERM, Ctrl-C) stops the background builds, puts the auxiliary cache back and starts Photon and Valhalla again on the old data (their readiness markers are only set aside until a new index/graph is swapped in); if they do not come up it restores the rollback copy. Only SIGKILL or power loss skips this (section 11 "Rebuild interrupted").

Where to look: `journalctl -u nav-rebuild --since today`, `/var/lib/nav/last-rebuild.json` (build time and per-service downtime, **use it for AC 15**), `/var/lib/nav/last-smoke.txt`.
Manual rollback to the previous data: `sudo infra/staging/bin/nav-rollback-data.sh`.

`--force` = full rebuild even when Geofabrik's file is unchanged: tiles, routing graph **and** search index (the builders' completeness markers are invalidated, every tool is fetched). **On staging, AC 15's "`make rebuild-data`" is `nav-rebuild.sh --force`** (deployment-staging.md §17.2); the dev `make` targets must not run here (warning in section 5).

**AC 15/16 measurement** (once, outside tester hours; search is down about 1 min for the re-import, routing for the Valhalla restart). One command, copy-paste as is:
```sh
sudo bash -o pipefail -c '/opt/nav/infra/staging/bin/nav-stats-sampler.sh -- /opt/nav/infra/staging/bin/nav-rebuild.sh --empty-aux-cache 2>&1 | tee /var/lib/nav/ac15-$(date -u +%Y%m%dT%H%MZ).log'; echo "exit code: $?"
```
- `--empty-aux-cache` (implies `--force`) first **empties the auxiliary cache**: it moves the Planetiler auxiliary files (everything in `backend/data/sources/` except the OSM and Photon inputs, about 2.3 GB) and `backend/data/tools/` to `/var/lib/nav/aux-cache.aside`, so every C9 download happens from this host and nothing is pre-seeded. If the run fails, the files are put back; after a good run the old copies are deleted. The live tiles, graph and index keep serving until each is replaced.
- `nav-stats-sampler.sh` is the `make stats` equivalent: every **2 s** (AC 16: <= 3 s) it records the host RAM in use and each container's memory as `docker stats` shows it, then takes 5 steady-state samples after the rebuild and writes a summary. It exits with the rebuild's exit code. Only service names and sizes are recorded.
- Alternative: measure the very **first deploy** of an empty host (everything is downloaded anyway). Run `sudo /opt/nav/infra/staging/bin/nav-stats-sampler.sh` in a second shell before `deploy.sh`, and press Ctrl-C when the deploy has finished; the time is the `seconds=` value in `/var/lib/nav/deployments.log`.

Record in section 15 and in the QA checklist (O-4):
```sh
cat /var/lib/nav/last-rebuild.json      # total_seconds (AC 15: <= 1800), route/search/tiles downtime, "empty_aux_cache": true
cat "$(ls -t /var/lib/nav/stats/*.summary.json | head -1)"
#   peak_build_mb, steady_mb, ac16_ratio (<= 0.70), free_disk_gb (>= 50), max_gap_s (<= 3)
jq '.osm | {source, replication_timestamp}' /opt/nav/backend/data/build-info.json     # Geofabrik, <= 48 h old
tail -n 2 /var/lib/nav/last-smoke.txt   # smoke exit 0
grep -h '"msg":"downloaded"' "$(ls -t /var/lib/nav/ac15-*.log | head -1)" | jq -r .url | awk -F/ '{print $3}' | sort -u   # C9 download hosts
grep -c 'Protomaps jar built' "$(ls -t /var/lib/nav/ac15-*.log | head -1)"    # 1 = Maven Central and repo.osgeo.org reached (Maven build)
#   ghcr.io, mcr.microsoft.com and Docker Hub (images) are shown by the first deploy's `docker compose pull`.
df -h /
```
If a run was killed hard (power loss) and `/var/lib/nav/aux-cache.aside` is left over, the next rebuild warns about it; the fresh downloads are complete, so delete it: `sudo rm -rf /var/lib/nav/aux-cache.aside`.

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
| Rebuild heartbeat missing | `journalctl -u nav-rebuild -n 100`; `/var/lib/nav/last-smoke.txt` | Preflight failure: the old data is still serving; fix the cause (Geofabrik down, disk) and run `nav-rebuild.sh`. Build/smoke failure: it already rolled back; retry with `--force` after checking (full rebuild; search is down about 1 min for the re-import) |
| Data looks wrong after a rebuild | `jq .osm /opt/nav/backend/data/build-info.json` | `sudo infra/staging/bin/nav-rollback-data.sh` |
| Disk monitor DOWN (>= 85 %) | `df -h`; `docker system df`; `du -sh /opt/nav/backend/data/* /var/lib/nav/*` | `docker image prune -a` (unused images), remove `/var/lib/nav/rollback` if needed. Do not delete files in `backend/data` by hand while services run; `nav-rebuild.sh --force` replaces them safely |
| Certificate alert (<= 14 days) | `nav-compose logs caddy \| grep -i tls`; DNS still points here? port 80 open in the panel? | Fix DNS/firewall; `nav-compose restart caddy` triggers a new attempt |
| Testers report 429 during normal use | Many phones behind one carrier NAT address | Raise `GATEWAY_RATE_*` / `GATEWAY_BURST_*` in `.env`, then `nav-compose up -d gateway`; log the change in the NAV-008 change log |
| Backup heartbeat missing | `journalctl -u nav-backup -n 50`; `sudo sftp -b /dev/null nav-ops-vm && echo sftp-ok` (returns at once; never use plain `ssh nav-ops-vm`, the SFTP-only account makes it hang); `sudo /opt/nav/infra/staging/bin/nav-backup.sh snapshots` | Host key changed: `Host key verification failed` -> re-pin by fingerprint (section 9), never `accept-new`. Disk full on the ops VM (`df -h /srv/restic`). Stale lock: `sudo /opt/nav/infra/staging/bin/nav-backup.sh unlock`. Backup ran but the monitor stays red: `sudo /opt/nav/infra/staging/bin/nav-push-test.sh backup` |
| Any push monitor red although the job ran (`"heartbeat failed"` or `"push URL unusable"` in the job's journal) | `sudo /opt/nav/infra/staging/bin/nav-push-test.sh`; from outside `curl -sS -w ' %{http_code}\n' https://<ops-host>/api/push/not-a-token` (expect Kuma's `404` JSON) | `problem=...`: fix the URL form in `.env` (origin `https://<ops-host>`, section 10 step 6). `http_status=404` + Kuma message: wrong token or monitor paused. `000`: ops VM Caddy down (`cd /opt/nav-ops/monitoring/ops-vm && sudo docker compose ps`), DNS of `<ops-host>`, ops firewall 443, certificate (`docker compose logs caddy \| grep -i tls`) |
| Rebuild interrupted (`systemctl stop`, reboot, timeout, Ctrl-C) | `journalctl -u nav-rebuild -n 50`: `"bringing them back"` then `"serving again"` or `"restoring the rollback copy"`; `nav-compose ps photon valhalla` | The EXIT trap restarts Photon/Valhalla on the old data (or restores the rollback copy). Only a SIGKILL/power loss skips it: `sudo infra/staging/bin/nav-rollback-data.sh`, or if there is no rollback copy `sudo infra/staging/bin/nav-rebuild.sh --force` |
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
| | Rebuild with empty aux cache (AC 15/16): `nav-stats-sampler.sh -- nav-rebuild.sh --empty-aux-cache` | smoke exit | minutes, downtime per service, peak_build_mb + steady_mb = ac16_ratio, free disk, C9 hosts | nav-ops |
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

Only the real VPS can show: systemd timers firing, UFW active with IPv6 rules, swap, unattended reboot, Let's Encrypt issuance and the dry run (and the ops VM's certificate), Geofabrik reachability and its `.md5`, real rebuild times and memory, IPv6 client addresses reaching the gateway, whether the panel firewall filters IPv6, that Emergency mode and the SSH reset are offered, heartbeats from the staging host reaching the ops VM across providers, and the external port scans (staging host and ops VM). Record them in section 15.

## 18. Accepted risks (staging) and production follow-ups (NAV-009)

| Risk | Why accepted for staging | Production (NAV-009) |
|---|---|---|
| The SFTP backup account `nav-backup` is **not chrooted**. `restrict,command="sftp-server -d /srv/restic"` blocks shells and forwarding, but `-d` only sets the start directory: with the staging host's backup key one can read world-readable files elsewhere on the ops VM | Staging holds configuration only, the key never leaves the staging host (root-only), and the ops VM holds no personal data | Chroot it: `/srv/restic` owned by `root:root` 755, the repository `/srv/restic/nav-staging` owned by `nav-backup`, and in `/etc/ssh/sshd_config.d/10-nav-backup.conf` a block `Match User nav-backup` / `ChrootDirectory /srv/restic` / `ForceCommand internal-sftp -d /nav-staging` / `AllowTcpForwarding no` / `X11Forwarding no`; `RESTIC_REPOSITORY` becomes `sftp:nav-ops-vm:/nav-staging` |
| The restic repository is **not append-only**. The staging host runs `forget --prune` itself, so whoever controls the staging host (root) can also delete or corrupt all snapshots | Configuration-only backups; the provider's weekly backups are a second copy; a compromised staging host is rebuilt from git plus a fresh configuration anyway (section 11) | Append-only target (`rest-server --append-only`, or object storage with object lock / versioning), with `forget --prune` run from the backup side under a separate key |
| `deploy.sh <tag>` fetches the tag and then runs **that tag's own** `deploy.sh` as root (re-exec after checkout), without verifying a tag signature. Whoever can push a tag to the repository can run code as root on the host at the next deploy | One operator deploys by hand, tags are created by the team, the deploy key is read-only, and staging holds no personal data | Signed tags (`git verify-tag` against an allowed-signers file kept on the host, outside the checkout) before the re-exec, protected tags on GitHub, or deploy from a CI-built, signed artefact |
| `nav-ops` has passwordless sudo and is in the `docker` group (deployment-staging.md §17.1) | Key-only account with a passphrase-protected key | Separate deploy and admin roles, sudo with re-authentication (e.g. hardware key) |
| Push tokens travel in the URL path | TLS only; neither Caddy logs them; tokens only in the mode-600 `infra/staging/.env` | Same; rotate on any suspicion (section 11 "Suspected compromise") |
