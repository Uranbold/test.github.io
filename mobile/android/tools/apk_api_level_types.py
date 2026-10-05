#!/usr/bin/env python3
"""APK check: no framework type above minSdk is materialised in app code that can run below its API level.

    python3 mobile/android/tools/apk_api_level_types.py --apk <app.apk> [--apk <other.apk> ...]

Bug B-NAV012-01 (PO crash, Redmi Note 8 Pro, Android 11 / API 30, demo 0.1.0-nav005-demo, thread navmn-guidance):
    java.lang.NoClassDefFoundError: Failed resolution of: Landroid/media/AudioManager$OnModeChangedListener;
        at mn.navmn.app.lockscreen.LockScreenGate$$ExternalSyntheticApiModelOutline0.m
        at mn.navmn.app.audio.calls.AudioModeCallSignals$modes$1.invokeSuspend(CallSignals.kt:56)
An `if (SDK_INT >= 31) … else null` result typed `OnModeChangedListener?` was spilled into the coroutine; on resume
D8's check-cast (outlined into an ApiModelOutline class) resolves the type even for null, and the class does not exist
below API 31. An SDK_INT guard around a *call* is not enough: the *type* must not appear in shared code at all.

Rule (ADR-0013 §6.1 code note; mobile/android/README.md "API levels"): a framework class (android.*, java.*, … as
listed in the SDK's api-versions.xml) introduced after minSdk may only appear inside an API holder: a class whose
top-level simple name ends in `Api<N>` (e.g. `ModeListenerApi31`, annotated `@RequiresApi(N)`, every caller guarded,
which lint NewApi enforces) with N >= the type's API level, including that class's nested and D8-synthesised classes
(`ModeListenerApi31$$ExternalSyntheticLambda0`). Outside holders, app code (mn/navmn/**) must not contain, for such a
type:
  T1  check-cast / instance-of / const-class / new-instance / new-array / filled-new-array;
  T2  a call to a D8 `$$ExternalSyntheticApiModelOutline` method whose body does T1 (the crash's exact shape);
  T3  a field of that type, a method parameter or return type, or an invoked method / accessed field whose descriptor
      names it (a value of the type is then held in a register of shared code);
  T4  a local variable of that type (debug info; app builds are not minified);
  T5  a class (lambda, anonymous or named) that extends or implements it.
D8 API outline classes are neutral (D8 names them after an arbitrary context class); their bodies count at the call
site (T2). Calls to newer *methods* on old types are not flagged: D8 outlines those and lint NewApi checks the guards.

Read-only: never builds, installs or contacts anything. Exit 0 only if every APK passes. Python 3.9+ stdlib plus the
Android SDK's dexdump (build-tools) and platforms/android-*/data/api-versions.xml.
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

APP_PKG = "mn/navmn/"
DEFAULT_MIN_SDK = 26
OUTLINE = "$$ExternalSyntheticApiModelOutline"
HOLDER = re.compile(r"Api(\d+)$")

CLASS_HDR = re.compile(r"^\s+Class descriptor\s+: 'L([^;']+);'")
SUPER = re.compile(r"^\s+Superclass\s+: 'L([^;']+);'")
IFACE = re.compile(r"^\s+#\d+\s+: 'L([^;']+);'")
MEMBER_HDR = re.compile(r"^\s+#\d+\s+: \(in L[^;]+;\)")
NAME = re.compile(r"^\s+name\s+: '([^']+)'")
TYPE = re.compile(r"^\s+type\s+: '([^']+)'")
TYPE_OP = re.compile(r"\|[0-9a-f]{4}: (check-cast|instance-of|const-class|new-instance|new-array|filled-new-array(?:/range)?) .*?, \[*L([^;]+);")
INVOKE = re.compile(r"\|[0-9a-f]{4}: (invoke-\S+) \{[^}]*\}, L([^;]+);\.([^:]+):(\([^)]*\)\S+)")
FIELD_OP = re.compile(r"\|[0-9a-f]{4}: ([si](?:get|put)\S*) [^L]*L([^;]+);\.([^:]+):(\S+)")
LOCAL = re.compile(r"^\s+0x[0-9a-f]+ - 0x[0-9a-f]+ reg=\d+ \S+ \[*L([^;]+);")
DESC_TYPES = re.compile(r"L([^;]+);")


def sdk_root_candidates():
    return [r for r in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/opt/android-sdk") if r]


def find_dexdump():
    hits = sorted(p for r in sdk_root_candidates() for p in glob.glob(os.path.join(r, "build-tools", "*", "dexdump")))
    return hits[-1] if hits else None


def find_aapt2():
    hits = sorted(p for r in sdk_root_candidates() for p in glob.glob(os.path.join(r, "build-tools", "*", "aapt2")))
    return hits[-1] if hits else None


def find_api_versions():
    hits = sorted(p for r in sdk_root_candidates()
                  for p in glob.glob(os.path.join(r, "platforms", "android-*", "data", "api-versions.xml")))
    return hits[-1] if hits else None


def load_class_levels(path):
    """framework class (internal name) -> API level it was added in."""
    root = ET.parse(path).getroot()
    return {c.get("name"): int(c.get("since", "1")) for c in root.findall("class")}


def min_sdk_of(apk, aapt2):
    if not aapt2:
        return None
    tree = subprocess.run([aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                          capture_output=True, text=True).stdout
    m = re.search(r"minSdkVersion\(0x0101020c\)=(\d+)", tree)
    return int(m.group(1)) if m else None


class Method:
    __slots__ = ("cls", "name", "desc", "type_ops", "invokes", "fields", "locals")

    def __init__(self, cls, name, desc):
        self.cls, self.name, self.desc = cls, name, desc
        self.type_ops, self.invokes, self.fields, self.locals = [], [], [], []


def load_dex(apk, dexdump, workdir):
    """Returns (classes, methods): classes[cls] = {'super', 'ifaces', 'fields': [(name, type)]}; methods[(cls, name, desc)]."""
    classes, methods = {}, {}
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        for d in dexes:
            path = os.path.join(workdir, d)
            with open(path, "wb") as f:
                f.write(z.read(d))
            proc = subprocess.Popen([dexdump, "-d", path], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    text=True, errors="replace")
            cls = None
            section = None  # ifaces | sfields | ifields | methods
            pending = None
            cur = None
            in_locals = False
            for line in proc.stdout:
                m = CLASS_HDR.match(line)
                if m:
                    cls = m.group(1)
                    classes[cls] = {"super": None, "ifaces": [], "fields": []}
                    section, pending, cur, in_locals = None, None, None, False
                    continue
                if cls is None:
                    continue
                if line.startswith("  Superclass"):
                    m = SUPER.match(line)
                    classes[cls]["super"] = m.group(1) if m else None
                    continue
                if line.startswith("  Interfaces"):
                    section = "ifaces"
                    continue
                if line.startswith("  Static fields"):
                    section = "fields"
                    continue
                if line.startswith("  Instance fields"):
                    section = "fields"
                    continue
                if line.startswith("  Direct methods") or line.startswith("  Virtual methods"):
                    section, cur, in_locals = "methods", None, False
                    continue
                if section == "ifaces":
                    m = IFACE.match(line)
                    if m and "(in L" not in line:
                        classes[cls]["ifaces"].append(m.group(1))
                    continue
                if MEMBER_HDR.match(line):
                    pending, cur, in_locals = None, None, False
                    continue
                m = NAME.match(line)
                if m and cur is None:
                    pending = m.group(1)
                    continue
                m = TYPE.match(line)
                if m and pending is not None:
                    if section == "fields":
                        classes[cls]["fields"].append((pending, m.group(1)))
                    elif section == "methods":
                        cur = Method(cls, pending, m.group(1))
                        methods[(cls, pending, m.group(1))] = cur
                    pending = None
                    continue
                if cur is None:
                    continue
                if "|" in line:
                    m = TYPE_OP.search(line)
                    if m:
                        cur.type_ops.append((m.group(1), m.group(2)))
                        continue
                    m = INVOKE.search(line)
                    if m:
                        cur.invokes.append((m.group(1), m.group(2), m.group(3), m.group(4)))
                        continue
                    m = FIELD_OP.search(line)
                    if m:
                        cur.fields.append((m.group(1), m.group(2), m.group(3), m.group(4)))
                    continue
                if line.strip().startswith("locals"):
                    in_locals = True
                    continue
                if in_locals:
                    m = LOCAL.match(line)
                    if m:
                        cur.locals.append(m.group(1))
                    elif not line.startswith("        0x"):
                        in_locals = False
            proc.wait()
            os.remove(path)
    return classes, methods


def holder_level(cls):
    """API level N if cls belongs to an `…Api<N>` holder (top-level simple name), else None."""
    top = cls.split("$", 1)[0].rsplit("/", 1)[-1]
    m = HOLDER.search(top)
    return int(m.group(1)) if m else None


def violations(classes, methods, levels, min_sdk):
    """[(rule, location, type, level)] for every framework type > min_sdk outside a sufficient holder."""
    def level(t):
        lv = levels.get(t)
        return lv if lv is not None and lv > min_sdk else None

    def allowed(cls, lv):
        h = holder_level(cls)
        return h is not None and h >= lv

    outline_types = {}
    for k, m in methods.items():
        if OUTLINE in m.cls:
            outline_types[k] = [(op, t) for op, t in m.type_ops if level(t)]
    out = []

    def flag(rule, cls, where, t):
        lv = level(t)
        if lv and not allowed(cls, lv):
            out.append((rule, where, t, lv))

    for cls, info in classes.items():
        if not cls.startswith(APP_PKG) or OUTLINE in cls:
            continue
        for t in [info["super"]] + info["ifaces"]:
            if t:
                flag("T5.extends_or_implements", cls, cls, t)
        for fname, ftype in info["fields"]:
            for t in DESC_TYPES.findall(ftype):
                flag("T3.field_type", cls, f"{cls}.{fname}", t)
    for (cls, name, desc), m in methods.items():
        if not cls.startswith(APP_PKG) or OUTLINE in cls:
            continue
        where = f"{cls}.{name}"
        for t in DESC_TYPES.findall(desc):
            flag("T3.method_signature", cls, where, t)
        for op, t in m.type_ops:
            flag(f"T1.{op}", cls, where, t)
        for op, rc, rn, rd in m.invokes:
            for t in DESC_TYPES.findall(rd):
                flag("T3.invoke_descriptor", cls, f"{where} -> {rc.rsplit('/', 1)[-1]}.{rn}", t)
            if OUTLINE in rc:
                for oop, t in outline_types.get((rc, rn, rd), []):
                    flag(f"T2.outlined_{oop}", cls, f"{where} -> {rc.rsplit('/', 1)[-1]}.{rn}", t)
        for op, fc, fn, ft in m.fields:
            for t in DESC_TYPES.findall(ft):
                flag("T3.field_access", cls, f"{where} -> {fc.rsplit('/', 1)[-1]}.{fn}", t)
        for t in m.locals:
            flag("T4.local_variable", cls, where, t)
    return out


def check_apk(apk, dexdump, aapt2, levels, min_sdk_arg):
    print(f"APK: {apk}")
    if not os.path.isfile(apk):
        print(f"FAIL  apk_exists\n        expected: an APK file | actual: {apk} missing")
        return False
    min_sdk = min_sdk_arg or min_sdk_of(apk, aapt2) or DEFAULT_MIN_SDK
    with tempfile.TemporaryDirectory() as tmp:
        classes, methods = load_dex(apk, dexdump, tmp)
    app_classes = sum(1 for c in classes if c.startswith(APP_PKG))
    holders = sorted({c.split("$", 1)[0] for c in classes if c.startswith(APP_PKG) and holder_level(c) and OUTLINE not in c})
    print(f"INFO  minSdk={min_sdk}; app classes={app_classes}; API holders={[h.rsplit('/', 1)[-1] for h in holders]}")
    if app_classes == 0:
        print("FAIL  app_code_present\n        expected: mn/navmn classes in dex | actual: 0")
        return False
    found = violations(classes, methods, levels, min_sdk)
    by_type = collections.defaultdict(list)
    for rule, where, t, lv in found:
        by_type[(t, lv)].append(f"{rule} {where}")
    if not found:
        print(f"PASS  B-NAV012-01.no_framework_types_above_minsdk_outside_api_holders [0 of {len(levels)} framework classes]")
        return True
    print(f"FAIL  B-NAV012-01.no_framework_types_above_minsdk_outside_api_holders")
    print(f"        expected: 0 framework types with API > {min_sdk} outside `…Api<N>` holder classes")
    print(f"        actual:   {len(found)} use(s) of {len(by_type)} type(s):")
    for (t, lv), uses in sorted(by_type.items()):
        print(f"          {t} [API {lv}]")
        for u in sorted(set(uses))[:40]:
            print(f"            {u}")
        if len(set(uses)) > 40:
            print(f"            … {len(set(uses)) - 40} more")
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--apk", action="append", required=True, help="APK to check (repeatable)")
    ap.add_argument("--min-sdk", type=int, default=None, help="default: the APK manifest's minSdkVersion, else 26")
    ap.add_argument("--api-versions", default=None, help="default: the newest SDK platforms/*/data/api-versions.xml")
    a = ap.parse_args()
    dexdump, aapt2 = find_dexdump(), find_aapt2()
    api = a.api_versions or find_api_versions()
    if not dexdump or not api:
        print(f"FAIL  tools\n        expected: dexdump and api-versions.xml | actual: dexdump={dexdump} api-versions={api}")
        return 2
    levels = load_class_levels(api)
    ok = all([check_apk(p, dexdump, aapt2, levels, a.min_sdk) for p in a.apk])
    print("RESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
