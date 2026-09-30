# NAV-008 staging runbook (owner: backend-engineer; operated by the named PO-side operator)

- **Story:** [NAV-008](../../docs/requirements/stories/NAV-008-backend-hosting-staging.md). **Design:** [deployment-staging.md](../../docs/architecture/deployment-staging.md). **Decision:** [ADR-0005](../../docs/architecture/adr/0005-backend-hosting-staging.md) (Hostinger VPS KVM 4, Singapore; D25, D26).
- **Who runs this:** the named PO-side operator (D7), on the servers, by hand. Nobody on the development side has server access, and nobody ever asks for or receives a password or a private key. Only **public** keys are handed over.
- **Placeholders:** `staging.<domain>` = the API host name (STAGING_HOST), `<ops-host>` = the ops VM, `<tag>` = a git tag. Real names and IP addresses go **only** into `infra/staging/.env` on the server and into the operator's own notes, never into git (`infra/ci/staging-static-checks.sh` fails on any public IPv4 address under `infra/`).
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
3. At creation, add the operator's **SSH public key** (section 2) and let the panel set a root password. The root password stays in the company password manager; it is needed only for the browser console (break-glass).
4. Keep Hostinger's included weekly backups on. They are on-provider copies and do **not** replace the off-host backup (section 9).
5. Record the invoice in the cost table (section 14).

