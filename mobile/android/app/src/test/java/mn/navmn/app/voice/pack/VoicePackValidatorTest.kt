package mn.navmn.app.voice.pack

import mn.navmn.app.support.TestVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * NAV-016 M6 (AC 39, 40, 44, 46): the runtime pack check, pack-format §8 V1–V10. Each AC 44 corruption gives its reason
 * code; a valid stub pack is accepted only under the debug policy.
 */
class VoicePackValidatorTest {
    private val inv = TestVoice.inventory

    private fun validate(files: PackFiles, policy: PackPolicy = PackPolicy.OPTIONAL) = VoicePackValidator.validate(files, inv, policy)

    private fun reason(s: PackState) = (s as? PackState.Invalid)?.reason

    private fun with(built: TestPacks.Built, change: (MutableMap<String, ByteArray?>) -> Unit): PackFiles {
        val m = HashMap<String, ByteArray?>()
        m[VoicePackValidator.MANIFEST] = built.manifest.toByteArray(Charsets.UTF_8)
        m.putAll(built.files)
        change(m)
        return PackFiles { m[it] }
    }

    @Test
    fun validStubPackUnderOptionalAndItsContents() {
        val s = validate(TestPacks.build().packFiles())
        assertTrue("$s", s is PackState.Valid)
        val pack = (s as PackState.Valid).pack
        assertEquals(304, pack.entries.size)
        assertEquals(80, pack.joinPauseMs)
        assertEquals(PackCodec(22_050, 1_024, 24), pack.codec)
        assertTrue(pack.entry(Segment("instr.turn.right", Take.NON_FINAL)) != null)
    }

    @Test
    fun ac44CorruptionsGiveTheirReasonCodes() {
        val built = TestPacks.build(vendor = TestPacks.REAL_VENDOR)
        assertTrue(validate(built.packFiles(), PackPolicy.REQUIRED_REAL) is PackState.Valid)
        // one byte changed in one audio file
        val first = built.files.keys.first()
        assertEquals(PackReason.FILE_HASH_MISMATCH, reason(validate(with(built) { it[first] = it[first]!!.copyOf().also { b -> b[0] = (b[0] + 1).toByte() } })))
        // one file deleted
        assertEquals(PackReason.FILE_MISSING, reason(validate(with(built) { it.remove(first) })))
        // the manifest truncated
        assertEquals(PackReason.MANIFEST_UNREADABLE, reason(validate(with(built) { it[VoicePackValidator.MANIFEST] = built.manifest.take(built.manifest.length / 2).toByteArray() })))
        // inventorySha256 changed
        assertEquals(PackReason.INVENTORY_MISMATCH, reason(validate(TestPacks.build(inventorySha256 = "0".repeat(64)).packFiles())))
        // schemaVersion 99
        assertEquals(PackReason.SCHEMA_UNSUPPORTED, reason(validate(TestPacks.build(schemaVersion = "99").packFiles())))
        // a stub pack in a release / demo variant
        assertEquals(PackReason.STUB_NOT_ALLOWED, reason(validate(TestPacks.build().packFiles(), PackPolicy.REQUIRED_REAL)))
    }

    @Test
    fun theOtherChecksInOrder() {
        assertEquals("V1", PackReason.NO_PACK, reason(validate(PackFiles { null })))
        assertEquals("V2 not JSON", PackReason.MANIFEST_UNREADABLE, reason(validate(PackFiles { "not json".toByteArray() })))
        val built = TestPacks.build(vendor = TestPacks.REAL_VENDOR)
        fun manifest(edit: (String) -> String) = with(built) { it[VoicePackValidator.MANIFEST] = edit(built.manifest).toByteArray() }
        assertEquals("V3 unknown member", PackReason.MANIFEST_UNREADABLE, reason(validate(manifest { it.replace("\"packVersion\": 1,", "\"packVersion\": 1, \"extra\": 1,") })))
        assertEquals("V3 bad type", PackReason.MANIFEST_UNREADABLE, reason(validate(manifest { it.replace("\"packVersion\": 1,", "\"packVersion\": \"1\",") })))
        assertEquals("V4", PackReason.CODEC_UNSUPPORTED, reason(validate(manifest { it.replace("\"container\": \"adts\"", "\"container\": \"mp4\"") })))
        assertEquals("V4 rate", PackReason.CODEC_UNSUPPORTED, reason(validate(manifest { it.replace("\"sampleRateHz\": 22050, \"channels\"", "\"sampleRateHz\": 44100, \"channels\"") })))
        assertEquals("V6", PackReason.VOICE_NOT_ALLOWED, reason(validate(TestPacks.build(vendor = TestPacks.REAL_VENDOR, voiceId = "FEMALE1").packFiles(), PackPolicy.REQUIRED_REAL)))
        assertTrue("V6 is not applied in debug", validate(TestPacks.build(voiceId = "FEMALE1").packFiles()) is PackState.Valid)
        assertEquals("V7 changed text", PackReason.INVENTORY_MISMATCH, reason(validate(manifest { it.replaceFirst("\"spokenText\": \"дараа нь\"", "\"spokenText\": \"дараа ни\"") })))
        assertEquals("V8 path", PackReason.ENTRY_INVALID, reason(validate(manifest { it.replaceFirst("\"file\": \"clips/", "\"file\": \"../") })))
        val firstDuration = Regex("\"durationMs\": (\\d+)").find(built.manifest)!!
        assertEquals("V8 duration", PackReason.ENTRY_INVALID, reason(validate(manifest { it.replaceFirst(firstDuration.value, "\"durationMs\": ${firstDuration.groupValues[1].toInt() + 5}") })))
    }

    @Test
    fun aPackForAnotherInventoryIsRejected() {
        val other = VoiceInventory(String(TestVoice.bytes, Charsets.UTF_8).replace("дараа нь", "дараа ни").toByteArray(Charsets.UTF_8))
        val s = validate(TestPacks.build(inventory = other).packFiles())
        assertEquals("AC 5, AC 46", PackReason.INVENTORY_MISMATCH, reason(s))
    }

    /** QA interop (task breakdown §3.1): runs only with -Pnav.voicePackDir pointing at a generator stub pack. */
    @Test
    fun interopWithTheGeneratorPack() {
        val dir = System.getProperty("nav.voicePackDir").orEmpty().trim()
        Assume.assumeTrue("nav.voicePackDir not set", dir.isNotEmpty() && dir != "none")
        val root = File(dir)
        val s = validate(PackFiles { path -> root.resolve(path).takeIf { it.isFile }?.readBytes() })
        assertTrue("the generator pack fails the app's check: $s", s is PackState.Valid)
    }
}
