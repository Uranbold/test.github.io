# NAV-008 operator checklist: host-side verification (AC 7, 12, 14–24)

Owner: qa-engineer. Test plan: [`docs/qa/test-plans/NAV-008.md`](../test-plans/NAV-008.md). Design: [`deployment-staging.md`](../../architecture/deployment-staging.md).
Run by the **named PO-side operator** (role `nav-ops`, D7) on the staging host, with QA observing by chat or call. The runbook from backend-engineer (`infra/staging/`) has the exact commands. This list says what to record.

**Rules.** Paste **outputs**, never passwords, keys, tokens, push URLs or the host IP. Names of people stay out: use role names (provider account owner, `nav-ops`, DNS editor, alert receiver). Commands marked *(QA)* run on QA's machine outside the host.

## O-0 Host sanity (before anything else)

Why: the address first given for staging looks like Hostinger **shared web hosting** (LiteSpeed/hPanel, Mumbai), which cannot run Docker. This step makes sure the machine is the KVM VPS of D25.

| # | Check | Command / where | Expected | Result |
|---|---|---|---|---|
| O-0.1 | Plan and location | Hostinger panel, VPS page | Plan **KVM 4**, location **Singapore**, OS Ubuntu 24.04 LTS | |
| O-0.2 | Virtualisation | `systemd-detect-virt` | `kvm` | |
| O-0.3 | Size | `nproc; free -g \| awk '/Mem/{print $2}'; df -h /` | 4 CPUs, about 16 GB, about 200 GB | |
| O-0.4 | OS | `lsb_release -ds; uname -r` | Ubuntu 24.04.x LTS | |
| O-0.5 | Docker | `docker version --format '{{.Server.Version}}'; docker compose version` | Engine and Compose plugin from Docker's apt repository (not the snap) | |
| O-0.6 | Deployed tag | `git -C /opt/nav describe --tags` (path per runbook) | The pinned tag given in the deploy record | |

If O-0.1, O-0.2 or O-0.5 fails, stop and report to QA and the orchestrator. The host cannot pass NAV-008.

## O-1 SSH and security updates (AC 12)

| # | Command | Expected | Result |
|---|---|---|---|
| O-1.1 | `sudo sshd -T \| grep -Ei '^(passwordauthentication\|kbdinteractiveauthentication\|permitrootlogin\|pubkeyauthentication\|allowusers)'` | `no`, `no`, `no`, `yes`, `nav-ops` (plus the backup user if one exists) | |
| O-1.2 | `ls /etc/ssh/sshd_config.d/` | `00-nav-hardening.conf` sorts first | |
| O-1.3 | `systemctl is-enabled unattended-upgrades; apt-config dump \| grep -E 'Unattended-Upgrade::(Allowed-Origins\|Origins-Pattern\|Automatic-Reboot)'` | `enabled`; security origin listed; automatic reboot per design §3 | |
| O-1.4 | `sudo ufw status verbose` | default deny incoming; 22 (limit), 80, 443 allowed for IPv4 and IPv6; logging off | |
| O-1.5 | Break-glass: open the provider's browser console once | Console login works (design §3) | |
| O-1.6 *(QA)* | `tests/staging/nav008/portscan.sh` | Only {22, 80, 443} open per family; `Permission denied (publickey)` for `nav-ops` and `root` | |

## O-2 Certificate renewal dry run (AC 7)

Per design §5: stop Caddy, start a temporary Caddy with the same Caddyfile, a **fresh empty data volume** and the **Let's Encrypt staging CA**, confirm it obtains a staging certificate for `staging.<domain>`, remove it and start the normal Caddy. About 1 minute of downtime, outside tester hours.

- [ ] Start and end time (UTC): ______
- [ ] Log excerpt showing `certificate obtained successfully` from the staging CA (paste; no email address)
- [ ] *(QA)* `tests/staging/nav008/tls-check.sh` afterwards shows the normal (production CA) certificate again

## O-3 Log privacy after one day of use (AC 14)

1. *(QA, day 0)* `python3 tests/api/nav008/staging_checks.py --group log-markers`. QA sends the operator the needle file `log-markers-<time>.txt` (marker coordinates and text, no personal data).
2. *(Operator, day 1 or later, same Caddy digest)* copy `tests/staging/nav008/log-privacy-scan.sh` and the needle file to the host, then:

```bash
sudo bash log-privacy-scan.sh --since 24h --markers log-markers-<time>.txt
```

3. Paste the **whole output** to QA. It shows counts and digit-masked samples only. Do not send raw log lines.

| # | Expected | Result |
|---|---|---|
| LP-01…06 | no line in any container log or nav-* unit journal with a public IPv4/IPv6 address, query string, request body, coordinate pair or client header | |
| LP-07 | no line with a QA marker value | |
| LP-08 | sshd source IPs: count only (INFO, security log for the counsel review) | |
| LP-09 | Caddy has no `*.log` files | |
| retention | Docker log driver `local` (5 × 10 MB); journald `SystemMaxUse=1G`, `MaxRetentionSec=14day` | |

## O-4 Daily data rebuild (AC 15, 16)

Preparation: empty auxiliary cache (move `backend/data/sources/` aside or start from an empty `data/` as the runbook says), `OSM_PBF_URL=https://download.geofabrik.de/asia/mongolia-latest.osm.pbf`.

