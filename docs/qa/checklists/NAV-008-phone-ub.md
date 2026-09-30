# NAV-008 phone checklist: tester in Ulaanbaatar (AC 6, 8, 9, 10)

Owner: qa-engineer. Test plan: [`docs/qa/test-plans/NAV-008.md`](../test-plans/NAV-008.md) sections 3 and 4.
Tool: [`tests/staging/nav008/phone-kit.sh`](../../../tests/staging/nav008/phone-kit.sh), one file that needs only bash and curl.
Based on the spike's c1 protocol ([spike §5](../../architecture/spikes/nav-008-hosting.md)).

**Privacy.** Record the **district only**, never a home or work address. The kit never stores or prints your public IP: it keeps only the operator's network number (ASN) and name. To find the ASN, the kit sends your IP once to the RIPE NCC lookup service (stat.ripe.net), as the spike protocol does. Do not paste screenshots that show your IP.

**Who may use the staging URL.** Until the counsel record for AC 24 exists, only the project team uses it. Do not share the URL.

## 0. Before you go (once)

- [ ] SIM cards from **at least 2** operators (Unitel, MobiCom, Skytel, G-Mobile; all four if possible), prepaid data on the **default APN**.
- [ ] One Android phone with Chrome. An iPhone with Safari too, if available (AC 6).
- [ ] Either (a) a laptop and a USB cable for tethering, or (b) Termux on the Android phone (from F-Droid, then `pkg install curl`). An iPhone can only be measured with (a). AC 9 and the tethered smoke run (AC 10) need (a).
- [ ] QA sends you `phone-kit.sh` and the value for `STAGING_HOST` (by chat, not in the repo).
- [ ] For the tethered smoke run (step 5), the laptop has a clone of the repository and `python3`.
- [ ] Battery charged. Place: central UB, outdoors or near a window, at least 3 signal bars. Optional extra run in a ger district, for information only.
- [ ] Time windows: at least one run per operator. Recommended: 08:00–10:00, 12:00–14:00 and 19:00–22:00 (evening peak).

## 1. Set up for one operator

- [ ] Put the SIM of this operator in the phone. Turn **Wi-Fi off** on the phone.
- [ ] Laptop (a): turn **Wi-Fi and Ethernet off** and enable USB tethering. No VPN, no proxy.
- [ ] Set the session values. Use the operator's name, the network type the phone shows, your district and the phone model:

```bash
export STAGING_HOST=staging.<domain>        # from QA
export OPERATOR=Unitel NET=4G PLACE=Sukhbaatar DEVICE="Pixel 7" WIFI_OFF=yes
```

## 2. AC 6: health in the browser and with the kit

- [ ] Open `https://<STAGING_HOST>/health` in the phone's **default browser** (Chrome on Android, Safari on iPhone).
      Expected: the page shows `{"status":"ok"}` and there is **no certificate warning**. Take a screenshot (crop the status bar if it shows an IP).
- [ ] Run `bash phone-kit.sh info` and then `bash phone-kit.sh health`.
      Expected: `PASS  AC6.health ...`. The lines "health over IPv4/IPv6" are information for the IPv6/NAT64 edge case.

## 3. AC 8: TCP connect time (20 samples)

- [ ] Run `bash phone-kit.sh c1`. It takes about 30 seconds. Keep the phone still and the screen on.
- [ ] Read the `RESULT=` line:
  - `RESULT=REPEAT`: more than 2 timeouts out of 20. The run does not count. Move to a better signal and run `c1` again.
  - `RESULT=RECORDED (median <= 100 ms ...)`: fine, go on to step 4.
  - `RESULT=RECORDED FLAG_D26 ...`: the median is **above 100 ms**. **Stop here for this operator.** Send the `c1-summary-*.txt` file to QA now. QA asks the PO (through the orchestrator) to accept the value or ask for action. Run step 4 only after QA tells you the PO's answer is recorded, with `export D26_PO_RESPONSE=recorded`.

## 4. AC 9: route response time over one connection (tethered laptop)

- [ ] On the laptop (still tethered, Wi-Fi off), run `bash phone-kit.sh route-p95`.
      Expected: `PASS  AC9 p95 ... <= 500 ms`. A `WARN ... new connection` line means keep-alive broke. The p95 still counts; tell QA.
- [ ] AC 9 needs one passing run. Running it on every operator is better, because it shows the spread.

## 5. AC 10: smoke suite from the mobile network (tethered laptop, once)

- [ ] In the repository clone on the tethered laptop:

```bash
BASE_URL=https://$STAGING_HOST OPERATOR=$OPERATOR NET=$NET tests/staging/nav008/smoke-staging.sh --tethered
```

      Expected: `SM-02 NAV-001 smoke exit 0 (tethered-...)`. The script refuses to run if a proxy variable is set.

## 6. Repeat steps 1 to 4 for the next operator

At least 2 operators in total. With an iPhone, repeat step 2 (browser) on it too.

## 7. Send back to QA

- [ ] The whole `nav008-results/` folder (CSV, summaries, logs; no IP inside) from the phone and from the laptop, plus the laptop's `tests/staging/nav008/results/smoke-tethered-*` files.
- [ ] The browser screenshots.
- [ ] Anything unusual: signal drops, captive portals, a certificate warning, 429 errors, an operator where the page did not load at all.

## Record sheet (QA fills it from the files; the tester may fill it on paper)

| # | Operator | Net | District | Date, time | Egress ASN | AC 6 browser (Android / iPhone) | AC 6 kit | IPv6 | c1 n / median / p95 / timeouts | Result | AC 9 p95 | AC 10 tethered |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | | | | | |
| 2 | | | | | | | | | | | | |
| 3 | | | | | | | | | | | | |
| 4 | | | | | | | | | | | | |
