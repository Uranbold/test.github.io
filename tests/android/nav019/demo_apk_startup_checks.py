#!/usr/bin/env python3
"""NAV-019 bug B-NAV019-01: demo APK start-up checks on the APK file itself (no device, no emulator).

    python3 tests/android/nav019/demo_apk_startup_checks.py --apk <demo.apk>

Default --apk: mobile/android/app/build/outputs/apk/demo/app-demo.apk (build it with
./gradlew :app:assembleDemo -Pnav.demoTilesFile=<pmtiles>). Works the same on the orchestrator's repackaged
arm64-only APK. Test plan docs/qa/test-plans/NAV-019.md, section "Bug B-NAV019-01", ids TC-B19-10..TC-B19-24.
Story NAV-019 AC 6 (the opened demo build shows the picker: the process must start), AC 1 (installable demo APK).

TC-B19-10 is the APK-level reproduction of the crash: starting from NavApplication.onCreate it follows invokes into
app code (mn/navmn/**, interface calls to every app implementation, D8 lambda classes created on the way) and fails
on any reference to MapLibre (org/maplibre/**). At process start nothing has called MapLibre.getInstance yet, and
MapLibre's HttpRequestImpl/HttpIdentifier call MapLibre.getApplicationContext(), which throws
MapLibreConfigurationException (commit 763bd4e: DemoVariant.onApplicationCreate -> HttpRequestUtil.setOkHttpClient).
The JVM twin of this check is mobile/android/app/src/testDemo/java/mn/navmn/app/qa/QaNav019DemoStartupTest.kt.
Known limit: coroutine bodies (suspend lambda classes) are not followed; they run later, off the start path.

TC-B19-20..24 record the other crash candidates checked for the PO's phone class (arm64-v8a, API 28-30): native
libraries, ELF API level and symbol versions, zip alignment / stored libs, manifest, framework/JDK calls above minSdk
in app code. Read-only: never builds, installs or contacts anything. Exit 0 only if every check passes.
"""
import argparse
import collections
import glob
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ANDROID = os.path.join(REPO, "mobile", "android")
sys.path.insert(0, os.path.join(REPO, "tests", "api", "nav001"))
from lib import Report  # noqa: E402

APP_PKG = "mn/navmn/"
START = ("mn/navmn/app/NavApplication", "onCreate", "()V")
MIN_SDK = 26
REQUIRED_LIBS = {"libferrostar.so", "libjnidispatch.so", "libmaplibre.so"}
# NDK stable system libraries available on every API >= 26 device.
NDK_SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libjnigraphics.so",
                   "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libvulkan.so",
                   "libmediandk.so", "libnativewindow.so", "libaaudio.so", "libcamera2ndk.so", "libstdc++.so"}
# bionic symbol-version nodes up to API 26 (LIBC_N = 24, LIBC_O = 26); LIBC_P (28) and later would not load on 26.
ALLOWED_LIBC_VERSIONS = {"LIBC", "LIBC_N", "LIBC_O", "LIBC_PRIVATE"}


def sdk_tool(name):
    roots = [os.environ.get("ANDROID_HOME", ""), os.environ.get("ANDROID_SDK_ROOT", ""), "/opt/android-sdk"]
    hits = sorted(p for r in roots if r for p in glob.glob(os.path.join(r, "build-tools", "*", name)))
    return hits[-1] if hits else None


def api_versions():
    roots = [os.environ.get("ANDROID_HOME", ""), os.environ.get("ANDROID_SDK_ROOT", ""), "/opt/android-sdk"]
    hits = sorted(p for r in roots if r for p in glob.glob(os.path.join(r, "platforms", "android-*", "data", "api-versions.xml")))
    return hits[-1] if hits else None


# ------------------------------------------------------------------------------------------------ dex model (dexdump)
METHOD_HDR = re.compile(r"^\s+#\d+\s+: \(in L([\w/$-]+);\)")
NAME = re.compile(r"^\s+name\s+: '([^']+)'")
TYPE = re.compile(r"^\s+type\s+: '([^']+)'")
CLASS_HDR = re.compile(r"^\s+Class descriptor\s+: 'L([\w/$-]+);'")
SUPER = re.compile(r"^\s+Superclass\s+: 'L([\w/$-]+);'")
IFACE = re.compile(r"^\s+#\d+\s+: 'L([\w/$-]+);'")
REF = re.compile(r"\|[0-9a-f]{4}: (invoke-\S+|[si]get\S*|[si]put\S*|new-instance|const-class) [^L]*L([\w/$-]+);(?:\.([\w$<>-]+):(\S+))?")


