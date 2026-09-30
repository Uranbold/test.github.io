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
| O-1.5 | Break-glass is **offered**, not used (design §3, §17.4). Hostinger: in hPanel, open the VPS **Settings** page and confirm that **Emergency mode** (rescue boot, VPS disk mounted under `/mnt`) is offered for this VPS, and note where the SSH-configuration reset is (last resort; it undoes the hardening, so bootstrap must run again). Other providers: confirm their rescue mode or VNC/serial console is offered. **Do not trigger it** on the live host. The hPanel "Browser terminal" is an SSH client and is **not** break-glass | Emergency mode (or the provider's rescue/VNC console) is listed for this VPS; screenshot or menu path recorded without the IP | |
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

Preparation: **AL-05 (push reachability, O-5) passed first**, otherwise RB-08 cannot pass. Empty auxiliary cache (move `backend/data/sources/` aside or start from an empty `data/` as the runbook says), `OSM_PBF_URL=https://download.geofabrik.de/asia/mongolia-latest.osm.pbf`.

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
| RB-08 | Rebuild heartbeat reached the push monitor (design §9, AC 15). In Uptime Kuma (UI through the SSH tunnel) open **nav-staging rebuild** | A new green heartbeat at the build end time (UTC) with the message `rebuilt in … s, smoke ok` (or `unchanged, smoke ok`); monitor UP. Record both UTC times. `nav-rebuild.sh` ended with exit 0 but no heartbeat = push not reachable: re-run AL-05 and report to backend-engineer | |

## O-5 Monitoring and alert drill (AC 17, 18)

Outside the maintenance window. *(QA)* runs `downtime-probe.sh --interval 5` as the reference clock.

| # | Step / check | Expected | Result |
|---|---|---|---|
| AL-01 | T0 = `docker compose ... stop gateway` (UTC) | — | |
| AL-02 | First alert on the operator's channel (UTC) | ≤ T0 + 5 min | |
| AL-03 | T1 = start the gateway; recovery notice (UTC) | ≤ T1 + 5 min | |
| AL-04 | Uptime monitor settings (screenshot or config export without tokens) | interval ≤ 60 s, on a different provider, expects 200 and `"status":"ok"`, alert after ≤ 2 failures | |
| AL-05 | **Push reachability** (design §9, §17.4). On the staging host: `sudo infra/staging/bin/nav-diskcheck.sh; echo "exit=$?"`. Then, in Uptime Kuma (UI through the SSH tunnel), open **nav-staging disk** | `exit=0`, a log line with `"msg":"heartbeat sent"`, and a new **green** heartbeat in Kuma at that time (UTC), message `disk ok: …`. Record the UTC time of the command and of the heartbeat. `heartbeat skipped` (no push URL in `.env`) or `heartbeat failed` fails AL-05, even with `exit=0`. No heartbeat in Kuma means the host cannot reach the push URL (ops VM Caddy or UFW 80/443, or the `http://localhost:3001` origin from the Kuma UI left in `UPTIME_PUSH_URL_*`): stop, report to backend-engineer | |
| AL-05.1 | Push endpoint exposure, from the staging host, **no token used**: `curl -sS -o /dev/null -w '%{http_code}\n' https://<ops-host>/` and `curl -sS https://<ops-host>/api/push/not-a-token` (paste outputs, not the host name) | First: `404` (the Kuma UI is not public). Second: a JSON answer from Uptime Kuma with `"ok":false` (the push path reaches Kuma) | |
| AL-05.2 | Monitor settings (screenshot or config export without tokens) | **nav-staging disk**: push, heartbeat interval 900 s, retries 0 (no push for 15 min = alert). Liveness monitor: certificate expiry notification at ≤ 14 days | |
| AL-06 | `sar -u -r -d -f /var/log/sysstat/sa$(date -d '7 days ago' +%d)` | data for 7 days ago exists (retention ≥ 7 days, design: 28) | |
| AL-07 | **Disk alert drill (AC 18)**, steps below. Needs AL-05 green | Alert on the operator's channel after the heartbeat stops; recovery after the restore; `.env` identical to before | |

**AL-07 disk alert drill (AC 18).** Shows that the ≥ 85 % rule really fires, without filling the disk: `DISK_ALERT_PERCENT` is lowered below the current usage for one push interval, then restored. `nav-diskcheck.sh` reads it from `infra/staging/.env` on every run and the **last** assignment wins (`nav-env.sh`). Run it outside the maintenance window and away from the 19:30 UTC rebuild and 20:30 UTC backup. Tell the alert receiver first. Leave `nav-diskcheck.timer` running. The backup copy of `.env` holds secrets: keep it root-only under `/root`, never paste it, delete it at the end.

```bash
cd /opt/nav                                                    # path per runbook
df -P / | awk 'NR==2 {print $5}'                               # D1: current usage, e.g. 23%
sudo cp -p infra/staging/.env /root/nav-env.al07.bak           # root-only copy (keeps mode 600)
echo 'DISK_ALERT_PERCENT=1' | sudo tee -a infra/staging/.env >/dev/null   # no inline comment: the value is read to the end of the line
sudo infra/staging/bin/nav-diskcheck.sh; echo "exit=$?"        # D2: expect exit=1 and "heartbeat withheld"
# wait for the alert on the operator's channel (design §9: about 15 min after the last green heartbeat)
sudo cp -p /root/nav-env.al07.bak infra/staging/.env           # restore
sudo cmp /root/nav-env.al07.bak infra/staging/.env && echo identical   # D5
sudo rm /root/nav-env.al07.bak
sudo infra/staging/bin/nav-diskcheck.sh; echo "exit=$?"        # D6: expect exit=0, heartbeat green again
```

| # | Record | Expected | Result |
|---|---|---|---|
| D1 | Usage of `/` before the drill | below 85 % (otherwise the real alert is already active: stop and report) | |
| D2 | Output of the lowered run (UTC) | `exit=1` and a JSON log line with `"msg":"disk usage at or above the alert limit; heartbeat withheld"` and `"limit_percent":"1"` | |
| D3 | UTC time of the last green heartbeat in **nav-staging disk** | — | |
| D4 | UTC time of the DOWN alert on the operator's channel; minutes after D3 | the alert arrives (design §9: about 15 min, push interval 900 s). No alert 30 min after D3: restore at once and report to backend-engineer (AC 18 fails) | |
| D5 | `cmp` result after the restore | `identical` | |
| D6 | Restored run, recovery notice (UTC) | `exit=0`, `"msg":"heartbeat sent"`, green heartbeat, recovery notice on the operator's channel | |

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
