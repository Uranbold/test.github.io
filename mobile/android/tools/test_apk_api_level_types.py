#!/usr/bin/env python3
"""Self-test of tools/apk_api_level_types.py (NAV-005 AC 100; bug B-NAV012-01).

    python3 mobile/android/tools/test_apk_api_level_types.py

Builds two tiny APK-shaped zips (classes.dex only) from the Java fixtures in tools/fixtures/apk-api-level/ with the
SDK's android.jar, javac and d8, then runs the checker on them:
  (i)   fail/  the B-NAV012-01 shape (an API 31 type as a field, parameter, local and cast outside a holder)
        → exit 1, and the output names android/media/AudioManager$OnModeChangedListener, [API 31] and the rules;
  (ii)  pass/  the same code in a `...Api31` holder → exit 0 with RESULT: PASS;
  (iii) the checker without its tools (no dexdump, no api-versions.xml) → exit 2, never a pass or a skip.
The real debug APK is checked by the Gradle task checkApkApiLevelTypesDebug (AC 100 (iii)).

Every missing tool (javac, d8, android.jar) is a failure (exit 2), never a skip (AC 99). Writes only to a temporary
directory. Exit 0 only if every case behaves as stated.
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "apk_api_level_types.py")
FIXTURES = os.path.join(HERE, "fixtures", "apk-api-level")
TYPE = "android/media/AudioManager$OnModeChangedListener"


def sdk_root():
    for r in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/opt/android-sdk"):
        if r and os.path.isdir(r):
            return r
    return None


def newest(pattern):
    hits = sorted(glob.glob(pattern))
    return hits[-1] if hits else None


def build_apk(src_dir, out_dir, javac, d8, android_jar):
    classes = os.path.join(out_dir, "classes")
    dex = os.path.join(out_dir, "dex")
    os.makedirs(classes)
    os.makedirs(dex)
    sources = sorted(glob.glob(os.path.join(src_dir, "**", "*.java"), recursive=True))
    subprocess.run([javac, "--release", "17", "-g", "-classpath", android_jar, "-d", classes] + sources, check=True)
    class_files = sorted(glob.glob(os.path.join(classes, "**", "*.class"), recursive=True))
    subprocess.run([d8, "--debug", "--min-api", "26", "--lib", android_jar, "--output", dex] + class_files, check=True)
    apk = os.path.join(out_dir, "fixture.apk")
    with zipfile.ZipFile(apk, "w") as z:
        z.write(os.path.join(dex, "classes.dex"), "classes.dex")
    return apk


def run_checker(apk, env=None, extra=()):
    p = subprocess.run([sys.executable, CHECKER, "--apk", apk, "--min-sdk", "26", *extra],
                       capture_output=True, text=True, env=env)
    return p.returncode, p.stdout + p.stderr


def main():
    sdk = sdk_root()
    javac = shutil.which("javac") or (os.path.join(os.environ["JAVA_HOME"], "bin", "javac") if os.environ.get("JAVA_HOME") else None)
    d8 = newest(os.path.join(sdk, "build-tools", "*", "d8")) if sdk else None
    android_jar = newest(os.path.join(sdk, "platforms", "android-*", "android.jar")) if sdk else None
    if not (javac and os.path.exists(javac) and d8 and android_jar):
        print(f"FAIL  tools\n        expected: javac, d8 and android.jar | actual: javac={javac} d8={d8} android.jar={android_jar}")
        return 2
    results = []

    def case(name, ok, detail):
        results.append(ok)
        print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else "\n        " + detail.replace("\n", "\n        ")))

    with tempfile.TemporaryDirectory() as tmp:
        bad = build_apk(os.path.join(FIXTURES, "fail"), os.path.join(tmp, "fail"), javac, d8, android_jar)
        good = build_apk(os.path.join(FIXTURES, "pass"), os.path.join(tmp, "pass"), javac, d8, android_jar)

        code, out = run_checker(bad)
        case("AC100.i.b_nav012_01_shape_exits_1", code == 1, f"exit {code}\n{out}")
        case("AC100.i.names_type_and_level", f"{TYPE} [API 31]" in out, out)
        for rule in ("T3.field_type", "T3.method_signature", "T1.check-cast", "T4.local_variable"):
            case(f"AC100.i.names_rule_{rule}", rule in out, out)
        case("AC100.i.result_fail", "RESULT: FAIL" in out, out)

        code, out = run_checker(good)
        case("AC100.ii.holder_api31_exits_0", code == 0 and "RESULT: PASS" in out, f"exit {code}\n{out}")

        # (iii) the checker's own tools missing (an empty SDK root and an api-versions.xml that does not exist): exit 2,
        # never a pass and never a skip. (/opt/android-sdk is the checker's last dexdump fallback, so the missing
        # api-versions.xml is what makes this case fail on a machine that has that SDK.)
        empty = os.path.join(tmp, "empty-sdk")
        os.makedirs(empty)
        env = dict(os.environ, ANDROID_HOME=empty, ANDROID_SDK_ROOT=empty)
        code, out = run_checker(good, env=env, extra=("--api-versions", os.path.join(empty, "api-versions.xml")))
        case("AC99.missing_tools_exit_2", code == 2 and "FAIL  tools" in out and "RESULT: PASS" not in out, f"exit {code}\n{out}")

    ok = all(results)
    print("RESULT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
