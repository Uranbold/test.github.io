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
import java.nio.file.Files
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
        // inventorySha256 that the entries do not hash to: a corrupt manifest, fails every variant, debug too (defect 6)
        val stale = pack("stale", TestPacks.build(vendor = TestPacks.REAL_VENDOR, inventorySha256 = "0".repeat(64)))
        assertTrue(problem(stale.absolutePath, Policy.REQUIRED_REAL)!!.contains("V7 INVENTORY_MISMATCH"))
        assertTrue(problem(stale.absolutePath, Policy.OPTIONAL)!!.contains("V7 INVENTORY_MISMATCH"))
        val otherInventory = VoiceInventory(String(mn.navmn.app.support.TestVoice.bytes, Charsets.UTF_8).replace("дараа нь", "дараа ни").toByteArray(Charsets.UTF_8))
        val older = pack("older", TestPacks.build(vendor = TestPacks.REAL_VENDOR, inventory = otherInventory))
        assertTrue("AC 5: a pack from older wording", problem(older.absolutePath, Policy.REQUIRED_REAL_OR_NONE)!!.contains("differs from the app's inventory"))
        val debug = VoicePackGuard.check(older.absolutePath, Policy.OPTIONAL, sha)
        assertEquals("AC 46: debug bundles it; the app falls back", 305, debug.files.size)
        assertNull(debug.problem)
        assertTrue(debug.warning!!.contains("V7 INVENTORY_MISMATCH"))
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

    /**
     * Defect 6 (pack-format §8, ADR-0010 §5): the debug downgrade skips only the comparison with the compiled
     * inventorySha256. The rest of V7 and V8–V10 still run on a pack from older wording.
     */
    @Test
    fun debugDowngradesOnlyTheCompiledHashComparison() {
        val older = PackFaultFixtures.olderWording
        // self-consistent older wording: warning, bundled
        assertNull(VoicePackGuard.check(pack("ok", older()).absolutePath, Policy.OPTIONAL, sha).problem)
        // older wording + entries unsorted: the manifest's own consistency fails, debug too
        val unsorted = PackFaultFixtures.Case("x", PackReason.INVENTORY_MISMATCH, manifest = { m ->
            val lines = m.lines().toMutableList()
            val i = lines.indexOfFirst { it.trimStart().startsWith("{ \"key\"") }
            val a = lines[i]
            lines[i] = lines[i + 1]
            lines[i + 1] = a
            lines.joinToString("\n")
        }, base = older).writeTo(File(tmp.root, "unsorted"))
        assertTrue(problem(unsorted.absolutePath, Policy.OPTIONAL)!!.contains("V7 INVENTORY_MISMATCH"))
        // older wording + a duplicated entry
        val dup = PackFaultFixtures.Case("x", PackReason.INVENTORY_MISMATCH, manifest = { m ->
            val line = m.lines().first { it.trimStart().startsWith("{ \"key\"") }
            m.replaceFirst(line, "$line\n$line")
        }, base = older).writeTo(File(tmp.root, "dup"))
        assertTrue(problem(dup.absolutePath, Policy.OPTIONAL)!!.contains("V7 INVENTORY_MISMATCH"))
        // older wording + entries that do not hash to the manifest's own inventorySha256
        val selfHash = PackFaultFixtures.Case("x", PackReason.INVENTORY_MISMATCH, manifest = { m ->
            m.replace(Regex("\"inventorySha256\": \"[0-9a-f]{64}\""), "\"inventorySha256\": \"${"1".repeat(64)}\"")
        }, base = older).writeTo(File(tmp.root, "selfhash"))
        assertTrue(problem(selfHash.absolutePath, Policy.OPTIONAL)!!.contains("the hash of the entries differs"))
        // older wording + V8 / V10 faults: still checked after the downgrade
        val v8 = PackFaultFixtures.ownership.single { it.id == "c18-duration-not-frames" }
        val v8Dir = PackFaultFixtures.Case("x", null, manifest = v8.manifest, base = older).writeTo(File(tmp.root, "v8"))
        assertTrue(problem(v8Dir.absolutePath, Policy.OPTIONAL)!!.contains("V8 ENTRY_INVALID"))
        val v10 = PackFaultFixtures.ownership.single { it.id == "c01-byte-changed" }
        val v10Dir = PackFaultFixtures.Case("x", null, files = v10.files, base = older).writeTo(File(tmp.root, "v10"))
        assertTrue(problem(v10Dir.absolutePath, Policy.OPTIONAL)!!.contains("V10 FILE_HASH_MISMATCH"))
    }

    /** pack-format §8.1 conformance (D-3): the guard gives the same code as VoicePackValidator for every fixture. */
    @Test
    fun section81AndSizeCapFixturesGiveTheSameCodeAsTheRuntimeValidator() {
        val wrong = ArrayList<String>()
        for (c in PackFaultFixtures.all) {
            val dir = c.writeTo(File(tmp.root, "f-${c.id}"))
            val got = try {
                VoicePackGuard.verify(dir, false, sha)
                null
            } catch (f: VoicePackGuard.CheckFailure) {
                f.reason
            }
            if (got != c.expected?.name) wrong += "${c.id}: expected ${c.expected ?: "OK"}, guard ${got ?: "OK"}"
            val runtime = VoicePackValidator.validate(c.packFiles(), mn.navmn.app.support.TestVoice.inventory, PackPolicy.OPTIONAL)
            val rt = (runtime as? PackState.Invalid)?.reason?.name
            if (rt != got) wrong += "${c.id}: parity guard ${got ?: "OK"} vs validator ${rt ?: "OK"}"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
        val two = PackFaultFixtures.ownership.single { it.id == "c12-two-fault-bitrate-and-name" }
        val msg = problem(two.writeTo(File(tmp.root, "two")).absolutePath, Policy.OPTIONAL)!!
        assertTrue(msg, msg.contains("V3 MANIFEST_UNREADABLE"))
    }

    @Test
    fun sizeCapsMatchTheRuntimeValidator() {
        assertEquals(VoicePackValidator.MAX_MANIFEST_BYTES, VoicePackGuard.MAX_MANIFEST_BYTES)
        assertEquals(VoicePackValidator.MAX_CLIP_BYTES, VoicePackGuard.MAX_CLIP_BYTES)
        assertEquals(VoicePackValidator.MAX_PACK_BYTES, VoicePackGuard.MAX_PACK_BYTES)
        val big = PackFaultFixtures.sizeCaps.single { it.id == "s02-clip-262145-bytes" }
        val msg = problem(big.writeTo(File(tmp.root, "big")).absolutePath, Policy.OPTIONAL)!!
        assertTrue(msg, msg.contains("nav.voicePackDir") && msg.contains("V9 FILE_INVALID"))
    }

    private fun link(at: File, to: File) {
        at.delete()
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    /** pack-format §8.2 / security VP-4: links below the pack directory are never followed (created at test time). */
    @Test
    fun section82LinksAreRefused() {
        val built = TestPacks.build()
        val clip = built.files.keys.first { it.endsWith("instr.turn.right.final.aac") }
        fun code(dir: File): String? = try {
            VoicePackGuard.verify(dir, false, sha)
            null
        } catch (f: VoicePackGuard.CheckFailure) {
            "${f.check} ${f.reason}"
        }
        // manifest.json as a link (to a valid copy outside the pack)
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        val m1 = pack("m-link", built)
        val realManifest = File(outside, "manifest.json").apply { writeText(built.manifest) }
        link(File(m1, "manifest.json"), realManifest)
        assertEquals("V2 MANIFEST_UNREADABLE", code(m1))
        // a clip as a link to a file outside the pack with the same bytes (so the sha256 matches)
        val c1 = pack("c-link-out", built)
        val decoy = File(outside, "decoy.aac").apply { writeBytes(built.files[clip]!!) }
        link(File(c1, clip), decoy)
        assertEquals("V9 FILE_INVALID", code(c1))
        val msg = problem(c1.absolutePath, Policy.OPTIONAL)!!
        assertTrue(msg, msg.contains("nav.voicePackDir") && msg.contains("V9 FILE_INVALID"))
        // a clip as a link to a file inside the pack
        val c2 = pack("c-link-in", built)
        val inside = File(c2, "copy.aac").apply { writeBytes(built.files[clip]!!) }
        link(File(c2, clip), inside)
        assertEquals("V9 FILE_INVALID", code(c2))
        // a dangling link in place of a clip
        val c3 = pack("c-dangling", built)
        link(File(c3, clip), File(outside, "nothing-here.aac"))
        assertEquals("V9 FILE_INVALID", code(c3))
        // clips/ as a link to a complete copy outside
        val c4 = pack("clips-link", built)
        val clipsCopy = File(outside, "clips-copy")
        File(c4, "clips").copyRecursively(clipsCopy)
        File(c4, "clips").deleteRecursively()
        Files.createSymbolicLink(File(c4, "clips").toPath(), clipsCopy.toPath())
        assertEquals("V9 FILE_INVALID", code(c4))
        // a missing clip is still FILE_MISSING
        val c5 = pack("c-missing", built)
        File(c5, clip).delete()
        assertEquals("V9 FILE_MISSING", code(c5))
        // an unlisted link is never read or bundled (G1 is generator-only)
        val u = pack("unlisted-link", built)
        Files.createSymbolicLink(File(u, "clips/extra.final.aac").toPath(), File("/etc/hostname").toPath())
        val r = VoicePackGuard.check(u.absolutePath, Policy.OPTIONAL, sha)
        assertNull(r.problem)
        assertTrue(r.files.none { it.contains("extra") })
        // the pack directory itself may be reached through a link
        val viaLink = File(tmp.root, "via-link")
        Files.createSymbolicLink(viaLink.toPath(), pack("target", built).toPath())
        assertNull(code(viaLink))
    }

    /** pack-format §8.2: the copy writes regular files from the verified bytes, only the listed paths. */
    @Test
    fun theCopyWritesTheVerifiedBytesAsRegularFiles() {
        val built = TestPacks.build()
        val dir = pack("copy", built)
        File(dir, "notes.txt").writeText("x")
        val r = VoicePackGuard.check(dir.absolutePath, Policy.OPTIONAL, sha)
        // the pack changes after the check: the copy still writes what was verified
        val clip = built.files.keys.first()
        File(dir, clip).writeBytes(byteArrayOf(1, 2, 3))
        val target = File(tmp.root, "out/voicepack")
        r.writeTo(target)
        val written = target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.toSet()
        assertEquals(r.files.toSet(), written)
        assertTrue(target.walkTopDown().none { Files.isSymbolicLink(it.toPath()) })
        assertTrue(File(target, clip).readBytes().contentEquals(built.files[clip]!!))
        assertEquals(built.manifest, File(target, "manifest.json").readText())
    }

    /** D236: the per-voice APKs are told apart by the manifest voiceId (APK file name, versionName suffix), never the vendor. */
    @Test
    fun voiceTagNamesThePerVoiceApks() {
        assertNull(VoicePackGuard.voiceTag(null))
        assertNull(VoicePackGuard.voiceTag(" "))
        assertNull(VoicePackGuard.voiceTag("none"))
        assertNull(VoicePackGuard.voiceTag("relative/dir"))
        assertNull(VoicePackGuard.voiceTag(File(tmp.root, "missing").absolutePath))
        assertEquals("FEMALE5v2", VoicePackGuard.voiceTag(pack("f", TestPacks.build(vendor = TestPacks.REAL_VENDOR)).absolutePath))
        assertEquals("MALE4v2", VoicePackGuard.voiceTag(pack("m", TestPacks.build(vendor = TestPacks.REAL_VENDOR, voiceId = "MALE4v2")).absolutePath))
        assertEquals("FEMALE5v2-stub", VoicePackGuard.voiceTag(pack("s", TestPacks.build()).absolutePath))
        assertEquals("MALE4v2-stub", VoicePackGuard.voiceTag(pack("ms", TestPacks.build(voiceId = "MALE4v2")).absolutePath))
        val bad = PackFaultFixtures.ownership.single { it.id == "c14-voiceid-trailing-newline" }.writeTo(File(tmp.root, "bad"))
        assertNull("a voiceId that fails the schema gives no tag", VoicePackGuard.voiceTag(bad.absolutePath))
        val linked = pack("linked", TestPacks.build())
        val real = File(tmp.root, "real-manifest.json").apply { writeText(File(linked, "manifest.json").readText()) }
        link(File(linked, "manifest.json"), real)
        assertNull("a linked manifest is not read", VoicePackGuard.voiceTag(linked.absolutePath))
        for (tag in listOf("FEMALE5v2", "MALE4v2", "FEMALE5v2-stub")) assertTrue(!tag.contains(TestPacks.REAL_VENDOR, ignoreCase = true))
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
