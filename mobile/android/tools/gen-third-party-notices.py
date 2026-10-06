#!/usr/bin/env python3
"""Third-party notices and the in-app licences data (NAV-005 AC 88–98, ADR-0009 §6 and §11, ADR-0017 §7).

One generator, two outputs, both from the same rules:
  * mobile/android/THIRD_PARTY_NOTICES.md (committed; the release review reads it);
  * the licences screen's assets for one variant: `licences/index.json` plus one text file per distinct licence text
    (`licences/texts/<sha256>.txt`, stored once however many entries use it, AC 91, 97).

Inputs: the resolved runtime classpath of every variant (`debug`, `demo`, `release`), written by the Gradle task
`:app:writeLicenceDeps` as `{"debug": ["group:artifact:version", ...], ...}`; the RULES and ENTRIES below; and the
licence files in the repository (`mobile/android/licenses/`, `web/licenses/`, `web/public/fonts/OFL.txt`). Nothing is
read from the network or the Gradle cache.

    python3 tools/gen-third-party-notices.py                 # regenerate THIRD_PARTY_NOTICES.md (runs writeLicenceDeps)
    python3 tools/gen-third-party-notices.py --check --deps build/licences/deps.json
    python3 tools/gen-third-party-notices.py --deps … --variant debug --assets <dir>

`--check` (Gradle `checkThirdPartyNotices`, wired into every build and `check`, AC 96) fails (exit 1) when: a shipped
runtime artifact has no rule; an entry or part has no licence text or no copyright line; a licence is outside ALLOWED
and PENDING_AC96; a licence is GPL/AGPL; a native library's resolved version differs from NATIVE_PINS, or a bundled
native list is incomplete or not verbatim (ADR-0017 A5 §2); or the generated notices differ from the committed
THIRD_PARTY_NOTICES.md. Licences in PENDING_AC96 (awaiting the NAV-005 AC 96 amendment) pass `--check` with a PENDING line
and make `--release-gate` fail (Gradle `checkReleaseLicenceGate`, a dependency of packageRelease / bundleRelease: the
AC 98 release gate; `check`, debug and demo builds stay green).

The native lists (libmaplibre.so, libferrostar.so) are fetched with network by tools/fetch-native-licences.py and
committed; this script only reads them.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

ANDROID = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(os.path.dirname(ANDROID))
NOTICES = os.path.join(ANDROID, "THIRD_PARTY_NOTICES.md")
VARIANTS = ("debug", "demo", "release")

# AC 96 allow-list. Key = SPDX-style id used in rules; value = the name shown on screen (AC 90 table).
ALLOWED = {
    "Apache-2.0": "Apache-2.0",
    "MIT": "MIT",
    "BSD-2-Clause": "BSD-2-Clause",
    "BSD-3-Clause": "BSD-3-Clause",
    "BSL-1.0": "BSL-1.0",
    "OFL-1.1": "SIL OFL 1.1",
    "CC0-1.0": "CC0-1.0",
    "CC-BY-4.0": "CC BY 4.0",
    "ODbL-1.0": "ODbL 1.0",
    "public-domain": "Public domain",
    # Added by the PO decision of 2026-10-06 (AC 96 amendment); counsel review before the first external release.
    "ISC": "ISC",
    "Zlib": "Zlib",
    "FTL": "FreeType Project License",
    "Unicode-3.0": "Unicode License v3",
    "Unicode-DFS-2016": "Unicode License (ICU)",
    "MIT-Modern-Variant": "MIT-Modern-Variant",
    "curl": "curl",
    "MPL-2.0": "MPL-2.0",
    "Apache-2.0 WITH LLVM-exception": "Apache-2.0 WITH LLVM-exception",
    "MIT AND Apache-2.0 WITH LLVM-exception": "MIT AND Apache-2.0 WITH LLVM-exception",
}

# Licence texts: id → (repository path, upstream source). Verbatim copies; never edited by hand.
TEXTS = {
    "apache-2.0": ("mobile/android/licenses/Apache-2.0.txt", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
    "bsl-1.0": ("mobile/android/licenses/BSL-1.0.txt", "https://www.boost.org/LICENSE_1_0.txt"),
    "odbl-1.0": ("mobile/android/licenses/ODbL-1.0.txt", "https://github.com/spdx/license-list-data text/ODbL-1.0.txt"),
    "cc-by-4.0": ("mobile/android/licenses/CC-BY-4.0.txt", "https://creativecommons.org/licenses/by/4.0/legalcode.txt"),
    "cc0-1.0": ("mobile/android/licenses/CC0-1.0.txt", "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt"),
    "ferrostar": ("web/licenses/stadiamaps-ferrostar-LICENSE.txt", "https://github.com/stadiamaps/ferrostar LICENSE"),
    "osrm-openapi": ("mobile/android/licenses/osrm-openapi-LICENSE.txt", "https://github.com/stadiamaps/osrm-openapi v0.0.10 LICENSE"),
    "maplibre-native": ("mobile/android/licenses/maplibre-native-LICENSE.md", "https://github.com/maplibre/maplibre-native android-v13.6.1 LICENSE.md"),
    "maplibre-gestures": ("mobile/android/licenses/maplibre-gestures-android-LICENSE.md", "https://github.com/maplibre/maplibre-gestures-android LICENSE.md"),
    "valhalla-mobile": ("mobile/android/licenses/valhalla-mobile-LICENSE.md", "https://github.com/Rallista/valhalla-mobile 0.6.3 LICENSE.md"),
    "valhalla-models": ("mobile/android/licenses/valhalla-openapi-models-kotlin-LICENSE.md", "https://github.com/Rallista/valhalla-openapi-models-kotlin LICENSE.md"),
    "valhalla": ("mobile/android/licenses/valhalla-COPYING.txt", "https://github.com/valhalla/valhalla 3.6.3 COPYING"),
    "date": ("mobile/android/licenses/date-LICENSE.txt", "https://github.com/HowardHinnant/date LICENSE.txt"),
    "protobuf": ("mobile/android/licenses/protobuf-LICENSE.txt", "https://github.com/protocolbuffers/protobuf v25.1 LICENSE"),
    "abseil": ("mobile/android/licenses/abseil-cpp-LICENSE.txt", "https://github.com/abseil/abseil-cpp LICENSE"),
    "lz4": ("mobile/android/licenses/lz4-lib-LICENSE.txt", "https://github.com/lz4/lz4 v1.10.0 lib/LICENSE"),
    "rapidjson": ("mobile/android/licenses/rapidjson-license.txt", "https://github.com/Tencent/rapidjson license.txt"),
    "robin-hood": ("mobile/android/licenses/robin-hood-hashing-LICENSE.txt", "https://github.com/martinus/robin-hood-hashing LICENSE"),
    "unordered-dense": ("mobile/android/licenses/unordered_dense-LICENSE.txt", "https://github.com/martinus/unordered_dense LICENSE"),
    "jna": ("mobile/android/licenses/jna-LICENSE.txt", "https://github.com/java-native-access/jna 5.18.1 LICENSE"),
    "jakarta-notice": ("mobile/android/licenses/jakarta-inject-NOTICE.md", "jakarta.inject-api-2.0.1.jar META-INF/NOTICE.md"),
    "sqlite": ("mobile/android/licenses/sqlite-blessing.txt", "https://github.com/sqlite/sqlite src/sqlite.h.in header"),
    "protomaps-basemaps": ("web/licenses/protomaps-basemaps-LICENSE.md", "https://github.com/protomaps/basemaps LICENSE.md"),
    "tangrams-icons": ("web/licenses/tangrams-icons-LICENSE.md", "https://github.com/tangrams/icons LICENSE.md"),
    "ofl-noto": ("web/public/fonts/OFL.txt", "https://github.com/protomaps/basemaps-assets fonts/OFL.txt"),
}
# Texts whose own "Copyright …" lines are the copyright lines of the part (project licence files, not templates).
EXTRACT_COPYRIGHT = {"ferrostar", "osrm-openapi", "maplibre-native", "maplibre-gestures", "valhalla-mobile", "valhalla-models",
                     "valhalla", "protobuf", "lz4", "robin-hood", "unordered-dense", "tangrams-icons"}

# Licences outside the AC 96 allow-list that are shipped but not yet approved. Empty since the PO decision of 2026-10-06
# (chat "Ok", NAV-005 AC 96 amendment): ISC, Unicode (3.0 / DFS / ICU), FTL, Zlib, curl, MIT-Modern-Variant, MPL-2.0 (uniffi
# runtime, used unmodified) and Apache-2.0 WITH LLVM-exception moved into ALLOWED; counsel review before the first external
# release stays planned. The mechanism is kept: an id added here passes `--check` as pending and makes `--release-gate`
# (Gradle checkReleaseLicenceGate, a dependency of packageRelease and bundleRelease) fail (AC 98).
PENDING_AC96 = {}
SHOWN = {**ALLOWED, **PENDING_AC96}

# Native notice lists, pinned to the library version each was made for (ADR-0017 A5 §2). When the resolved artifact has
# another version the check fails: re-run the symbol scan and `tools/fetch-native-licences.py`, update the parts and the
# pin in the same commit. The MapLibre and Ferrostar list directories are named after the pinned version.
NATIVE_PINS = {
    "org.maplibre.gl:android-sdk": "13.6.1",        # libmaplibre.so: MAPLIBRE_CORE + MAPLIBRE_EXTRA
    "com.stadiamaps.ferrostar:core": "0.57.0",      # libferrostar.so: licenses/ferrostar-<version>/crates.json
    "io.github.rallista:valhalla-mobile": "0.6.3",  # libvalhalla-wrapper.so: the valhalla-mobile entry's parts
    "androidx.sqlite:sqlite-bundled": "2.5.2",      # libsqliteJni.so: SQLite 3.46.0 (ADR-0017 A4 §4)
    "net.java.dev.jna:jna": "5.18.1",               # libjnidispatch.so
}
MAPLIBRE_DIR = f"mobile/android/licenses/maplibre-native-android-v{NATIVE_PINS['org.maplibre.gl:android-sdk']}"
FERROSTAR_DIR = f"mobile/android/licenses/ferrostar-{NATIVE_PINS['com.stadiamaps.ferrostar:core']}"

# Upstream's third-party list of libmaplibre.so (LICENSES.core.md at the pinned tag): heading → (licence, copyright lines;
# None = the text's own "Copyright" lines). Licence None = not built for Android. Every upstream heading needs a rule and
# every rule an upstream heading, or the check fails (a new upstream list cannot be bundled half-read).
MAPLIBRE_CORE = {
    "Maplibre Native": ("BSD-2-Clause", ["2021 MapLibre contributors", "2018-2021 MapTiler.com", "2014-2020 Mapbox"]),
    "kdbush.hpp": ("ISC", ["2016, Vladimir Agafonkin"]),
    "supercluster.hpp": ("ISC", ["2016, Mapbox"]),
    "shelf-pack-cpp": ("ISC", ["2017, Mapbox"]),
    "geojson-vt-cpp": ("ISC", ["2015, Mapbox"]),
    "cheap-ruler-cpp": ("ISC", ["2017, Mapbox"]),
    "Boost C++ Libraries": ("BSL-1.0", ["the Boost library authors (per-file notices)"]),
    "csscolorparser": ("MIT", ["2012 Dean McNamee", "2014 Konstantin Käfer"]),
    "earcut.hpp": ("ISC", ["2015, Mapbox"]),
    "eternal": ("ISC", ["2018, Mapbox"]),
    "parsedate": ("curl", ["1996 - 2020, Daniel Stenberg, <daniel@haxx.se>, and many contributors"]),
    "polylabel": ("ISC", ["2016 Mapbox"]),
    "protozero": ("BSD-2-Clause", ["Mapbox"]),
    "unique_resource": ("BSL-1.0", ["Shintarou Okada"]),
    "vector-tile": ("ISC", ["2016, Mapbox"]),
    "wagyu": ("BSL-1.0", ["2010-2015, Angus Johnson", "2016, Mapbox"]),
    "mapbox-base": ("BSD-3-Clause", ["MapBox"]),
    "expected-lite": ("BSL-1.0", ["Martin Moene"]),
    "RapidJSON": ("MIT", ["2015 THL A29 Limited, a Tencent company, and Milo Yip"]),
    "geojson.hpp": ("ISC", ["2016, Mapbox"]),
    "geometry.hpp": ("ISC", ["2016, Mapbox"]),
    "variant": ("BSD-3-Clause", ["MapBox"]),
    "metal-cpp": (None, None),  # Apple Metal backend (MLN_WITH_METAL); the Android SDK is built with OpenGL ES
    "FreeType": ("FTL", ["1996-2002, 2006 David Turner, Robert Wilhelm, and Werner Lemberg", "The FreeType Project (www.freetype.org)"]),
    "FSST": ("MIT", ["2018-2020, CWI, TU Munich, FSU Jena"]),
    "FastPFOR": ("Apache-2.0", ["the FastPFOR authors"]),
    "SIMD Everywhere": ("MIT", ["2017 Evan Nemerson"]),
    "JSON for Modern C++": ("MIT", ["2013-2022 Niels Lohmann"]),
    "HarfBuzz": ("MIT-Modern-Variant", None),
}
# Vendored code linked into libmaplibre.so that upstream's list omits (found by the symbol scan, ADR-0017 A5 §1; linked by
# platform/android/android.cmake and the root CMakeLists.txt). (title, licence, file in MAPLIBRE_DIR/extra, copyright).
MAPLIBRE_EXTRA = [
    ("ICU {icu} (vendor/icu: bidirectional text and Arabic shaping)", "Unicode-DFS-2016", "icu-LICENSE",
     ["1991-2018 Unicode, Inc.", "1995-2016 International Business Machines Corporation and others"]),
    ("SQLite {sqlite} (vendor/sqlite: the offline tile database)", "public-domain", None,
     ["The author disclaims copyright to this source code."]),
    ("PMTiles C++ reader (vendor/PMTiles)", "BSD-3-Clause", "PMTiles-LICENSE", ["2021 Protomaps LLC"]),
    ("MapLibre Tile (MLT) decoder (vendor/maplibre-tile-spec), used under the MIT option of \"Apache-2.0 OR MIT\"", "MIT",
     "maplibre-tile-spec-LICENSE-MIT", ["2024 MapLibre contributors"]),
    ("unordered_dense (vendor/unordered_dense)", "MIT", "unordered_dense-LICENSE", None),
    ("jni.hpp (maplibre-native-base)", "ISC", "jni.hpp-LICENSE.txt", ["2016, Mapbox"]),
]


def P(title, licence, text, copyright=None, notice=None, native=False, sublist=False):
    """One licence part of an entry: a heading «title · licence», copyright lines and the full text (AC 91). `native` =
    statically linked into a shipped .so (listed in THIRD_PARTY_NOTICES.md §2). `sublist` = a part from a native library's
    third-party list: its copyright lines are shown above its own text only, not repeated in the entry's copyright block
    (with ~100 parts that block would push every licence text off the first screen)."""
    return {"title": title, "licence": licence, "text": text, "copyright": copyright, "notice": notice, "native": native,
            "sublist": sublist}


def slug(name):
    """Same rule as tools/fetch-native-licences.py: upstream heading → file name of its cut-out licence block."""
    return re.sub(r"[^A-Za-z0-9.+-]+", "-", name).strip("-")


def core_sections(text):
    """Upstream LICENSES.core.md → [(heading name, fence contents)], byte-for-byte as tools/fetch-native-licences.py cuts them."""
    out = []
    for block in re.split(r"^### ", text, flags=re.M)[1:]:
        m = re.match(r"\[([^\]]+)\]", block)
        body = re.search(r"\n```\n(.*?\n)```\n", block, re.S)
        out.append((m.group(1) if m else block.split("\n", 1)[0], body.group(1) if body else None))
    return out


def read_repo(rel, binary=False):
    with open(os.path.join(REPO, rel), "rb" if binary else "r", **({} if binary else {"encoding": "utf-8"})) as f:
        return f.read()


NATIVE_TEXT_IDS = set()  # dynamic text ids of the native lists (summarised per directory in §4, not one row each)


def native_text(tid, path, source, extract=False):
    TEXTS[tid] = (path, source)
    NATIVE_TEXT_IDS.add(tid)
    if extract:
        EXTRACT_COPYRIGHT.add(tid)
    return tid


def maplibre_native_parts():
    """Parts of the MapLibre entry for the code inside libmaplibre.so, from the committed list of the pinned version."""
    tag = "android-v" + NATIVE_PINS["org.maplibre.gl:android-sdk"]
    parts = []
    try:
        upstream = core_sections(read_repo(f"{MAPLIBRE_DIR}/LICENSES.core.md"))
        icu = read_repo(f"{MAPLIBRE_DIR}/extra/icu-version.txt").strip()
        sqlite = read_repo(f"{MAPLIBRE_DIR}/extra/sqlite-version.txt").strip()
    except OSError:
        return parts  # validate_native() reports the missing list
    for name, _ in upstream:
        licence, copyright = MAPLIBRE_CORE.get(name, (None, None))
        if licence is None:
            continue
        tid = native_text(f"maplibre-core:{slug(name)}", f"{MAPLIBRE_DIR}/core/{slug(name)}.txt",
                          f"maplibre-native {tag} LICENSES.core.md, section «{name}»", extract=copyright is None)
        parts.append(P(f"{name} (in libmaplibre.so)", licence, tid, copyright=copyright, native=True, sublist=True))
    for title, licence, file, copyright in MAPLIBRE_EXTRA:
        tid = "sqlite" if file is None else native_text(f"maplibre-extra:{file}", f"{MAPLIBRE_DIR}/extra/{file}",
                                                          f"see {MAPLIBRE_DIR}/SOURCES.json", extract=copyright is None)
        parts.append(P(title.format(icu=icu, sqlite=sqlite) + " (in libmaplibre.so)", licence, tid, copyright=copyright, native=True,
                       sublist=True))
    return parts


def ferrostar_native_parts():
    """Parts of the Ferrostar entry for the Rust crates and the Rust standard library inside libferrostar.so."""
    try:
        m = json.loads(read_repo(f"{FERROSTAR_DIR}/crates.json"))
    except OSError:
        return []
    parts, seen = [], set()
    for c in m["crates"] + m["std"]:
        if (c["name"], c["version"]) in seen:
            continue  # a crate std and Ferrostar both use in the same version (memchr, cfg-if) is listed once
        seen.add((c["name"], c["version"]))
        src = c.get("text_source", "")
        tid = native_text(f"ferrostar-crate:{c['file']}", f"{FERROSTAR_DIR}/{c['file']}",
                          src if src.startswith("https://") else f"crates.io {c['name']}-{c['version']}.crate")
        kind = "Rust standard library" if c["kind"] == "std" else "Rust crate"
        title = f"{c['name']} {c['version']} ({kind}, in libferrostar.so)"
        if c["elected"] != c["license"]:
            title += f", used under the {c['elected']} option of \"{c['license']}\""
        if c["elected"] == "MPL-2.0" and c.get("repository"):
            title += f"; source code: {c['repository']}"
        parts.append(P(title, c["elected"], tid, copyright=c["copyright"], native=True, sublist=True))
    return parts


MAPLIBRE_NATIVE_PARTS = maplibre_native_parts()
FERROSTAR_NATIVE_PARTS = ferrostar_native_parts()


# Entries of the licences screen, in screen order (UX spec L5, P3). `match` assigns runtime artifacts (group, artifact)
# to an entry; `primary` gives the version shown on the row; `requires` = shown only when that artifact is shipped.
ENTRIES = [
    {"id": "ferrostar", "name": "Ferrostar", "section": "software", "primary": "com.stadiamaps.ferrostar:core",
     "match": [("com.stadiamaps.ferrostar", "*"), ("com.stadiamaps", "osrm-openapi")],
     "parts": [P("Ferrostar core, libferrostar.so", "BSD-3-Clause", "ferrostar", native=True),
               P("osrm-openapi", "BSD-3-Clause", "osrm-openapi"),
               *FERROSTAR_NATIVE_PARTS]},
    {"id": "maplibre", "name": "MapLibre Native", "section": "software", "primary": "org.maplibre.gl:android-sdk",
     "match": [("org.maplibre.gl", "*")],
     "overrides": {("org.maplibre.gl", "android-sdk-geojson"): "Apache-2.0", ("org.maplibre.gl", "android-sdk-turf"): "Apache-2.0"},
     "parts": [P("MapLibre Native android-sdk, libmaplibre.so", "BSD-2-Clause", "maplibre-native", native=True),
               P("maplibre-android-gestures", "BSD-2-Clause", "maplibre-gestures"),
               P("MapLibre Java android-sdk-geojson, android-sdk-turf", "Apache-2.0", "apache-2.0", copyright=["MapLibre"]),
               *MAPLIBRE_NATIVE_PARTS]},
    {"id": "valhalla-mobile", "name": "valhalla-mobile", "section": "software", "primary": "io.github.rallista:valhalla-mobile",
     "match": [("io.github.rallista", "*")],
     "parts": [P("valhalla-mobile, libvalhalla-wrapper.so", "MIT", "valhalla-mobile", native=True),
               P("Valhalla 3.6.3", "MIT", "valhalla", native=True),
               P("date (Valhalla's bundled date library)", "MIT", "date", copyright=["2015, 2016, 2017 Howard Hinnant"], native=True),
               P("protobuf 4.25.1", "BSD-3-Clause", "protobuf", native=True),
               P("Abseil", "Apache-2.0", "abseil", copyright=["The Abseil Authors"], native=True),
               P("Boost algorithm, foreach, format, geometry, heap, optional, property_tree, range, tokenizer", "BSL-1.0", "bsl-1.0",
                 copyright=["the Boost library authors (per-file notices)"], native=True),
               P("lz4", "BSD-2-Clause", "lz4", native=True),
               P("RapidJSON", "MIT", "rapidjson", copyright=["2015 THL A29 Limited, a Tencent company, and Milo Yip"], native=True),
               P("robin-hood-hashing", "MIT", "robin-hood", native=True),
               P("unordered_dense", "MIT", "unordered-dense", native=True),
               P("valhalla-models, valhalla-models-config", "MIT", "valhalla-models")]},
    {"id": "sqlite", "name": "SQLite", "section": "software", "primary": "androidx.sqlite:sqlite-bundled",
     "requires": "androidx.sqlite:sqlite-bundled", "match": [],
     "parts": [P("SQLite, bundled in androidx.sqlite:sqlite-bundled (libsqliteJni.so)", "public-domain", "sqlite",
                 copyright=["The author disclaims copyright to this source code."], native=True)]},
    {"id": "protomaps-basemaps", "name": "Protomaps Basemaps (map style)", "section": "software", "version": "@protomaps/basemaps",
     "match": [],
     "parts": [P("@protomaps/basemaps style code", "BSD-3-Clause", "protomaps-basemaps", copyright=["2019-2023 Protomaps LLC, Kelso Cartography"]),
               P("Basemap visual design", "CC0-1.0", "cc0-1.0",
                 copyright=["The visual design of the Protomaps basemap styles was created by Geraldine Sarmiento for Protomaps LLC "
                            "and released under a Creative Commons 0 (CC0) license."])]},
    {"id": "jna", "name": "JNA (Java Native Access)", "section": "software", "primary": "net.java.dev.jna:jna",
     "match": [("net.java.dev.jna", "*")],
     "parts": [P("JNA, used under the Apache-2.0 option of \"Apache-2.0 OR LGPL-2.1-or-later\"", "Apache-2.0", "jna",
                 copyright=["Timothy Wall", "Matthias Bläsing"]),
               P("Apache License 2.0", "Apache-2.0", "apache-2.0", copyright=["Timothy Wall", "Matthias Bläsing"])]},
    {"id": "kotlin", "name": "Kotlin and kotlinx", "section": "software",
     "match": [("org.jetbrains.kotlin", "*"), ("org.jetbrains.kotlinx", "*"), ("org.jetbrains", "annotations")],
     "parts": [P("Kotlin, kotlinx, JetBrains annotations", "Apache-2.0", "apache-2.0",
                 copyright=["JetBrains s.r.o. and Kotlin Programming Language contributors"])]},
    {"id": "androidx", "name": "AndroidX and Jetpack Compose", "section": "software",
     "match": [("androidx.*", "*")],
     "overrides": {("androidx.datastore", "datastore-preferences-external-protobuf"): "BSD-3-Clause"},
     "parts": [P("AndroidX", "Apache-2.0", "apache-2.0", copyright=["The Android Open Source Project"]),
               P("datastore-preferences-external-protobuf (protobuf)", "BSD-3-Clause", "protobuf")]},
    {"id": "dagger", "name": "Dagger and Hilt", "section": "software",
     "match": [("com.google.dagger", "*"), ("javax.inject", "javax.inject"), ("jakarta.inject", "jakarta.inject-api")],
     "parts": [P("Dagger, Hilt", "Apache-2.0", "apache-2.0", copyright=["The Dagger Authors"]),
               P("javax.inject", "Apache-2.0", "apache-2.0", copyright=["2009 The JSR-330 Expert Group"]),
               P("jakarta.inject-api", "Apache-2.0", "apache-2.0",
                 copyright=["All content is the property of the respective authors or their employers."], notice="jakarta-notice")]},
    {"id": "square", "name": "OkHttp, Okio, Moshi", "section": "software",
     "match": [("com.squareup.okhttp3", "*"), ("com.squareup.okio", "*"), ("com.squareup.moshi", "*")],
     "parts": [P("OkHttp, Okio, Moshi", "Apache-2.0", "apache-2.0", copyright=["Square, Inc."])]},
    {"id": "small-libraries", "name": "Timber, Gson, JSR-305, JSpecify, ListenableFuture", "section": "software",
     "match": [("com.jakewharton.timber", "*"), ("com.google.code.gson", "*"), ("com.google.code.findbugs", "jsr305"),
               ("org.jspecify", "*"), ("com.google.guava", "listenablefuture")],
     "parts": [P("Timber", "Apache-2.0", "apache-2.0", copyright=["Jake Wharton"]),
               P("Gson", "Apache-2.0", "apache-2.0", copyright=["Google Inc."]),
               P("JSR-305 annotations (FindBugs-jsr305)", "Apache-2.0", "apache-2.0", copyright=["FindBugs-jsr305 project"]),
               P("JSpecify", "Apache-2.0", "apache-2.0", copyright=["The JSpecify Authors"]),
               P("Guava ListenableFuture", "Apache-2.0", "apache-2.0", copyright=["The Guava Authors"])]},
    {"id": "noto-sans", "name": "Noto Sans (map label glyphs)", "section": "fonts", "version": "basemaps-assets",
     "match": [],
     # The OFL file's own first line (its other "Copyright Holder" lines are licence definitions, not holders).
     "parts": [P("Noto Sans Regular, Medium, Italic", "OFL-1.1", "ofl-noto", copyright=["2022 The Noto Project Authors (https://github.com/notofonts)"])]},
    {"id": "map-icons", "name": "Map icons (sprites)", "section": "fonts", "version": "basemaps-assets",
     "match": [],
     "parts": [P("tangrams/icons", "MIT", "tangrams-icons"),
               P("Protomaps sprite design", "CC0-1.0", "cc0-1.0",
                 copyright=["The visual design of the Protomaps basemap styles was created by Geraldine Sarmiento for Protomaps LLC "
                            "and released under a Creative Commons 0 (CC0) license."])]},
]

# The two notice texts of the map-data block (AC 92): OpenStreetMap (ODbL 1.0) and ESA WorldCover (CC BY 4.0). The
# credit lines themselves are the app's string resources (attribution_osm, attribution_esa), shown with these texts.
DATA_TEXTS = {"odbl": "odbl-1.0", "ccby": "cc-by-4.0"}
DATA_NAMES = {"odbl": ("OpenStreetMap", "ODbL-1.0"), "ccby": ("ESA WorldCover", "CC-BY-4.0")}

COPYRIGHT_LINE = re.compile(r"^\s*#*\s*Copyright\s*(?:\(c\)|\(C\)|©)?\s*(.+?)\s*$")


def read_text(text_id):
    path = os.path.join(REPO, TEXTS[text_id][0])
    with open(path, "rb") as f:
        return f.read()


def extracted_copyright(text_id):
    out = []
    for line in read_text(text_id).decode("utf-8").splitlines():
        m = COPYRIGHT_LINE.match(line)
        if not m or "[" in m.group(1) or "{" in m.group(1):
            continue
        holder = re.sub(r"\s+All rights reserved\.?$", "", m.group(1)).strip()
        if holder and holder not in out:
            out.append(holder)
    return out


def asset_versions():
    with open(os.path.join(REPO, "web/package.json")) as f:
        basemaps = json.load(f)["dependencies"]["@protomaps/basemaps"].lstrip("^~")
    with open(os.path.join(REPO, "web/public/fonts/BASEMAPS_ASSETS_COMMIT")) as f:
        commit = f.read().strip()
    return {"@protomaps/basemaps": basemaps, "basemaps-assets": "basemaps-assets " + commit[:7]}


def glob_match(pattern, value):
    return pattern == "*" or pattern == value or (pattern.endswith(".*") and (value == pattern[:-2] or value.startswith(pattern[:-1])))


def entry_for(group, artifact):
    for e in ENTRIES:
        for g, a in e["match"]:
            if glob_match(g, group) and glob_match(a, artifact):
                return e
    return None


def licence_for(entry, group, artifact):
    override = entry.get("overrides", {}).get((group, artifact))
    if override:
        return override
    return entry["parts"][0]["licence"]


class Problems(Exception):
    pass


def resolve(deps):
    """{variant: [coordinate]} → (rows, problems). rows: (group, artifact, version, licence, entry id, variants)."""
    problems = []
    seen = {}
    for variant in VARIANTS:
        for coord in deps.get(variant, []):
            g, a, v = coord.split(":")
            seen.setdefault((g, a, v), set()).add(variant)
    rows = []
    for (g, a, v), vs in sorted(seen.items()):
        e = entry_for(g, a)
        if e is None:
            problems.append(f"no licence rule for shipped runtime artifact {g}:{a}:{v} (add it to ENTRIES in tools/gen-third-party-notices.py)")
            continue
        lic = licence_for(e, g, a)
        rows.append((g, a, v, lic, e["id"], tuple(x for x in VARIANTS if x in vs)))
    return rows, problems


def validate_native(rows):
    """ADR-0017 A5 §2: each native notice list matches the version of the library it describes, and the bundled lists are
    complete copies of their upstream sources."""
    problems = []
    shipped = {}
    for g, a, v, _, _, _ in rows:
        shipped.setdefault(f"{g}:{a}", set()).add(v)
    for coord, pin in NATIVE_PINS.items():
        for v in sorted(shipped.get(coord, ())):
            if v != pin:
                problems.append(f"native licence list of {coord} is pinned to {pin} but {v} is shipped: re-run the symbol scan and "
                                f"tools/fetch-native-licences.py, update the parts and NATIVE_PINS in the same commit (ADR-0017 A5 §2)")
    try:
        upstream = core_sections(read_repo(f"{MAPLIBRE_DIR}/LICENSES.core.md"))
    except OSError:
        problems.append(f"native licence list {MAPLIBRE_DIR}/LICENSES.core.md missing (run tools/fetch-native-licences.py maplibre)")
        upstream = []
    names = [n for n, _ in upstream]
    for name, body in upstream:
        if name not in MAPLIBRE_CORE:
            problems.append(f"upstream libmaplibre.so component «{name}» has no rule in MAPLIBRE_CORE")
        elif MAPLIBRE_CORE[name][0] is not None:
            path = os.path.join(REPO, MAPLIBRE_DIR, "core", slug(name) + ".txt")
            if body is None or not os.path.isfile(path) or read_repo(os.path.relpath(path, REPO)) != body:
                problems.append(f"{os.path.relpath(path, REPO)} is not the verbatim «{name}» block of LICENSES.core.md")
    if upstream:
        for name in MAPLIBRE_CORE:
            if name not in names:
                problems.append(f"MAPLIBRE_CORE rule «{name}» is not in the upstream LICENSES.core.md")
    try:
        m = json.loads(read_repo(f"{FERROSTAR_DIR}/crates.json"))
        if m.get("ferrostar") != NATIVE_PINS["com.stadiamaps.ferrostar:core"]:
            problems.append(f"{FERROSTAR_DIR}/crates.json was made for Ferrostar {m.get('ferrostar')}")
    except OSError:
        problems.append(f"native licence list {FERROSTAR_DIR}/crates.json missing (run tools/fetch-native-licences.py ferrostar)")
    return problems


def pending_licences():
    """Licence ids of the entries' parts that wait for the NAV-005 AC 96 amendment (PENDING_AC96), sorted."""
    return sorted({p["licence"] for e in ENTRIES for p in e["parts"] if p["licence"] in PENDING_AC96})