- [ ] *(QA)* before the start: `tests/staging/nav008/downtime-probe.sh --route --interval 2` (stop it with Ctrl-C after the smoke run)
- [ ] In a second shell on the host: `make stats` sampled every ≤ 3 s for the whole build (per runbook), output saved

| # | Record | Expected | Result |
|---|---|---|---|
| RB-01 | Start and end (UTC), total minutes | ≤ 30 min, no manual step, no pre-seeded file | |
| RB-02 | `data/build-info.json`: source URL, replication timestamp | Geofabrik; timestamp ≤ 48 h before the run | |
| RB-03 | *(QA)* probe summary: downtime seconds, statuses seen (`502` JSON from Caddy's backstop or the gateway) | measured and recorded | |
| RB-04 | Every C9 download succeeded from the host | yes | |
| RB-05 | *(QA)* `tests/staging/nav008/smoke-staging.sh` after the build | exit 0 | |
| RB-06 | Peak build memory + steady-state services, as % of RAM | ≤ 70 % | |
| RB-07 | `df -h /` after the build | ≥ 50 GB free | |
| RB-08 | Rebuild heartbeat reached the push monitor | yes | |

## O-5 Monitoring and alert drill (AC 17, 18)

Outside the maintenance window. *(QA)* runs `downtime-probe.sh --interval 5` as the reference clock.

| # | Step / check | Expected | Result |
|---|---|---|---|
| AL-01 | T0 = `docker compose ... stop gateway` (UTC) | — | |
| AL-02 | First alert on the operator's channel (UTC) | ≤ T0 + 5 min | |
| AL-03 | T1 = start the gateway; recovery notice (UTC) | ≤ T1 + 5 min | |
| AL-04 | Uptime monitor settings (screenshot or config export without tokens) | interval ≤ 60 s, on a different provider, expects 200 and `"status":"ok"`, alert after ≤ 2 failures | |
| AL-05 | Disk push monitor and certificate notification | disk alert at ≥ 85 % (push stops); certificate alert at ≤ 14 days | |
| AL-06 | `sar -u -r -d -f /var/log/sysstat/sa$(date -d '7 days ago' +%d)` | data for 7 days ago exists (retention ≥ 7 days, design: 28) | |

## O-6 Backup review (AC 19)

| # | Command / check | Expected | Result |
|---|---|---|---|
| BK-01 | `sudo restic snapshots` (with the repository settings from the runbook) | daily snapshots, stored on the ops VM at another provider | |
| BK-02 | `sudo restic ls latest \| grep -E '\.env\|caddy\|ufw\|sshd_config.d\|nav-.*\.(service\|timer)\|daemon.json\|sysstat\|unattended'` | every design §10 include-list item present | |
| BK-03 | `sudo restic ls latest \| grep -c '/data/'` | `0` (data/ excluded) and the runbook says it is rebuildable | |
| BK-04 | Forget policy in the backup unit | `--keep-daily 7 --keep-weekly 4` (or more) | |
| BK-05 | Encryption | restic repository (always encrypted); password file readable by root only, not in git or in the backup | |

## O-7 Restore drill (AC 20)

On a **new, empty VM of the same plan**, following only the runbook. *(QA)* starts `downtime-probe.sh` against the new host as the clock.

| # | Record | Expected | Result |
|---|---|---|---|
| RR-01 | Start (UTC), each runbook step with its time | — | |
| RR-02 | *(QA)* `smoke-staging.sh --base-url https://<new host or same name after DNS change>` exit 0 (UTC) | within ≤ 2 h of the start | |
| RR-03 | Total time; problems or runbook gaps found | recorded in the runbook log | |

## O-8 Repository and infra inspection (AC 21), QA

- [ ] `tests/staging/nav008/repo-checks.sh --stage accept` has 0 failures. Fill `STAGING_IPV4`/`STAGING_IPV6` in the local `staging.local.env` first, so RS-04 searches files and history for the real address.
- [ ] `infra/` plus the runbook contain: provisioning, deploy and update, certificate renewal (and its dry run), rebuild, restore, monitoring setup, access roles by role name only.
- [ ] Every new configuration key is in a `.env.example` with a one-line comment (RS-05), and secret keys are empty (RS-06).

## O-9 Invoice (AC 22), PO or architect with QA

| Field | Value |
|---|---|
| Invoice date | |
| Term length (months), term total (USD) | |
| Introductory monthly rate, renewal monthly rate (USD) | |
| Monthly add-ons (paid backups/snapshots, ops VM) | |
| Effective monthly cost, introductory = total ÷ months + add-ons | ≤ 150 |
| Effective monthly cost, renewal = renewal rate + add-ons | ≤ 150 |
| Recorded in ADR-0005 | yes / no |

## O-10 Documentation (AC 23), QA

- [ ] `repo-checks.sh --stage accept`: RS-10 and RS-11 pass (real `https://staging.<domain>` in `openapi.yaml` `servers` and in `backend/README.md`).

## O-11 Counsel record (AC 24), QA

- [ ] A row in `docs/requirements/decisions.md` and a line in the NAV-008 Change log give: the review date, the reviewer's **role** (no name), the outcome (go / go with conditions / no go) and any conditions (for example a tester consent text).
- [ ] The date of the **first invitation** of anyone outside the team (NAV-007 panel, partner drivers, outside testers) is recorded, and it is **on or after** the review date.
- [ ] If the outcome has conditions, each condition is met before the invitation.
- [ ] Until then: only the project team has the URL (checked with the PO).