def load_dex(apk, dexdump, workdir):
    """method key (cls, name, sig) -> ordered [(op, cls, name, sig)]; class -> (super, [interfaces])."""
    methods, hier = {}, {}
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        for d in dexes:
            path = os.path.join(workdir, d)
            with open(path, "wb") as f:
                f.write(z.read(d))
            proc = subprocess.Popen([dexdump, "-d", path], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
            cls = cur = None
            pending_name = None
            in_ifaces = False
            for line in proc.stdout:
                m = CLASS_HDR.match(line)
                if m:
                    cls = m.group(1)
                    hier[cls] = [None, []]
                    in_ifaces = False
                    continue
                if cls and line.startswith("  Superclass"):
                    m = SUPER.match(line)
                    hier[cls][0] = m.group(1) if m else None
                    continue
                if line.startswith("  Interfaces"):
                    in_ifaces = True
                    continue
                if in_ifaces:
                    m = IFACE.match(line)
                    if m and "(in L" not in line:
                        hier[cls][1].append(m.group(1))
                        continue
                    in_ifaces = False
                if METHOD_HDR.match(line):
                    pending_name = None
                    cur = None
                    continue
                m = NAME.match(line)
                if m:
                    pending_name = m.group(1)
                    continue
                m = TYPE.match(line)
                if m and pending_name is not None and cls:
                    cur = (cls, pending_name, m.group(1))
                    methods.setdefault(cur, [])
                    pending_name = None
                    continue
                if cur and "|" in line:
                    m = REF.search(line)
                    if m:
                        methods[cur].append(m.groups())
            proc.wait()
            os.remove(path)
    return methods, hier


def implements(hier, cls, iface, seen=None):
    seen = seen or set()
    if cls is None or cls in seen or cls not in hier:
        return False
    seen.add(cls)
    sup, ifs = hier[cls]
    return iface in ifs or any(implements(hier, i, iface, seen) for i in ifs) or implements(hier, sup, iface, seen)


def start_path_maplibre_refs(methods, hier):
    """BFS from NavApplication.onCreate through app code; returns [(path, ref)] for every org/maplibre reference."""
    by_name = collections.defaultdict(list)
    for k in methods:
        by_name[(k[1], k[2])].append(k)
    start = START
    if start not in methods:
        return None, []
    parent = {start: None}
    queue = collections.deque([start])
    hits = []
    while queue:
        k = queue.popleft()
        for op, cls, name, sig in methods.get(k, []):
            if cls.startswith("org/maplibre/"):
                hits.append((k, f"{op} {cls}" + (f".{name}{sig}" if name else "")))
                continue
            if not cls.startswith(APP_PKG):
                continue
            targets = []
            if op.startswith("invoke") and name:
                key = (cls, name, sig)
                if key in methods:
                    targets.append(key)
                # Interface / virtual dispatch: every app implementation with the same name and signature.
                if op.startswith("invoke-interface") or op.startswith("invoke-virtual") or op.startswith("invoke-super"):
                    targets += [c for c in by_name[(name, sig)] if c[0] != cls and c[0].startswith(APP_PKG)
                                and (implements(hier, c[0], cls) or hier.get(c[0], [None])[0] == cls)]
                # super.onCreate(): the Hilt base class of the same name chain.
                if op.startswith("invoke-super") and key not in methods:
                    sup = hier.get(cls, [None])[0]
                    while sup and (sup, name, sig) not in methods and sup.startswith(APP_PKG):
                        sup = hier.get(sup, [None])[0]
                    if sup and (sup, name, sig) in methods:
                        targets.append((sup, name, sig))
            if op == "new-instance" and "$$ExternalSyntheticLambda" in cls:
                targets += [m for m in methods if m[0] == cls]
            for t in targets:
                if t not in parent:
                    parent[t] = k
                    queue.append(t)
    out = []
    for k, ref in hits:
        chain, n = [], k
        while n is not None:
            chain.append(f"{n[0].split('/')[-1]}.{n[1]}")
            n = parent[n]
        out.append((" <- ".join(chain), ref))
    return True, out


# ------------------------------------------------------------------------------------------------ framework API levels
def load_api(path):
    root = ET.parse(path).getroot()
    classes = {}
    for c in root.findall("class"):
        since = int(c.get("since", "1"))
        ms = {m.get("name"): int(m.get("since", since)) for m in c.findall("method")}
        fs = {f.get("name"): int(f.get("since", since)) for f in c.findall("field")}
        sup = [e.get("name") for e in c.findall("extends")] + [e.get("name") for e in c.findall("implements")]
        classes[c.get("name")] = (since, ms, fs, sup)
    return classes


def api_since(classes, cls, key, kind, seen=None):
    seen = seen or set()
    if cls in seen or cls not in classes:
        return None
    seen.add(cls)
    since, ms, fs, sup = classes[cls]
    d = ms if kind == "m" else fs
    if key in d:
        return d[key]
    found = [x for x in (api_since(classes, s, key, kind, seen) for s in sup) if x is not None]
    return min(found) if found else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=os.path.join(ANDROID, "app/build/outputs/apk/demo/app-demo.apk"))
    a = ap.parse_args()
    rep = Report()
    print(f"APK: {a.apk}")
    if not rep.check("TC-B19-00.apk_exists", os.path.isfile(a.apk), "demo APK", a.apk):
        print(rep.summary())
        return 1
    aapt2, dexdump, zipalign = sdk_tool("aapt2"), sdk_tool("dexdump"), sdk_tool("zipalign")
    readelf = next((p for p in ("/usr/bin/readelf", "/usr/bin/llvm-readelf") if os.path.exists(p)), None)

    # ------------------------------------------------------------------------------ TC-B19-10: the crash (start path)
    with tempfile.TemporaryDirectory() as tmp:
        if dexdump:
            methods, hier = load_dex(a.apk, dexdump, tmp)
            found, refs = start_path_maplibre_refs(methods, hier)
            if found is None:
                rep.check("TC-B19-10.no_maplibre_on_application_start (AC 6)", False, "NavApplication.onCreate in dex", "not found")
            else:
                shown = "; ".join(f"{ref}  [{chain}]" for chain, ref in refs[:5])
                rep.check("TC-B19-10.no_maplibre_on_application_start (AC 6)", not refs,
                          "0 MapLibre references reachable from NavApplication.onCreate (MapLibre.getInstance not yet called)",
                          shown or "0")
        else:
            methods, hier = {}, {}
            rep.skip("TC-B19-10.no_maplibre_on_application_start (AC 6)", "dexdump not found (Android build-tools)")

        # -------------------------------------------------------------------------- TC-B19-20/21: native libraries
        with zipfile.ZipFile(a.apk) as z:
            infos = {i.filename: i for i in z.infolist()}
            abis = collections.defaultdict(set)
            for n in infos:
                m = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", n)
                if m:
                    abis[m.group(1)].add(m.group(2))
            rep.check("TC-B19-20.arm64_libs_complete (AC 1)", REQUIRED_LIBS <= abis.get("arm64-v8a", set()),
                      f"lib/arm64-v8a has {sorted(REQUIRED_LIBS)}", sorted(abis.get("arm64-v8a", set())))
            # Only ABIs a current device selects (NDK r17+); JNA's legacy armeabi/mips dirs are never preferred over these.
            partial = {abi: sorted(REQUIRED_LIBS - libs) for abi, libs in abis.items()
                       if abi in ("armeabi-v7a", "x86", "x86_64") and not REQUIRED_LIBS <= libs}
            rep.check("TC-B19-20b.no_partial_abi_dir", not partial, "every shipped ABI dir has all required libs", partial or "ok")
            if readelf:
                problems = []
                for n in sorted(x for x in infos if x.startswith("lib/arm64-v8a/") and x.endswith(".so")):
                    p = os.path.join(tmp, os.path.basename(n))
                    with open(p, "wb") as f:
                        f.write(z.read(n))
                    dyn = subprocess.run([readelf, "-dW", p], capture_output=True, text=True).stdout
                    bundled = abis.get("arm64-v8a", set())
                    for need in re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", dyn):
                        if need not in NDK_SYSTEM_LIBS and need not in bundled:
                            problems.append(f"{n}: NEEDED {need} unresolved")
                    note = subprocess.run([readelf, "-nW", p], capture_output=True, text=True).stdout
                    m = re.search(r"Android\s+0x[0-9a-f]+\s+NT_VERSION.*?\n\s+description data: ([0-9a-f]{2}) ([0-9a-f]{2})", note)
                    if m:
                        api = int(m.group(2) + m.group(1), 16)
                        if api > MIN_SDK:
                            problems.append(f"{n}: built for API {api} > minSdk {MIN_SDK}")
                    ver = subprocess.run([readelf, "-VW", p], capture_output=True, text=True).stdout
                    need_sec = ver.split("Version needs section", 1)[1] if "Version needs section" in ver else ""
                    for v in set(re.findall(r"Name: (LIBC\w*)", need_sec)):
                        if v not in ALLOWED_LIBC_VERSIONS:
                            problems.append(f"{n}: needs bionic {v} (newer than API {MIN_SDK})")
                    os.remove(p)
                rep.check("TC-B19-21.native_libs_load_on_api26_plus", not problems,
                          "NEEDED resolvable, ELF API <= minSdk, bionic versions <= LIBC_O", problems or "ok")
            else:
                rep.skip("TC-B19-21.native_libs_load_on_api26_plus", "readelf not found")

            # ---------------------------------------------------------------------- TC-B19-22: packaging
            tree = subprocess.run([aapt2, "dump", "xmltree", a.apk, "--file", "AndroidManifest.xml"],
                                  capture_output=True, text=True).stdout if aapt2 else ""
            extract_false = bool(re.search(r"extractNativeLibs\(0x010104ea\)=false", tree))
            compressed = [n for n, i in infos.items() if n.startswith("lib/") and n.endswith(".so") and i.compress_type != zipfile.ZIP_STORED]
            rep.check("TC-B19-22.stored_libs_when_not_extracted", not (extract_false and compressed),
                      "extractNativeLibs=false => every .so stored", compressed or f"extractNativeLibs={'false' if extract_false else 'true/absent'}, all stored")
        if zipalign:
            r = subprocess.run([zipalign, "-c", "-P", "16", "4", a.apk], capture_output=True, text=True)
            rep.check("TC-B19-22b.zipalign_16k", r.returncode == 0, "zipalign -c -P 16 4 OK", (r.stdout + r.stderr).strip()[-200:] or "OK")
        else:
            rep.skip("TC-B19-22b.zipalign_16k", "zipalign not found")

        # -------------------------------------------------------------------------- TC-B19-23: manifest
        if tree:
            m = re.search(r"minSdkVersion\(0x0101020c\)=(\d+)", tree)
            rep.check("TC-B19-23.min_sdk_le_28", m is not None and int(m.group(1)) <= 28, "minSdk <= 28 (Android 9)", m.group(1) if m else "absent")
            app_cls = re.search(r'android:name\(0x01010003\)="(mn\.navmn\.app\.NavApplication)"', tree)
            act = re.search(r'android:name\(0x01010003\)="(mn\.navmn\.app\.ui\.MainActivity)"', tree)
            if methods:
                ok = all(any(k[0] == c for k in methods) for c in ("mn/navmn/app/NavApplication", "mn/navmn/app/ui/MainActivity"))
                rep.check("TC-B19-23b.manifest_entry_classes_in_dex", bool(app_cls and act and ok),
                          "NavApplication and MainActivity declared and present in dex", "ok" if ok else "missing")
        else:
            rep.skip("TC-B19-23.min_sdk_le_28", "aapt2 not found")

        # -------------------------------------------------------------------------- TC-B19-24: API levels in app code
        av = api_versions()
        debuggable = bool(re.search(r"debuggable\(0x0101000f\)=true", tree))
        if debuggable:
            rep.skip("TC-B19-24.no_direct_framework_calls_above_minsdk_in_app_code",
                     "debuggable APK: D8 does not outline SDK_INT-guarded calls in debug dexing (lint NewApi covers it)")
        elif methods and av:
            classes = load_api(av)
            above = collections.defaultdict(set)
            for (cls, name, sig), refs in methods.items():
                if not cls.startswith(APP_PKG) or "$$ExternalSynthetic" in cls:
                    continue
                for op, rc, rn, rs in refs:
                    if rc not in classes:
                        continue
                    if rn is None:
                        s = classes[rc][0]
                    elif op.startswith("invoke"):
                        s = api_since(classes, rc, rn + rs, "m") or classes[rc][0]
                    else:
                        s = api_since(classes, rc, rn, "f") or classes[rc][0]
                    if s > MIN_SDK:
                        above[f"{rc}.{rn or ''} [API {s}]"].add(f"{cls.split('/')[-1]}.{name}")
            # Unguarded calls cannot be told from guarded ones in bytecode; D8 outlines guarded new-API calls into
            # $$ExternalSyntheticApiModelOutline classes, so a direct reference left in app code is the red flag.
            rep.check("TC-B19-24.no_direct_framework_calls_above_minsdk_in_app_code", not above,
                      f"0 direct refs above API {MIN_SDK} in mn/navmn (outside D8 API outlines)",
                      {k: sorted(v)[:3] for k, v in list(above.items())[:8]} or "0")
        else:
            rep.skip("TC-B19-24.no_direct_framework_calls_above_minsdk_in_app_code", "dexdump or api-versions.xml missing")

    print(rep.summary())
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