def release_gate(variant, pending):
    if variant == "release" and pending:
        return [f"release packaging blocked: licences outside the NAV-005 AC 96 allow-list are shipped ({', '.join(pending)}); "
                f"the BA/PO amendment of AC 96 must approve them first (ADR-0017 A5 §1, AC 98)"]
    return []


def validate_entries():
    problems = []
    for e in ENTRIES:
        if not e["parts"]:
            problems.append(f"entry {e['id']} has no licence text")
        for p in e["parts"]:
            if p["licence"] not in SHOWN:
                problems.append(f"entry {e['id']}: licence {p['licence']} is not in the allow-list")
            if re.search(r"\bA?GPL\b", p["licence"]) and "LGPL" not in p["licence"]:
                problems.append(f"entry {e['id']}: GPL licence {p['licence']}")
            if p["text"] not in TEXTS or not os.path.isfile(os.path.join(REPO, TEXTS[p["text"]][0])):
                problems.append(f"entry {e['id']} part {p['title']}: licence text {p['text']} missing")
                continue
            if not part_copyright(p):
                problems.append(f"entry {e['id']} part {p['title']}: no copyright line")
            if p["notice"] and not os.path.isfile(os.path.join(REPO, TEXTS[p["notice"]][0])):
                problems.append(f"entry {e['id']} part {p['title']}: NOTICE {p['notice']} missing")
    for t in DATA_TEXTS.values():
        if not os.path.isfile(os.path.join(REPO, TEXTS[t][0])):
            problems.append(f"data licence text {t} missing")
    return problems


