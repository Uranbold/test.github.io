package mn.navmn.app.voice.pack

import mn.navmn.app.BuildConfig
import mn.navmn.app.support.TestStrings
import navmn.buildlogic.VoicePackGuard
import navmn.buildlogic.VoicePackGuard.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * NAV-016 M5 (AC 20, 45, 46, 50, 52; AC 6 for mobile/): the Gradle-side pack check navmn.buildlogic.VoicePackGuard (the
 * same source file bundleVoicePack<Variant> runs) and the repository scans.
 */
class VoicePackGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sha = BuildConfig.VOICE_INVENTORY_SHA256

    private fun pack(name: String, built: TestPacks.Built = TestPacks.build()): File = built.writeTo(File(tmp.root, name))

    private fun problem(value: String?, policy: Policy): String? = VoicePackGuard.check(value, policy, sha).problem

    @Test
    fun variantPolicies() {
        assertEquals(Policy.OPTIONAL, VoicePackGuard.policyFor("debug"))
        assertEquals(Policy.REQUIRED_REAL_OR_NONE, VoicePackGuard.policyFor("demo"))
        assertEquals(Policy.REQUIRED_REAL, VoicePackGuard.policyFor("release"))
        assertEquals("OPTIONAL", BuildConfig.VOICE_PACK_POLICY)
        // debug: optional
        assertEquals(emptyList<String>(), VoicePackGuard.check(null, Policy.OPTIONAL, sha).files)
        assertEquals(emptyList<String>(), VoicePackGuard.check("none", Policy.OPTIONAL, sha).files)
        val stub = pack("stub")
        assertEquals("a stub pack in debug: manifest + 304 clips", 305, VoicePackGuard.check(stub.absolutePath, Policy.OPTIONAL, sha).files.size)
        // demo: required and real, unless exactly "none"
        assertTrue(problem(null, Policy.REQUIRED_REAL_OR_NONE)!!.contains("nav.voicePackDir"))
        assertEquals(emptyList<String>(), VoicePackGuard.check("none", Policy.REQUIRED_REAL_OR_NONE, sha).files)
        assertTrue(problem(stub.absolutePath, Policy.REQUIRED_REAL_OR_NONE)!!.let { it.contains("nav.voicePackDir") && it.contains("V5 STUB_NOT_ALLOWED") })
        val real = pack("real", TestPacks.build(vendor = TestPacks.REAL_VENDOR))
        assertNull(problem(real.absolutePath, Policy.REQUIRED_REAL_OR_NONE))
        // release: required and real; "none" refused
        assertTrue(problem("none", Policy.REQUIRED_REAL)!!.contains("nav.voicePackDir"))
        assertTrue(problem(null, Policy.REQUIRED_REAL)!!.contains("nav.voicePackDir"))
        assertNull(problem(real.absolutePath, Policy.REQUIRED_REAL))
        val male = pack("male", TestPacks.build(vendor = TestPacks.REAL_VENDOR, voiceId = "MALE4v2"))
        assertNull("AC 45: a MALE4v2 pack builds the same way", problem(male.absolutePath, Policy.REQUIRED_REAL))
        val other = pack("other", TestPacks.build(vendor = TestPacks.REAL_VENDOR, voiceId = "FEMALE1"))
        assertTrue("AC 46", problem(other.absolutePath, Policy.REQUIRED_REAL)!!.contains("V6 VOICE_NOT_ALLOWED"))
    }

    @Test
    fun failuresNameThePropertyAndTheFirstFailedCheck() {
        val missing = File(tmp.root, "missing")
        assertTrue(problem(missing.absolutePath, Policy.OPTIONAL)!!.let { it.contains("nav.voicePackDir") && it.contains("not a directory") })
        assertTrue(problem("relative/pack", Policy.OPTIONAL)!!.contains("absolute"))
        val empty = File(tmp.root, "empty").apply { mkdirs() }
        assertTrue(problem(empty.absolutePath, Policy.OPTIONAL)!!.contains("V1 NO_PACK"))
        val real = TestPacks.build(vendor = TestPacks.REAL_VENDOR)
        // a bad hash (one clip byte changed)
        val bad = pack("bad", real)
        val clip = File(bad, real.files.keys.first())
        clip.writeBytes(clip.readBytes().also { it[0] = (it[0] + 1).toByte() })
        assertTrue(problem(bad.absolutePath, Policy.REQUIRED_REAL)!!.let { it.contains("nav.voicePackDir") && it.contains("V10 FILE_HASH_MISMATCH") })
        // a deleted clip
        val gone = pack("gone", real)
        File(gone, real.files.keys.last()).delete()
        assertTrue(problem(gone.absolutePath, Policy.REQUIRED_REAL)!!.contains("V9 FILE_MISSING"))
        // a truncated manifest, schemaVersion 99
        val trunc = pack("trunc", real)
        File(trunc, "manifest.json").writeText(real.manifest.take(100))
        assertTrue(problem(trunc.absolutePath, Policy.REQUIRED_REAL)!!.contains("V2 MANIFEST_UNREADABLE"))
        assertTrue(problem(pack("v99", TestPacks.build(vendor = TestPacks.REAL_VENDOR, schemaVersion = "99")).absolutePath, Policy.REQUIRED_REAL)!!.contains("V2 SCHEMA_UNSUPPORTED"))
        // another inventory: fails demo/release (AC 20, 46); debug bundles it with a warning (the app falls back)
        val stale = pack("stale", TestPacks.build(vendor = TestPacks.REAL_VENDOR, inventorySha256 = "0".repeat(64)))
        assertTrue(problem(stale.absolutePath, Policy.REQUIRED_REAL)!!.contains("V7 INVENTORY_MISMATCH"))
        val debug = VoicePackGuard.check(stale.absolutePath, Policy.OPTIONAL, sha)
        assertNull(debug.problem)
        assertNotNull(debug.warning)
        val otherInventory = VoiceInventory(String(mn.navmn.app.support.TestVoice.bytes, Charsets.UTF_8).replace("дараа нь", "дараа ни").toByteArray(Charsets.UTF_8))
        val older = pack("older", TestPacks.build(vendor = TestPacks.REAL_VENDOR, inventory = otherInventory))
        assertTrue("AC 5: a pack from older wording", problem(older.absolutePath, Policy.REQUIRED_REAL_OR_NONE)!!.contains("differs from the app's inventory"))
        assertEquals("AC 46: debug bundles it; the app falls back", 305, VoicePackGuard.check(older.absolutePath, Policy.OPTIONAL, sha).files.size)
        val compiledOther = VoicePackGuard.check(pack("ok", real).absolutePath, Policy.REQUIRED_REAL, "f".repeat(64)).problem
        assertTrue(compiledOther!!.contains("differs from the app's inventory"))
    }

    @Test
    fun onlyTheManifestAndTheListedClipsAreBundled() {
        val dir = pack("extra", TestPacks.build(vendor = TestPacks.REAL_VENDOR))
        File(dir, "notes.txt").writeText("x")
        File(dir, "clips/unlisted.aac").writeBytes(byteArrayOf(1))
        val files = VoicePackGuard.check(dir.absolutePath, Policy.REQUIRED_REAL, sha).files
        assertEquals("manifest.json", files.first())
        assertTrue(files.none { it == "notes.txt" || it == "clips/unlisted.aac" })
        assertEquals(305, files.size)
    }

    /** Files of mobile/ and web/ that belong to the repository (no build output, caches or dependencies). */
    private fun sources(root: File): Sequence<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", "node_modules", ".idea", "voicepack-local") && !it.name.startsWith("dist") }
        .filter { it.isFile && it.length() < 5_000_000 }

    @Test
    fun ac52AndAc6RepositoryScans() {
        // The needles are assembled so that this file itself does not contain them.
        val token = listOf("CHI", "MEGE_API_", "TOKEN").joinToString("")
        val vendor = TestPacks.REAL_VENDOR
        val repo = TestStrings.repoRoot
        val hits = ArrayList<String>()
        for (dir in listOf("mobile", "web")) {
            for (f in sources(File(repo, dir))) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (text.contains(token)) hits += "AC 52: ${f.relativeTo(repo)}"
                if (text.contains(vendor, ignoreCase = true)) hits += "AC 6: ${f.relativeTo(repo)}"
            }
        }
        assertEquals(hits.joinToString("\n"), 0, hits.size)
    }

    @Test
    fun ac52TheApkHasNoTokenAndClipsAreStored() {
        // Runs on whatever debug APK the last assembleDebug produced (skipped when there is none).
        val apk = File(TestStrings.repoRoot, "mobile/android/app/build/outputs/apk/debug").listFiles { f -> f.extension == "apk" }?.firstOrNull()
        org.junit.Assume.assumeTrue("no debug APK built yet", apk != null)
        val token = listOf("CHI", "MEGE_API_", "TOKEN").joinToString("").toByteArray()
        ZipFile(apk!!).use { zip ->
            for (e in zip.entries()) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                assertTrue("AC 52: ${e.name} contains the token variable name", indexOf(bytes, token) < 0)
                if (e.name.startsWith("assets/voicepack/") && e.name.endsWith(".aac")) {
                    assertEquals("ADR-0010 §3.3: ${e.name} is stored uncompressed", java.util.zip.ZipEntry.STORED, e.method)
                }
            }
            assertNotNull("the inventory asset is in the APK", zip.getEntry("assets/voice/inventory.tsv"))
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
