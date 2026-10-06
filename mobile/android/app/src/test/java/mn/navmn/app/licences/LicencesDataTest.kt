package mn.navmn.app.licences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * NAV-005 section P, the generated data of the debug APK (the merged debug assets Robolectric sees):
 *  - AC 90: 100 % of the THIRD_PARTY_NOTICES.md artifacts shipped in debug are on the screen, nothing else; the AC 90
 *    table's licence names; 0 GPL / AGPL; 0 entries without a licence name or text;
 *  - AC 91: every entry has a copyright line; every text asset is byte-identical to a repository licence file and to
 *    the SHA-256 the index names; each distinct text is stored once; valhalla-mobile and OFL lines;
 *  - AC 96: the generator refuses a library without a licence rule (a fixture), a stale notices file, and a native library
 *    whose version differs from its pinned notice list (ADR-0017 A5 §2);
 *  - AC 90 / A5 §1: the code statically linked into libmaplibre.so and libferrostar.so is listed; licences beyond the
 *    original allow-list were approved by the 2026-10-06 AC 96 amendment, so the release gate is open;
 *  - AC 97: the licence assets are ≤ 0.5 MB compressed.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LicencesDataTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val assets = LicenceAssets { app.assets.open(it) }
    private val index: LicenceIndex = assets.index() ?: throw AssertionError("licences/index.json missing from the debug assets")
    private val repo = TestStrings.repoRoot
    private val generator = File(repo, "mobile/android/tools/gen-third-party-notices.py")

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun entries() = index.software + index.fonts

    private fun noticesTable() = File(repo, "mobile/android/THIRD_PARTY_NOTICES.md").readLines()
        .filter { it.startsWith("| `") }
        .map { row -> row.split('|').map { it.trim().trim('`') } }
        .filter { it.size >= 7 && it[1].contains('.') && !it[1].contains('/') }

    /** (group:artifact) → variants column of the committed notices file, §3. */
    private fun noticesRows(): Map<String, String> = noticesTable().associate { "${it[1]}:${it[2]}" to it[6] }

    /** (group:artifact) → version column of the committed notices file, §3. */
    private fun noticesVersions(): Map<String, String> = noticesTable().associate { "${it[1]}:${it[2]}" to it[3] }

    /** Licence names added to the allow-list by the 2026-10-06 AC 96 amendment (generator ALLOWED, A5 §1); native parts only. */
    private val pending = setOf("ISC", "Zlib", "FreeType Project License", "Unicode License (ICU)", "MIT-Modern-Variant", "curl", "MPL-2.0",
        "MIT AND Apache-2.0 WITH LLVM-exception")

    @Test
    fun everyShippedArtifactOfTheDebugVariantIsOnTheScreenAndNothingElse() {
        assertEquals("debug", index.variant)
        val expected = noticesRows().filterValues { "debug" in it }.keys
        val onScreen = entries().flatMap { e -> e.artifacts.map { it.coordinate } }
        assertEquals("one line per artifact", onScreen.size, onScreen.toSet().size)
        assertEquals(expected, onScreen.toSet())
        assertTrue("181 runtime artifacts in debug (AC 90 count, notices file)", expected.size >= 150)
        // The debug-only test manifest is listed for debug and for no release variant.
        val testManifest = noticesRows().getValue("androidx.compose.ui:ui-test-manifest")
        assertEquals("debug", testManifest)
    }

    @Test
    fun theAc90TableLicenceNamesAndNoGpl() {
        fun e(id: String) = index.entry(id) ?: throw AssertionError("entry $id missing")
        // AC 90 table: the component's own licence comes first; the parts of the code linked into its .so follow (A5 §1).
        assertEquals("BSD-3-Clause", e("ferrostar").licences.first())
        assertEquals(listOf("BSD-3-Clause", "BSD-3-Clause"), e("ferrostar").parts.take(2).map { it.licence })
        assertEquals(listOf("BSD-2-Clause", "Apache-2.0"), e("maplibre").licences.take(2))
        assertTrue(e("maplibre").artifacts.filter { it.coordinate.endsWith("android-sdk") || it.coordinate.endsWith("gestures") }.all { it.licence == "BSD-2-Clause" })
        assertTrue(e("maplibre").artifacts.filter { it.coordinate.contains("geojson") || it.coordinate.contains("turf") }.all { it.licence == "Apache-2.0" })
        val vm = e("valhalla-mobile")
        assertEquals(listOf("MIT", "BSD-3-Clause", "Apache-2.0", "BSL-1.0", "BSD-2-Clause"), vm.licences)
        for (name in listOf("Valhalla 3.6.3", "date", "protobuf 4.25.1", "Abseil", "Boost", "lz4", "RapidJSON", "robin-hood-hashing", "unordered_dense", "valhalla-models")) {
            assertTrue(name, vm.parts.any { it.title.startsWith(name) })
        }
        assertEquals(listOf("Apache-2.0"), e("jna").licences)
        assertTrue(e("jna").parts.first().title.contains("Apache-2.0 OR LGPL-2.1-or-later"))
        assertEquals(listOf("Public domain"), e("sqlite").licences)
        assertEquals(listOf("BSD-3-Clause", "CC0-1.0"), e("protomaps-basemaps").licences)
        assertEquals(listOf("SIL OFL 1.1"), e("noto-sans").licences)
        assertEquals(listOf("MIT", "CC0-1.0"), e("map-icons").licences)
        assertTrue(e("androidx").artifacts.single { it.coordinate == "androidx.datastore:datastore-preferences-external-protobuf" }.licence == "BSD-3-Clause")
        assertEquals("ODbL 1.0", index.data.odbl.licence)
        assertEquals("CC BY 4.0", index.data.ccby.licence)
        val allowed = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "BSL-1.0", "SIL OFL 1.1", "CC0-1.0", "CC BY 4.0", "ODbL 1.0", "Public domain")
        for (en in entries()) {
            assertTrue(en.id, en.licences.isNotEmpty() && en.licences.all { it in allowed || it in pending })
            assertTrue(en.id, en.parts.isNotEmpty() && en.parts.all { it.licence in allowed || it.licence in pending })
            // Pending licences occur only in the parts of code linked into native libraries (MapLibre, Ferrostar).
            assertTrue(en.id, en.parts.none { it.licence in pending } || en.id in setOf("ferrostar", "maplibre"))
            assertTrue(en.id, en.artifacts.all { it.licence in allowed })
            assertTrue(en.id, en.licences.none { Regex("\\bA?GPL\\b").containsMatchIn(it) })
        }
        // Screen order (UX P3): Ferrostar, MapLibre, valhalla-mobile, SQLite, … ; then the fonts and icons.
        assertEquals(listOf("ferrostar", "maplibre", "valhalla-mobile", "sqlite", "protomaps-basemaps", "jna", "kotlin", "androidx", "dagger", "square", "small-libraries"), index.software.map { it.id })
        assertEquals(listOf("noto-sans", "map-icons"), index.fonts.map { it.id })
    }

    @Test
    fun copyrightLinesAndByteIdenticalTextsStoredOnce() {
        for (en in entries()) {
            assertTrue("${en.id} has a copyright line", en.copyright.isNotEmpty() && en.copyright.all { it.isNotBlank() })
            for (p in en.parts) assertTrue("${en.id} / ${p.title}", p.copyright.isNotEmpty())
        }
        val vm = index.entry("valhalla-mobile")!!.copyright
        assertTrue(vm.contains("© 2024 Adventure Consortium Inc (dba Rallista)"))
        assertTrue(vm.contains("© 2018 Valhalla contributors"))
        assertEquals("© 2022 The Noto Project Authors (https://github.com/notofonts)", index.entry("noto-sans")!!.copyright.single())
        // Every licence file of the repository, by hash.
        val repoFiles = (File(repo, "mobile/android/licenses").walkTopDown().toList() + File(repo, "web/licenses").listFiles()!!.toList() +
            File(repo, "web/public/fonts/OFL.txt")).filter { it.isFile }.associateBy { sha256(it.readBytes()) }
        val referenced = (entries().flatMap { e -> e.parts.flatMap { listOfNotNull(it.text, it.notice) } } + listOf(index.data.odbl.text, index.data.ccby.text)).toSet()
        assertEquals("each referenced text is in the index exactly once", referenced, index.texts.keys)
        assertEquals("each distinct text is stored once", index.texts.size, index.texts.values.toSet().size)
        for ((path, sha) in index.texts) {
            val bytes = app.assets.open(path).use { it.readBytes() }
            assertEquals(path, sha, sha256(bytes))
            assertNotNull("$path is byte-identical to a repository licence file", repoFiles[sha])
        }
        assertEquals("ODbL-1.0.txt", repoFiles.getValue(index.texts.getValue(index.data.odbl.text)).name)
        assertEquals("CC-BY-4.0.txt", repoFiles.getValue(index.texts.getValue(index.data.ccby.text)).name)
        // Apache-2.0 once, not once per AndroidX artifact.
        val apache = sha256(File(repo, "mobile/android/licenses/Apache-2.0.txt").readBytes())
        assertEquals(1, index.texts.values.count { it == apache })
    }

    /** ADR-0017 A5 §1 (review finding): the third-party code inside libmaplibre.so and libferrostar.so is listed. */
    @Test
    fun codeLinkedIntoTheNativeLibrariesIsListed() {
        val maplibre = index.entry("maplibre")!!.parts
        fun ml(prefix: String) = maplibre.singleOrNull { it.title.startsWith(prefix) && it.title.contains("libmaplibre.so") }
            ?: throw AssertionError("MapLibre part $prefix missing: ${maplibre.map { it.title }}")
        assertEquals("Unicode License (ICU)", ml("ICU 61.1").licence)
        assertEquals("FreeType Project License", ml("FreeType").licence)
        assertEquals("MIT-Modern-Variant", ml("HarfBuzz").licence)
        assertEquals("Public domain", ml("SQLite 3.45.3").licence)
        assertEquals("BSD-2-Clause", ml("protozero").licence)
        assertEquals("ISC", ml("supercluster.hpp").licence)
        for (p in listOf("kdbush.hpp", "Boost C++ Libraries", "earcut.hpp", "wagyu", "RapidJSON", "PMTiles", "MapLibre Tile (MLT)", "unordered_dense", "jni.hpp")) ml(p)
        assertTrue("metal-cpp is Apple-only", maplibre.none { it.title.startsWith("metal-cpp") })

        val ferrostar = index.entry("ferrostar")!!.parts
        fun fs(crate: String) = ferrostar.singleOrNull { it.title.startsWith("$crate ") && it.title.contains("libferrostar.so") }
            ?: throw AssertionError("Ferrostar part $crate missing")
        for (c in listOf("geo", "geographiclib-rs", "serde", "serde_json", "chrono", "uuid", "rand", "regex-automata", "anyhow",
            "android_logger", "log", "bytes", "chacha20", "addr2line", "gimli", "miniz_oxide", "rustc-demangle")) {
            assertTrue(c, fs(c).licence == "MIT")
        }
        assertEquals("MPL-2.0", fs("uniffi_core").licence)
        assertTrue(fs("uniffi_core").title.contains("source code: https://github.com/mozilla/uniffi-rs"))
        assertTrue(ferrostar.any { it.title.startsWith("Rust standard library") && it.licence == "MIT" })
        // The entry's own copyright block stays short; each listed part shows its lines above its own text (UX P4: the first
        // licence text stays reachable without scrolling past ~100 holders).
        assertTrue(index.entry("ferrostar")!!.copyright.size <= 10)
        assertTrue(index.entry("maplibre")!!.copyright.size <= 10)
        // Each part has a copyright line and a text (AC 91), also for crates whose licence file names no holder.
        for (p in maplibre + ferrostar) assertTrue(p.title, p.copyright.isNotEmpty() && p.copyright.all { it.isNotBlank() && it != "© " })
    }

    @Test
    fun licenceAssetsAreAtMostHalfAMegabyteCompressed() {
        val raw = index.texts.keys.sumOf { app.assets.open(it).use { s -> s.readBytes().size } } + app.assets.open(LicenceAssets.INDEX).use { it.readBytes().size }
        val packed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION)).use { z ->
                (index.texts.keys + LicenceAssets.INDEX).forEach { p -> app.assets.open(p).use { it.copyTo(z) } }
            }
        }.size()
        assertTrue("compressed $packed bytes (raw $raw)", packed <= 512 * 1024)
    }

    @Test
    fun paragraphsJoinWrappedLinesAndKeepClauses() {
        val p = LicenceAssets.paragraphs("Permission is hereby granted,\nfree of charge.\n\n1. Redistributions of source\n   code must retain\n\n\nEND")
        assertEquals(listOf("Permission is hereby granted, free of charge.", "1. Redistributions of source\n   code must retain", "END"), p)
    }

    private fun python(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("python3", generator.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("generator finished", p.waitFor(120, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    /** AC 96: a fixture library with no licence mapping fails the check, and so does a notices file that drifted. */
    @Test
    fun generatorFailsOnAnUnmappedLibraryAndOnDrift() {
        val dir = java.nio.file.Files.createTempDirectory("licences").toFile()
        try {
            val notices = noticesRows()
            val versions = noticesVersions()
            val pinned = setOf("org.maplibre.gl:android-sdk", "com.stadiamaps.ferrostar:core", "io.github.rallista:valhalla-mobile",
                "androidx.sqlite:sqlite-bundled", "net.java.dev.jna:jna")
            val debug = notices.filterValues { "debug" in it }.keys
            fun deps(extra: List<String>, bump: String? = null) = File(dir, "deps-${extra.size}-$bump.json").apply {
                // Versions do not matter for the rule check (except the native libraries pinned to their notice lists); the
                // drift check compares the whole file, so it must fail too.
                fun v(c: String) = if (c == bump) versions.getValue(c) + ".1" else if (c in pinned) versions.getValue(c) else "1"
                writeText("{\"debug\": [" + (debug.map { "\"$it:${v(it)}\"" } + extra.map { "\"$it\"" }).joinToString(",") + "], \"demo\": [], \"release\": []}")
            }
            val (code, out) = python("--check", "--deps", deps(listOf("com.example.unmapped:fixture-lib:1.0")).path)
            assertNotEquals(0, code)
            assertTrue(out, out.contains("no licence rule for shipped runtime artifact com.example.unmapped:fixture-lib:1.0"))
            val (code2, out2) = python("--check", "--deps", deps(emptyList()).path)
            assertNotEquals("versions changed → THIRD_PARTY_NOTICES.md drifted", 0, code2)
            assertTrue(out2, out2.contains("differs from the generated notices"))
            val (code3, out3) = python("--self-test")
            assertEquals(out3, 0, code3)
            assertTrue(out3, out3.contains("PASS  self_test.release_gate_fails_on_licence_outside_allow_list"))
            // ADR-0017 A5 §2: a Ferrostar bump without a new native notice list fails the check.
            val (code4, out4) = python("--check", "--deps", deps(emptyList(), bump = "com.stadiamaps.ferrostar:core").path)
            assertNotEquals(0, code4)
            assertTrue(out4, out4.contains("native licence list of com.stadiamaps.ferrostar:core is pinned to ${versions.getValue("com.stadiamaps.ferrostar:core")}"))
            // AC 98: since the AC 96 amendment of 2026-10-06 no shipped licence is pending, so the release gate is open.
            val (code5, out5) = python("--release-gate", "--deps", deps(emptyList()).path)
            assertEquals(out5, 0, code5)
            assertTrue(out5, out5.contains("release licence gate open"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