def part_copyright(p):
    lines = p["copyright"] if p["copyright"] is not None else (extracted_copyright(p["text"]) if p["text"] in EXTRACT_COPYRIGHT else [])
    return ["© " + h if not h.startswith("The author disclaims") and not h.startswith("The visual design") and not h.startswith("All content") else h
            for h in lines]


def shown_entries(rows, variant):
    """The entries of one variant's screen with their artifacts (0 entries for things the variant does not ship, AC 90)."""
    shipped = {(g, a): (v, lic, eid) for g, a, v, lic, eid, vs in rows if variant in vs}
    versions = asset_versions()
    out = []
    for e in ENTRIES:
        artifacts = sorted(((g, a, v, lic) for (g, a), (v, lic, eid) in shipped.items() if eid == e["id"]))
        if e["match"] and not artifacts:
            continue
        req = e.get("requires")
        if req and tuple(req.split(":")) not in shipped:
            continue
        if "primary" in e:
            g, a = e["primary"].split(":")
            version = shipped.get((g, a), (None,))[0]
        elif "version" in e:
            version = versions[e["version"]]
        else:
            version = None  # families with many versions show the licence name only (UX P3)
        parts = list(e["parts"])
        if e["id"] == "androidx" and not any(lic == "BSD-3-Clause" for _, _, _, lic in artifacts):
            parts = parts[:1]
        out.append((e, version, parts, artifacts))
    return out


