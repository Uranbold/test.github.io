#!/usr/bin/env python3
"""NAV-005 static and build checks on mobile/android and the debug APK (no device needed).

    python3 tests/android/nav005/static_checks.py [--apk mobile/android/app/build/outputs/apk/debug/app-debug.apk]

Build the APK first (cd mobile/android && ./gradlew :app:assembleDebug). Test plan docs/qa/test-plans/NAV-005.md ids
TC-S*. Story AC 1, 8, 13, 15, 61, 62 (recenter term), 65 (static part), 66, 67 (allowBackup), 69, 70.
Read-only: never builds, installs or contacts anything. Exit 0 only if every check passes.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ANDROID = os.path.join(REPO, "mobile", "android")
sys.path.insert(0, os.path.join(REPO, "tests", "api", "nav001"))
from lib import Report  # noqa: E402

# Public documentation/tooling hosts that may appear in comments or READMEs; they are not servers the app talks to.
DOC_HOSTS = {"dl.google.com", "maven.google.com", "developer.android.com", "github.com", "raw.githubusercontent.com",
             "crates.io", "static.crates.io", "docs.rs", "www.apache.org", "opensource.org", "spdx.org",
             "maven-central.storage-download.googleapis.com", "repo.maven.apache.org", "repo1.maven.org",
             "schemas.android.com", "www.w3.org", "fonts.google.com", "material.io", "fonts.gstatic.com",
             "protomaps.com", "maplibre.org", "openstreetmap.org", "www.openstreetmap.org", "kotlinlang.org",
             "gradle.org", "services.gradle.org", "docs.gradle.org", "plugins.gradle.org", "stadiamaps.github.io",
             "unicode.org", "www.unicode.org", "valhalla.github.io", "source.android.com", "www.gnu.org",
             "scripts.sil.org", "openfontlicense.org", "creativecommons.org", "json-schema.org",
             # licence texts and build tooling named in THIRD_PARTY_NOTICES.md and tools/ (not app endpoints)
             "findbugs.sourceforge.net", "jspecify.org", "square.github.io", "index.crates.io", "rustup.rs",
             "maven.apache.org"}
LOOPBACK = {"127.0.0.1", "localhost", "10.0.2.2"}
FORBIDDEN_DEPS = re.compile(r"firebase|crashlytics|analytics|sentry|bugsnag|appcenter|instabug|amplitude|mixpanel|"
                            r"segment\.analytics|play-services|gms:|mapbox|telemetry|newrelic|datadog", re.I)


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "mobile/android"], cwd=REPO, capture_output=True, text=True).stdout
    return [os.path.join(REPO, p) for p in out.splitlines() if p]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=os.path.join(ANDROID, "app/build/outputs/apk/debug/app-debug.apk"))
    a = ap.parse_args()
    rep = Report()

    # ---------------------------------------------------------------- AC 69 / AC 8 / 13 / 15 / 67: the APK and its manifest
    if not rep.check("TC-S01.apk_exists (AC 69)", os.path.exists(a.apk), "debug APK built", a.apk):
        print(rep.summary())
        return 1
    aapt = sorted(glob.glob("/opt/android-sdk/build-tools/*/aapt2") + glob.glob(os.path.expandvars("$ANDROID_HOME/build-tools/*/aapt2")))
    if aapt:
        perms = subprocess.run([aapt[-1], "dump", "permissions", a.apk], capture_output=True, text=True).stdout
        tree = subprocess.run([aapt[-1], "dump", "xmltree", a.apk, "--file", "AndroidManifest.xml"], capture_output=True, text=True).stdout
        rep.check("TC-S02.no_background_location (AC 8)", "ACCESS_BACKGROUND_LOCATION" not in perms, "absent", "present" if "ACCESS_BACKGROUND_LOCATION" in perms else "absent")
        for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION", "POST_NOTIFICATIONS"):
            rep.check(f"TC-S03.permission_{p} (AC 8/13/15)", f"android.permission.{p}'" in perms, "declared", None)
        i = tree.find("GuidanceForegroundService")
        block = tree[i:tree.find("E: ", i)] if i >= 0 else ""
        svc = re.search(r"foregroundServiceType\(0x01010599\)=0x([0-9a-f]+)", block)
        rep.check("TC-S04.service_type_location (AC 15)", bool(svc) and int(svc.group(1), 16) & 0x8 == 0x8, "foregroundServiceType includes location (0x8)", svc.group(1) if svc else None)
        rep.check("TC-S05.allow_backup_false (AC 67)", re.search(r"allowBackup\(0x01010280\)=false", tree) is not None, "allowBackup=false", None)
    else:
        rep.skip("TC-S02..05", "aapt2 not found")
    with zipfile.ZipFile(a.apk) as z:
        names = z.namelist()
        rep.check("TC-S06.no_test_hooks_in_apk (AC 61/72)", not any(re.search(r"(Replay|QaRun|TestStrings|MockWebServer)", n) for n in names), "no test classes/resources by name", [n for n in names if "Replay" in n][:3])
        dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
        rep.check("TC-S06b.no_qa_or_replay_classes_in_dex (AC 72)", b"mn/navmn/app/qa/" not in dex and b"mn/navmn/app/support/Replay" not in dex, "absent", None)
        style = [n for n in names if n.startswith("assets/style/")]
        hosts = set()
        for n in style:
            hosts |= set(re.findall(rb"(https?)://([A-Za-z0-9.-]+)", z.read(n)))
        rep.check("TC-S07.style_has_no_remote_urls (AC 65)", not hosts and len(style) >= 2, "style JSON only asset:// + gateway placeholder", sorted(hosts)[:5])
        # Hosts embedded in code (INFO): libraries carry provider URLs they only use for mapbox:// / provider schemes.
        found = set(re.findall(rb"https?://([A-Za-z0-9.-]+\.[a-z]{2,})", dex))
        for n in names:
            if n.startswith("lib/arm64-v8a/") and n.endswith(".so"):
                found |= set(re.findall(rb"https?://([A-Za-z0-9.-]+\.[a-z]{2,})", z.read(n)))
        rep.note("TC-S08.hosts_embedded_in_code (AC 65, INFO)", sorted(h.decode() for h in found))

    # ---------------------------------------------------------------- AC 70: pinned versions, repositories, minSdk
    build_files = [os.path.join(ANDROID, p) for p in ("gradle/libs.versions.toml", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts")]
    dyn = []
    for f in build_files:
        for i, line in enumerate(open(f, encoding="utf-8"), 1):
            code = line.split("//")[0].split("#")[0]
            if re.search(r'["\'][^"\']*(\+|latest\.|\[[^\]]*,[^\]]*\])["\']', code) and "version" in code.lower() or re.search(r':\s*[\w.-]+:[\w.-]*\+["\']', code):
                dyn.append(f"{os.path.relpath(f, REPO)}:{i}: {line.strip()}")
    rep.check("TC-S09.no_dynamic_versions (AC 70)", not dyn, "0 dynamic versions", dyn[:5])
    toml = open(build_files[0], encoding="utf-8").read()
    for lib, ver in (("ferrostar", "0.57.0"), ("maplibre", "13.6.1")):
        m = re.search(rf'^{lib}\s*=\s*"([^"]+)"', toml, re.M)
        rep.check(f"TC-S10.{lib}_pinned (AC 70)", bool(m) and m.group(1) == ver, ver, m.group(1) if m else None)
    app_gradle = open(build_files[3], encoding="utf-8").read()
    m = re.search(r"minSdk\s*=\s*(\d+)", app_gradle)
    rep.check("TC-S11.min_sdk (AC 70, Open question 5)", bool(m) and int(m.group(1)) >= 25, "minSdk >= 25 (BA recommendation 26; PO to confirm)", m.group(1) if m else None)
    settings = open(build_files[2], encoding="utf-8").read()
    repos = set(re.findall(r"(google\(\)|mavenCentral\(\)|gradlePluginPortal\(\)|url\s*=\s*uri\(\"([^\"]+)\"\)|maven\(\"([^\"]+)\"\))", settings))
    rep.note("TC-S12.repositories (AC 70, INFO)", sorted({r[0] for r in repos}))
    bad_repo = [r for r in re.findall(r"https?://[^\"\s)]+", settings) if not re.search(r"google|maven|gradle", r)]
    rep.check("TC-S12b.repositories_maven_central_or_google (AC 70)", not bad_repo, "only Maven Central (or its Google mirror) and Google", bad_repo)

    # ---------------------------------------------------------------- AC 66: no server hostnames / IPs / keystores / local files
    files = tracked_files()
    host_hits, ip_hits = [], []
    for f in files:
        if not os.path.isfile(f) or any(seg in f for seg in ("/build/", "/.gradle/")) or f.endswith((".jar", ".png", ".webp", ".pbf", ".apk", ".so")):
            continue
        try:
            text = open(f, encoding="utf-8").read()
        except (UnicodeDecodeError, IsADirectoryError):
            continue
        rel = os.path.relpath(f, REPO)
        for h in re.findall(r"https?://([A-Za-z0-9.-]+)", text):
            hl = h.lower().rstrip(".")
            if "." not in hl.strip(".") or hl in LOOPBACK or hl in DOC_HOSTS or hl.endswith((".invalid", ".example", "example.com", "example.org")) or hl.startswith("{"):
                continue
            host_hits.append(f"{rel}: {h}")
        for ip in re.findall(r"\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b", text):
            if ip.startswith(("127.", "10.0.2.", "0.")) or rel.endswith(".json") and "style" in rel:
                continue
            if all(0 <= int(x) <= 255 for x in ip.split(".")):
                ip_hits.append(f"{rel}: {ip}")
    rep.check("TC-S13.no_server_hostnames (AC 66)", not host_hits, "0 non-loopback, non-documentation hosts", sorted(set(host_hits))[:10])
    rep.check("TC-S14.no_ip_literals (AC 66)", not ip_hits, "0 non-loopback IPv4 literals", sorted(set(ip_hits))[:10])
    secrets = [os.path.relpath(f, REPO) for f in files if f.endswith((".jks", ".keystore", ".p12")) or os.path.basename(f) in ("local.properties", "gateway.local.properties")]
    rep.check("TC-S15.no_keystore_or_local_files_tracked (AC 66)", not secrets, "none would be committed", secrets)
    ign = subprocess.run(["git", "check-ignore", "mobile/android/local.properties", "mobile/android/gateway.local.properties"], cwd=REPO, capture_output=True, text=True).stdout.split()
    rep.check("TC-S16.local_files_ignored (AC 66)", len(ign) == 2, "local.properties and gateway.local.properties git-ignored", ign)
    nsc_debug = open(os.path.join(ANDROID, "app/src/debug/res/xml/network_security_config.xml"), encoding="utf-8").read()
    nsc_main = open(os.path.join(ANDROID, "app/src/main/res/xml/network_security_config.xml"), encoding="utf-8").read()
    debug_domains = set(re.findall(r"<domain[^>]*>([^<]+)</domain>", nsc_debug))
    rep.check("TC-S17.cleartext_debug_loopback_only (AC 66)", debug_domains and debug_domains <= LOOPBACK, "debug cleartext only for loopback/emulator", sorted(debug_domains))
    rep.check("TC-S18.release_no_cleartext (AC 66)", 'cleartextTrafficPermitted="true"' not in nsc_main, "main config permits no cleartext", None)

    # ---------------------------------------------------------------- AC 65: dependency review (declared deps)
    deps = [line.strip() for line in toml.splitlines() if "module" in line]
    bad = [d for d in deps if FORBIDDEN_DEPS.search(d)]
    rep.check("TC-S19.no_analytics_crash_or_play_services_deps (AC 14, 65)", not bad, "none", bad)

    # ---------------------------------------------------------------- AC 61 / 62: strings and glossary terms
    src = [f for f in glob.glob(os.path.join(ANDROID, "app/src/main/java/**/*.kt"), recursive=True)]
    literals = []
    for f in src:
        for i, line in enumerate(open(f, encoding="utf-8"), 1):
            code = re.sub(r"//.*$", "", line)
            if code.strip().startswith(("*", "/*")) or "scan:data" in line:  # OSM-data matchers marked by mobile
                continue
            for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', code):
                if re.search(r"[Ѐ-ӿ]", lit):
                    literals.append(f"{os.path.relpath(f, REPO)}:{i}: \"{lit[:40]}\"")
    rep.check("TC-S20.no_hardcoded_mongolian_literals (AC 61)", not literals, "0 Cyrillic string literals in Kotlin", literals[:8])
    text_lits = []
    for f in src:
        for i, line in enumerate(open(f, encoding="utf-8"), 1):
            if re.search(r'\bText\(\s*"[A-Za-z]', line) or re.search(r'contentDescription\s*=\s*"[A-Za-z]', line):
                text_lits.append(f"{os.path.relpath(f, REPO)}:{i}: {line.strip()[:80]}")
    rep.check("TC-S21.no_hardcoded_english_ui_literals (AC 61)", not text_lits, "0 Text(\"…\") / contentDescription literals", text_lits[:8])
    mn = open(os.path.join(ANDROID, "app/src/main/res/values/strings.xml"), encoding="utf-8").read()
    en = open(os.path.join(ANDROID, "app/src/main/res/values-en/strings.xml"), encoding="utf-8").read()
    keys_mn = set(re.findall(r'<string name="([^"]+)"(?![^>]*translatable="false")', mn))
    keys_en = set(re.findall(r'<string name="([^"]+)"', en))
    rep.check("TC-S22.mn_en_key_sets_identical (AC 61)", keys_mn == keys_en, "identical translatable key sets", {"only_mn": sorted(keys_mn - keys_en)[:5], "only_en": sorted(keys_en - keys_mn)[:5]})
    rep.check("TC-S23.recenter_glossary_term (AC 25, 62, D22)", "Байршил руу буцах" in mn and "Төвлөрүүлэх" not in mn, "«Байршил руу буцах» present, «Төвлөрүүлэх» absent", None)
    rep.check("TC-S24.attribution_string (AC 1, 2)", "© OpenStreetMap contributors" in mn, "present", None)
    for term in ("Дуусгах", "Дууг хаах", "Дууг нээх", "Хойд зүг дээшээ", "Явах чиглэл дээшээ", "Замчлал", "Маршрутыг дахин тооцоолж байна", "Та маршрутаас гарлаа", "GPS дохио тасарлаа", "GPS дохио сэргэлээ"):
        rep.check(f"TC-S25.term_{term} (AC 62/42/51)", f">{term}<" in mn, "exact resource value", None)
    g = subprocess.run(["node", os.path.join(ANDROID, "tools/check-glossary.mjs")], cwd=REPO, capture_output=True, text=True)
    rep.check("TC-S26.glossary_check (AC 61)", g.returncode == 0, "check-glossary exit 0", (g.stdout + g.stderr).strip().splitlines()[-1:] if (g.stdout + g.stderr).strip() else g.returncode)

    # ---------------------------------------------------------------- AC 1: start camera
    vm = open(os.path.join(ANDROID, "app/src/main/java/mn/navmn/app/ui/AppViewModel.kt"), encoding="utf-8").read()
    rep.check("TC-S27.start_camera_P1_z12 (AC 1)", "LatLon(47.9189, 106.9176)" in vm and re.search(r"DEFAULT_ZOOM\s*=\s*12\.0", vm) is not None, "P1, zoom 12", None)
    readme = open(os.path.join(ANDROID, "README.md"), encoding="utf-8").read()
    rep.check("TC-S28.readme_documents_sdk_url_commands (AC 69)", all(k in readme for k in ("ANDROID_HOME", "nav.gatewayBaseUrl", "assembleDebug")), "SDK setup, gateway property, commands", None)

    for k, v in rep.info.items():
        print(f"INFO  {k}: {v}")
    print(rep.summary())
    return 0 if not rep.failed else 1


if __name__ == "__main__":
    sys.exit(main())