**Check that you really have a VPS** before going on (from the operator's laptop):
```sh
ssh root@<ip-from-the-panel> 'cat /etc/os-release | grep PRETTY; nproc; free -g | head -2; df -h / | tail -1'
# expected: Ubuntu 24.04 LTS, 4, ~15 GB total, ~190-200 GB disk
```
If `ssh root@...` does not give a shell, or the address serves a Hostinger/LiteSpeed web page, it is web hosting, not a VPS. The address the PO first provided looked like that from outside; confirm the product in the panel.

**Ops VM** (monitoring + backup target, section 10): a small VPS (1 vCPU, 1-2 GB RAM, 20+ GB disk, Ubuntu 24.04) at a **different provider** than the staging host.

## 2. SSH keys (handover without secrets)

On the operator's own laptop (once):
```sh
ssh-keygen -t ed25519 -C "nav-ops (role)" -f ~/.ssh/nav_ops_ed25519    # set a passphrase; the private key never leaves the laptop
cat ~/.ssh/nav_ops_ed25519.pub                                          # this PUBLIC line goes into the panel / to the server
```
Only the `.pub` line is ever copied anywhere. `bootstrap.sh` refuses a file that contains a private key and never prints keys (only fingerprints).

## 3. Panel firewall and DNS

- **Panel firewall** (Hostinger hPanel, VPS -> Firewall): allow inbound **TCP 22, 80, 443** (IPv4 and IPv6); drop everything else. This layer also covers anything Docker might publish by mistake (Docker's published ports bypass UFW).
- **DNS** (company zone, DNS editor role): `staging.<domain>` **A** -> the VPS IPv4, and **AAAA** -> the VPS IPv6 if one is assigned. **TTL 300** during setup. Check: `dig +short staging.<domain> A` from outside. Caddy can only get a certificate after this resolves to the VPS.

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
2. Check the provider's browser console (hPanel -> VPS -> Browser terminal / VNC) still logs in as root with the panel password (break-glass, AC 20).
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

**Set up once** (after section 10 created the backup account on the ops VM):
```sh
sudo ssh-keygen -t ed25519 -N '' -C nav-staging-backup -f /root/.ssh/nav_backup_ed25519    # stays on this host
sudo cat /root/.ssh/nav_backup_ed25519.pub     # copy this PUBLIC line to the ops VM (section 10)
sudo tee /root/.ssh/config >/dev/null <<'EOF'
Host nav-ops-vm
    HostName <ops-host>
    User nav-backup
    IdentityFile /root/.ssh/nav_backup_ed25519
    IdentitiesOnly yes
    StrictHostKeyChecking accept-new
EOF
sudo chmod 600 /root/.ssh/config
sudo sh -c 'umask 077; openssl rand -base64 32 > /root/.config/nav/restic-password'
#   store the SAME password in the company password manager now: without it the backup cannot be read.
# infra/staging/.env: RESTIC_REPOSITORY=sftp:nav-ops-vm:/srv/restic/nav-staging
sudo infra/staging/bin/nav-backup.sh init && sudo infra/staging/bin/nav-backup.sh && sudo infra/staging/bin/nav-backup.sh snapshots
```

**Restore drill (AC 20), timed, target <= 2 h:** start a clock, then on a **new empty VM** of the same plan:
1. Sections 3 (DNS only if the IP changes) and 4: code into `/opt/nav` (A or B), then bootstrap (C) with the operator's key. **Private repository:** the deploy key is not in the backup (it is a private key). Create a **new** one on the new VM exactly as in section 4 B (as `nav-ops`, with the `config` and `known_hosts` lines), add its `.pub` as a deploy key on GitHub, and delete the old host's deploy key there. Check with `sudo runuser -u nav-ops -- git -C /opt/nav fetch --tags --dry-run origin && echo fetch-ok` before step 5.
2. Put the backup key and `/root/.ssh/config` in place (a new key is fine: add its `.pub` on the ops VM), and write the restic password from the password manager to `/root/.config/nav/restic-password` (`chmod 600`).
3. `sudo RESTIC_REPOSITORY=sftp:nav-ops-vm:/srv/restic/nav-staging /opt/nav/infra/staging/bin/nav-restore.sh --in-place` (restores `.env`, Caddy data, firewall, units; reloads sshd/UFW/systemd). Without `--in-place` it only restores to `/var/tmp/nav-restore-<time>` for inspection.
4. If the operator key changed, re-run `bootstrap.sh --ops-key-file ...` (the restored `authorized_keys` holds the old public key; keys are added, not replaced).
5. `sudo /opt/nav/infra/staging/bin/deploy.sh <tag>` (first data build), then from outside `BASE_URL=https://staging.<domain> tests/smoke/run.sh` -> exit 0. Stop the clock and record the time in section 15.
Caddy reuses the restored certificate if DNS still points at the same IP; after a DNS change it gets a new one by itself.

## 10. Ops VM and monitoring (AC 17, AC 18)

On the ops VM (different provider), as root:
```sh
apt-get update && apt-get install -y git && git clone <repo-url> /opt/nav && git -C /opt/nav checkout --quiet <tag>
/opt/nav/infra/staging/bootstrap/bootstrap.sh --role ops --ops-key-file /root/nav-ops.pub --extra-allow-users nav-backup
/opt/nav/infra/staging/monitoring/ops-vm/setup-backup-account.sh /root/nav-staging-backup.pub   # the staging host's backup PUBLIC key
docker compose -f /opt/nav/infra/staging/monitoring/ops-vm/compose.yaml up -d                 # Uptime Kuma on 127.0.0.1:3001
```
`--role ops` opens only SSH in UFW. The backup account is SFTP-only (no shell, no forwarding), confined to `/srv/restic`.

Uptime Kuma UI from the operator's laptop: `ssh -L 3001:127.0.0.1:3001 nav-ops@<ops-host>`, then `http://localhost:3001` (create the admin account; its password goes into the password manager). Create the notification channel of the operator, set **Settings -> Notifications -> TLS expiry days = 14 and 7**, then the monitors exactly as in [`monitoring/uptime-kuma-monitors.yaml`](monitoring/uptime-kuma-monitors.yaml):

| Monitor | Type | Rule |
|---|---|---|
| nav-staging health | HTTP(s) keyword `"status":"ok"` on `https://staging.<domain>/health` | every 60 s, 1 retry -> alert after the 2nd failure (about 2 min); certificate expiry alert <= 14 days |
| nav-staging disk | Push, 15 min | pushed every 5 min only while disk use < 85 % |
| nav-staging rebuild | Push, 26 h | pushed after each good rebuild + smoke |
| nav-staging backup (recommended) | Push, 26 h | pushed after each good backup |

Copy each push URL into `infra/staging/.env` on the staging host (`UPTIME_PUSH_URL_DISK`, `UPTIME_PUSH_URL_REBUILD`, `UPTIME_PUSH_URL_BACKUP`); they contain tokens, never paste them elsewhere. Test: `sudo infra/staging/bin/nav-diskcheck.sh` -> the disk monitor turns green.

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
| Backup heartbeat missing | `journalctl -u nav-backup -n 50` | ops VM reachable? `sudo ssh nav-ops-vm` must connect and give no shell (SFTP only); disk on the ops VM; stale lock: `restic unlock` |
| Locked out of SSH | | Provider browser console as root (panel password), fix `/etc/ssh/sshd_config.d/00-nav-hardening.conf` or `authorized_keys`, `sshd -t && systemctl reload ssh` |
| Suspected compromise | | Snapshot in the panel (evidence), rotate the operator key, rebuild a new VM from section 9 restore drill, rotate push URLs and the restic password |

## 12. Upgrades

- **OS:** unattended-upgrades (security) with reboot at 21:30 UTC. Check: `cat /var/log/unattended-upgrades/unattended-upgrades.log`.
- **Docker Engine** (held): take a panel snapshot, `sudo apt-mark unhold docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin && sudo apt-get install --only-upgrade <same list> && sudo apt-mark hold <same list>`, then `deploy.sh <current tag>` (smoke).
- **Caddy / service images:** change the pinned digest in git (`CADDY_IMAGE` default in `compose.staging.yaml` or the `.env`), tag, `deploy.sh <new tag>`, then re-check that Caddy logs contain no request data (`nav-compose logs caddy | grep -E 'remote_ip|uri'` must be empty).
- **Before risky changes** (OS release upgrade, Docker major, data layout): manual panel snapshot.

## 13. Access roles (role names only, AC 21)

| Role | Holds | Held by |
|---|---|---|
| Provider account owner | Hostinger account, billing, panel firewall, snapshots, browser console | named PO-side operator (D7) |
| `nav-ops` | SSH (key only) and sudo on the staging host and the ops VM | named PO-side operator |
| DNS editor | company DNS records for the staging names | named PO-side operator or company IT |
| Alert receiver | Uptime Kuma notifications | named PO-side operator |
| Backup account `nav-backup` (ops VM) | SFTP-only, key-only; receives encrypted restic data | technical account, no person |
| Password manager entries | root console password, restic password, Uptime Kuma admin | named PO-side operator |
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
| | Bootstrap second run | `changes=0`? | | nav-ops |
| | First deploy (cold data build) | | minutes | nav-ops |
| | Certificate dry run (LE staging CA) | pass/fail | issuer line | nav-ops |
| | Rebuild with empty aux cache (AC 15/16): `nav-stats-sampler.sh -- nav-rebuild.sh --empty-aux-cache` | smoke exit | minutes, downtime per service, peak_build_mb + steady_mb = ac16_ratio, free disk, C9 hosts | nav-ops |
| | Alert drill (AC 17) | | alert after .. min, recovery after .. min | nav-ops |
| | Restore drill (AC 20) | | total minutes | nav-ops |

## 16. Privacy of logs (AC 14)

No client IP, query string, request body or coordinate is written by any request-path component:
- **Caddy:** no access log; runtime log = include-list `tls`, `http.acme_client`, `http.auto_https` (per-request error logs are dropped).
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

Only the real VPS can show: systemd timers firing, UFW active with IPv6 rules, swap, unattended reboot, Let's Encrypt issuance and the dry run, Geofabrik reachability and its `.md5`, real rebuild times and memory, IPv6 client addresses reaching the gateway, and the external port scan. Record them in section 15.