def notices_markdown(rows):
    versions = asset_versions()
    lines = [
        "# Third-party notices (mobile/android/)",
        "",
        "Generated by `tools/gen-third-party-notices.py` (NAV-005 AC 88–98, ADR-0009 §6, §11, ADR-0017 §7). Do not edit by hand:",
        "the Gradle check `checkThirdPartyNotices` (part of every build and of `check`) fails when this file differs from the",
        "generated one, when a shipped runtime artifact has no rule, when a licence is outside the allow-list and not pending (§2),",
        "or when a native library's version differs from the version its notice list was made for (§2). The same data",
        "is the in-app licences screen («Тохиргоо» → «Лиценз»), with the full licence texts from `mobile/android/licenses/`,",
        "`web/licenses/` and `web/public/fonts/OFL.txt` bundled as APK assets (offline). Build and test tools (Gradle, AGP,",
        "Kotlin compiler, KSP, Robolectric, JUnit, MockWebServer, the host Ferrostar build) are not shipped and are not listed.",
        "No GPL or AGPL component.",
        "",
        "## 1. Bundled assets",
        "",
        "| Asset | Path in the APK | Source | Licence |",
        "|---|---|---|---|",
        f"| Noto Sans Regular / Medium / Italic glyph PBFs | `assets/fonts/<fontstack>/<range>.pbf` | copied at build time from `web/public/fonts/` ({versions['basemaps-assets']}) | SIL OFL 1.1 (`assets/fonts/OFL.txt`) |",
        f"| Map icon sprites `light`, `dark` | `assets/sprites/v4/` | copied at build time from `web/public/sprites/v4/` ({versions['basemaps-assets']}) | MIT (tangrams/icons) and CC0-1.0 (Protomaps design) |",
        f"| Basemap style JSON (day, night) | `assets/style/basemap-*.json` | generated by `web/scripts/export-native-style.mjs` from `@protomaps/basemaps` {versions['@protomaps/basemaps']} + `docs/design/tokens.json` | BSD-3-Clause code, CC0-1.0 visual design |",
        "| Licence texts and index of the licences screen | `assets/licences/` | generated by this script from the files listed in §4 | the licences of the texts themselves |",
        "| Manoeuvre and control icons | `res/drawable/ic_*.xml` | the project's own paths (same artwork as `web/src/ui/icons.ts`, `routeIcons.ts`) | project-owned, no third-party artwork |",
        "| Chime | generated in code (`voice/VoiceSelection.kt` ChimePcm) | sine tones, no samples | project-owned |",
        "",
        "Map data: tiles come from the gateway or, with the offline pack (NAV-022), from the installed pack files",
        "(© OpenStreetMap contributors, ODbL 1.0; low-zoom landcover © ESA WorldCover, CC BY 4.0). Both credits are shown on",
        "the map screens (AC 2) and in the licences screen's map-data block with the bundled ODbL 1.0 and CC BY 4.0 texts (AC 92).",
        "",
        "## 2. Native code bundled inside runtime libraries",
        "",
        "| Licences-screen entry | Part | Licence | Licence text |",
        "|---|---|---|---|",
    ]
    for e in ENTRIES:
        for p in e["parts"]:
            if p["native"]:
                lines.append(f"| {e['name']} | {p['title']} | {SHOWN[p['licence']]} | `{TEXTS[p['text']][0]}` |")
    pending = pending_licences()
    lines += [
        "",
        "Each native list is pinned to the library version it was made for (ADR-0017 A5 §2); a different resolved version fails",
        "`checkThirdPartyNotices`: " + ", ".join(f"`{c}` {v}" for c, v in NATIVE_PINS.items()) + ".",
        "",
        f"`libmaplibre.so` (MapLibre Native android-v{NATIVE_PINS['org.maplibre.gl:android-sdk']}): upstream's third-party list",
        f"`LICENSES.core.md` at that tag is bundled verbatim in `{MAPLIBRE_DIR}/`, one part per component (`core/`, cut",
        "out verbatim; `metal-cpp` is Apple-only and not built for Android). The symbol scan of the shipped library also found",
        "vendored code that upstream's list omits (ICU, SQLite, PMTiles, MLT, unordered_dense, jni.hpp); their licence files are in",
        "`extra/` (sources in `SOURCES.json`). The system `libz`, `libEGL`, `libGLESv3`, `libandroid`, `libjnigraphics`, `liblog`,",
        "`libm`, `libdl` and `libc` are not bundled.",
        "",
        f"`libferrostar.so` (Ferrostar {NATIVE_PINS['com.stadiamaps.ferrostar:core']}): the Rust crates of `cargo tree --target",
        "aarch64-linux-android -e normal,no-proc-macro` of the published crate and its `Cargo.lock`, plus the Rust standard library of",
        f"the rustc that built the AAR, with their verbatim licence files in `{FERROSTAR_DIR}/` (`crates.json`, made by",
        "`tools/fetch-native-licences.py`). Crates offered under \"A OR B\" are used under the MIT option where offered, else Apache-2.0.",
        "",
    ]
    if pending:
        lines += [
            "**Licences awaiting the NAV-005 AC 96 amendment** (outside the allow-list, none GPL; ADR-0017 A5 §1): "
            + ", ".join(f"{PENDING_AC96[x]}" for x in pending) + ".",
            "They are listed and their texts are bundled, `checkThirdPartyNotices` reports them as pending, and",
            "`checkReleaseLicenceGate` (a dependency of `packageRelease` and `bundleRelease`) fails until the BA/PO amend AC 96",
            "(AC 98 release gate). Debug and demo builds and `check` are not blocked.",
            "",
        ]
    lines += [
        "`valhalla-mobile` (NAV-021, ADR-0017 §2, §7) bundles `libvalhalla-wrapper.so` for arm64-v8a, armeabi-v7a, x86 and x86_64,",
        "loaded only in the `:routing` process; the statically linked libraries above follow its `src/vcpkg.json` at tag 0.6.3",
        "(commit b47ad5a) and a symbol scan of the shipped library. The system `libz`, `liblog`, `libm`, `libdl` and `libc` are not",
        "bundled. JNA (`net.java.dev.jna:jna`) is dual-licensed Apache-2.0 OR LGPL-2.1-or-later and is used under **Apache-2.0**",
        "(ADR-0009 §11).",
        "",
        "## 3. Runtime libraries (resolved runtime classpath per variant)",
        "",
        "| Group | Artifact | Version | Licence | Licences-screen entry | Variants |",
        "|---|---|---|---|---|---|",
    ]
    for g, a, v, lic, eid, vs in rows:
        lines.append(f"| `{g}` | `{a}` | {v} | {ALLOWED[lic]} | {eid} | {', '.join(vs)} |")
    lines += ["", "## 4. Licence texts (verbatim copies)", "", "| Text | File | Upstream |", "|---|---|---|"]
    for tid, (path, src) in TEXTS.items():
        if tid not in NATIVE_TEXT_IDS:
            lines.append(f"| {tid} | `{path}` | {src} |")
    for prefix, d, src in (("maplibre-", MAPLIBRE_DIR, f"maplibre-native android-v{NATIVE_PINS['org.maplibre.gl:android-sdk']} LICENSES.core.md and `SOURCES.json`"),
                           ("ferrostar-", FERROSTAR_DIR, "the .crate archives (SHA-256 = Cargo.lock) or the crate's repository, see `crates.json`")):
        n = len({TEXTS[t][0] for t in NATIVE_TEXT_IDS if t.startswith(prefix)})
        lines.append(f"| {prefix}native ({n} files) | `{d}/` | {src} |")
    return "\n".join(lines) + "\n"


def write_assets(rows, variant, out_dir):
    texts_dir = os.path.join(out_dir, "licences", "texts")
    os.makedirs(texts_dir, exist_ok=True)
    stored = {}

    def store(text_id):
        data = read_text(text_id)
        sha = hashlib.sha256(data).hexdigest()
        rel = f"licences/texts/{sha[:16]}.txt"
        if rel not in stored:
            with open(os.path.join(out_dir, rel), "wb") as f:
                f.write(data)
            stored[rel] = sha
        return rel

    sections = {"software": [], "fonts": []}
    for e, version, parts, artifacts in shown_entries(rows, variant):
        copyright_lines = []
        jparts = []
        for p in parts:
            c = part_copyright(p)
            if not p["sublist"]:
                copyright_lines += [x for x in c if x not in copyright_lines]
            jparts.append({"title": p["title"], "licence": SHOWN[p["licence"]], "copyright": c, "text": store(p["text"]),
                           "notice": store(p["notice"]) if p["notice"] else None})
        licences = []
        for p in parts:
            if SHOWN[p["licence"]] not in licences:
                licences.append(SHOWN[p["licence"]])
        sections[e["section"]].append({
            "id": e["id"], "name": e["name"], "version": version, "licences": licences, "copyright": copyright_lines,
            "parts": jparts,
            "artifacts": [{"coordinate": f"{g}:{a}", "version": v, "licence": ALLOWED[lic]} for g, a, v, lic in artifacts],
        })
    index = {
        "schema": 1,
        "variant": variant,
        "data": {k: {"name": DATA_NAMES[k][0], "licence": ALLOWED[DATA_NAMES[k][1]], "text": store(t)} for k, t in DATA_TEXTS.items()},
        "software": sections["software"],
        "fonts": sections["fonts"],
        "texts": stored,
    }
    with open(os.path.join(out_dir, "licences", "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1, sort_keys=False)
        f.write("\n")
    return index


def gradle_deps():
    subprocess.run(["./gradlew", "-q", ":app:writeLicenceDeps", "-Pnav.hostFerrostar=off"], cwd=ANDROID, check=True)
    return os.path.join(ANDROID, "app/build/licences/deps.json")


def self_test():
    """AC 96: a fixture library with no licence mapping makes the check fail; a GPL rule is refused."""
    ok = True
    rows, problems = resolve({"debug": ["com.example.unmapped:lib:1.0", "androidx.core:core:1.18.0"]})
    if not any("com.example.unmapped:lib:1.0" in p for p in problems):
        print("FAIL  self_test.unmapped_artifact_fails")
        ok = False
    else:
        print("PASS  self_test.unmapped_artifact_fails")
    ENTRIES.append({"id": "gpl-fixture", "name": "x", "section": "software", "match": [("com.example.gpl", "*")],
                    "parts": [P("x", "GPL-3.0-only", "apache-2.0", copyright=["x"])]})
    try:
        problems = validate_entries()
    finally:
        ENTRIES.pop()
    if any("GPL-3.0-only" in p for p in problems):
        print("PASS  self_test.licence_outside_allow_list_fails")
    else:
        print("FAIL  self_test.licence_outside_allow_list_fails")
        ok = False

    def case(name, passed):
        nonlocal ok
        print(("PASS  " if passed else "FAIL  ") + "self_test." + name)
        ok = ok and passed

    # ADR-0017 A5 §2: a native library bump without a new notice list fails.
    pin = NATIVE_PINS["com.stadiamaps.ferrostar:core"]
    rows, _ = resolve({"debug": [f"com.stadiamaps.ferrostar:core:{pin}.1"]})
    case("native_version_bump_without_list_fails", any("pinned to " + pin in p for p in validate_native(rows)))
    rows, _ = resolve({"debug": [f"com.stadiamaps.ferrostar:core:{pin}"]})
    case("native_pinned_version_passes", not any("pinned to" in p for p in validate_native(rows)))
    # An upstream MapLibre component without a rule fails (the upstream list is bundled completely or not at all).
    saved = MAPLIBRE_CORE.pop("HarfBuzz")
    try:
        case("upstream_component_without_rule_fails", any("«HarfBuzz» has no rule" in p for p in validate_native([])))
    finally:
        MAPLIBRE_CORE["HarfBuzz"] = saved
    # Pending licences pass the check but block the release variant's assets (AC 98).
    pending = pending_licences()
    case("pending_licences_block_release_assets", bool(release_gate("release", ["ISC"])) and not release_gate("debug", ["ISC"])
         and (bool(release_gate("release", pending)) == bool(pending)))
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--deps", help="JSON {variant: [group:artifact:version]} (default: run :app:writeLicenceDeps)")
    ap.add_argument("--check", action="store_true", help="fail if THIRD_PARTY_NOTICES.md is stale or a rule is missing")
    ap.add_argument("--variant", choices=VARIANTS, help="with --assets: the variant whose screen data is written")
    ap.add_argument("--assets", help="output directory for licences/index.json and licences/texts/")
    ap.add_argument("--release-gate", action="store_true",
                    help="fail while a shipped licence waits for the NAV-005 AC 96 amendment (Gradle checkReleaseLicenceGate, AC 98)")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return 0 if self_test() else 1
    with open(a.deps or gradle_deps()) as f:
        deps = json.load(f)
    rows, problems = resolve(deps)
    problems += validate_native(rows)
    problems += validate_entries()
    pending = pending_licences()
    if a.release_gate:
        problems += release_gate("release", pending)
    if problems:
        print("THIRD-PARTY NOTICES CHECK FAILED (NAV-005 AC 96):", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1
    if a.release_gate:
        print("release licence gate open: every shipped licence is on the NAV-005 AC 96 allow-list")
        return 0
    md = notices_markdown(rows)
    if a.assets:
        if not a.variant:
            ap.error("--assets needs --variant")
        index = write_assets(rows, a.variant, a.assets)
        n = sum(len(e["artifacts"]) for e in index["software"])
        print(f"{a.variant}: {len(index['software'])} software and {len(index['fonts'])} font/icon entries, {n} artifacts, "
              f"{len(index['texts'])} distinct texts")
        return 0
    if a.check:
        with open(NOTICES, encoding="utf-8") as f:
            committed = f.read()
        if committed != md:
            print("THIRD-PARTY NOTICES CHECK FAILED (NAV-005 AC 96): mobile/android/THIRD_PARTY_NOTICES.md differs from the "
                  "generated notices. Regenerate: cd mobile/android && python3 tools/gen-third-party-notices.py", file=sys.stderr)
            import difflib
            for line in list(difflib.unified_diff(committed.splitlines(), md.splitlines(), "committed", "generated", lineterm=""))[:60]:
                print("  " + line, file=sys.stderr)
            return 1
        if pending:
            print(f"PENDING (NAV-005 AC 96 amendment, release assets blocked): {', '.join(pending)}")
        print(f"THIRD_PARTY_NOTICES.md up to date: {len(rows)} runtime artifacts, {len(ENTRIES)} entries, "
              + (f"{len(pending)} licences pending the AC 96 amendment (release blocked)" if pending else "all licences allowed"))
        return 0
    with open(NOTICES, "w", encoding="utf-8") as f:
        f.write(md)
    print(f"wrote {NOTICES}: {len(rows)} runtime artifacts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
