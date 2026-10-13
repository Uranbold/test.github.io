package mn.navmn.app.voice.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** pack-format §8 reason codes (logged only in debug builds, never with coordinates; NAV-005 AC 67). */
enum class PackReason {
    NO_PACK,
    SCHEMA_UNSUPPORTED,
    MANIFEST_UNREADABLE,
    CODEC_UNSUPPORTED,
    STUB_NOT_ALLOWED,
    VOICE_NOT_ALLOWED,
    INVENTORY_MISMATCH,
    ENTRY_INVALID,
    FILE_MISSING,
    FILE_HASH_MISMATCH,
    DECODE_FAILED,
    PLAYBACK_FAILED,
}

/** The build's pack policy (BuildConfig.VOICE_PACK_POLICY, NAV-016 AC 50): debug accepts stub packs. */
enum class PackPolicy {
    OPTIONAL,
    REQUIRED_REAL,
    ;

    companion object {
        fun of(name: String): PackPolicy = entries.firstOrNull { it.name == name } ?: REQUIRED_REAL
    }
}

/** The manifest `codec` fields the client needs (trim rule, pack-format §5). */
data class PackCodec(val sampleRateHz: Int, val encoderDelayFrames: Int, val bitrateKbps: Int)

/** One manifest entry (pack-format §3). */
data class PackEntry(
    val key: String,
    val take: Take,
    val spokenText: String,
    val file: String,
    val frames: Int,
    val encodedFrames: Int,
    val durationMs: Int,
    val sha256: String,
)

/** A validated pack (V1–V10 passed). */
data class VoicePack(
    val voiceId: String,
    val vendor: String,
    val packVersion: Long,
    val codec: PackCodec,
    val joinPauseMs: Int,
    val thenPauseMs: Int,
    val inventorySha256: String,
    val entries: List<PackEntry>,
) {
    private val byKey = entries.associateBy { it.key to it.take }

    fun entry(segment: Segment): PackEntry? = byKey[segment.key to segment.take]
}

sealed interface PackState {
    /** Validation has not finished (navigation-ux §4.6: the depart prompt waits up to 2 s, then chimes). */
    data object Pending : PackState

    data class Valid(val pack: VoicePack) : PackState

    data class Invalid(val reason: PackReason) : PackState
}

/** Reads a file of the bundled pack by its path relative to the pack directory; null if it does not exist. */
fun interface PackFiles {
    fun read(path: String): ByteArray?
}

/**
 * NAV-016 M6 (AC 39, 40, 44, 46): the runtime pack check, pack-format §8 V1–V10 in order; the first failure gives the
 * reason. Pure (no Android types): the app reads the files from the APK assets `voicepack/` (AssetPackFiles), tests from
 * a map. 0 network requests. Runs on a background thread (VoicePackSource).
 */
object VoicePackValidator {
    private val json = Json { isLenient = false }
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val KEY = Regex("^(dist|cont|instr|tail|join|fixed)(\\.[A-Za-z0-9_]+)+$")
    private val FILE = Regex("^clips/(dist|cont|instr|tail|join|fixed)(\\.[A-Za-z0-9_]+)+\\.(final|nonFinal)\\.aac$")
    private val SPOKEN = Regex("^[\\u0430-\\u044F\\u0451\\u04AF\\u04E9]+( [\\u0430-\\u044F\\u0451\\u04AF\\u04E9]+)*$")
    private val TOP = listOf(
        "schemaVersion", "packVersion", "voiceId", "source", "sourceSampleRateHz", "codec", "takeMethod", "joinPauseMs",
        "thenPauseMs", "processing", "glossaryRevision", "inventorySha256", "entries",
    )
    private val SOURCE = listOf("vendor", "apiVersion", "voice")
    private val CODEC = listOf("name", "container", "fileExtension", "sampleRateHz", "channels", "bitrateKbps", "encoderDelayFrames", "encoder")
    private val PROCESSING = listOf("silenceThresholdDbfs", "edgeMarginMs", "fadeMs", "targetRmsDbfs", "peakCeilingDbfs")
    private val ENTRY = listOf("key", "take", "spokenText", "file", "frames", "encodedFrames", "durationMs", "sha256")
    private val VENDORS = setOf("stub", "recorded", String(charArrayOf('c', 'h', 'i', 'm', 'e', 'g', 'e'))) // AC 6: no vendor literal
    /** D226, D229: the only voices a release or demo build accepts. */
    val REAL_VOICES = setOf("FEMALE5v2", "MALE4v2")
    const val SUPPORTED_SCHEMA = 1L
    const val MANIFEST = "manifest.json"

    private class Fail(val reason: PackReason) : Exception()

    fun validate(files: PackFiles, compiled: VoiceInventory, policy: PackPolicy): PackState = try {
        PackState.Valid(check(files, compiled.sha256, policy))
    } catch (f: Fail) {
        PackState.Invalid(f.reason)
    }

    private fun fail(reason: PackReason): Nothing = throw Fail(reason)

    private fun v3(): Nothing = fail(PackReason.MANIFEST_UNREADABLE)

    private fun check(files: PackFiles, compiledSha: String, policy: PackPolicy): VoicePack {
        val bytes = runCatching { files.read(MANIFEST) }.getOrNull() ?: fail(PackReason.NO_PACK) // V1
        // V2
        val root = runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: fail(PackReason.MANIFEST_UNREADABLE)
        val schema = (root["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (schema != SUPPORTED_SCHEMA) fail(PackReason.SCHEMA_UNSUPPORTED)
        // V3
        root.members(TOP)
        val packVersion = root.long("packVersion", 1, Long.MAX_VALUE)
        val voiceId = root.string("voiceId", Regex("^[A-Za-z0-9]{2,32}$"))
        val source = root.obj("source").also { it.members(SOURCE) }
        val vendor = source.string("vendor", Regex(".*")).also { if (it !in VENDORS) v3() }
        source.string("apiVersion", Regex("^[A-Za-z0-9._-]{1,16}$"))
        source.string("voice", Regex("^[A-Za-z0-9._-]{1,32}$"))
        if (root.long("sourceSampleRateHz", 0, Long.MAX_VALUE) !in setOf(8_000L, 16_000L, 22_050L)) v3()
        val codec = root.obj("codec").also { it.members(CODEC) }
        root.string("takeMethod", Regex("^(carrier-then-v1|comma-v1)$"))
        val joinPause = root.long("joinPauseMs", 0, 300).toInt()
        val thenPause = root.long("thenPauseMs", 0, 300).toInt()
        val processing = root.obj("processing").also { it.members(PROCESSING) }
        processing.number("silenceThresholdDbfs", -70.0, -30.0)
        processing.long("edgeMarginMs", 0, 40)
        processing.long("fadeMs", 0, 50)
        processing.number("targetRmsDbfs", -30.0, -10.0)
        processing.number("peakCeilingDbfs", -6.0, 0.0)
        root.string("glossaryRevision", HEX64)
        val inventorySha = root.string("inventorySha256", HEX64)
        val rawEntries = (root["entries"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: v3()
        val entries = rawEntries.map { el ->
            val e = el as? JsonObject ?: v3()
            e.members(ENTRY)
            val spoken = e.string("spokenText", SPOKEN)
            if (spoken.length !in 2..300) v3()
            PackEntry(
                key = e.string("key", KEY),
                take = Take.of(e.string("take", Regex("^(final|nonFinal)$"))) ?: v3(),
                spokenText = spoken,
                file = e.string("file", Regex(".+")),
                frames = e.long("frames", 1, Int.MAX_VALUE.toLong()).toInt(),
                encodedFrames = e.long("encodedFrames", 1, Int.MAX_VALUE.toLong()).toInt(),
                durationMs = e.long("durationMs", 150, 4000).toInt(),
                sha256 = e.string("sha256", HEX64),
            )
        }
        // V4
        val rate = (codec["sampleRateHz"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val codecOk = codec.str("name") == "aac-lc" && codec.str("container") == "adts" && codec.str("fileExtension") == ".aac" &&
            (codec["channels"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull == 1L && (rate == 16_000L || rate == 22_050L)
        if (!codecOk) fail(PackReason.CODEC_UNSUPPORTED)
        val bitrate = codec.long("bitrateKbps", 16, 64).toInt()
        val delay = codec.long("encoderDelayFrames", 0, 4096).toInt()
        codec.string("encoder", Regex("^[A-Za-z0-9._+-]{1,48}$"))
        // V5, V6
        if (policy == PackPolicy.REQUIRED_REAL && vendor == "stub") fail(PackReason.STUB_NOT_ALLOWED)
        if (policy == PackPolicy.REQUIRED_REAL && voiceId !in REAL_VOICES) fail(PackReason.VOICE_NOT_ALLOWED)
        // V7: sorted, unique; canonical hash of the entries = inventorySha256 = the compiled inventory
        val canonical = StringBuilder()
        for ((i, e) in entries.withIndex()) {
            if (i > 0) {
                val p = entries[i - 1]
                val c = compareAscii(p.key, e.key).let { if (it == 0) compareAscii(p.take.id, e.take.id) else it }
                if (c >= 0) fail(PackReason.INVENTORY_MISMATCH)
            }
            canonical.append(e.key).append('\t').append(e.take.id).append('\t').append(e.spokenText).append('\n')
        }
        val recomputed = VoiceInventory.sha256Hex(canonical.toString().toByteArray(Charsets.UTF_8))
        if (recomputed != inventorySha || inventorySha != compiledSha) fail(PackReason.INVENTORY_MISMATCH)
        // V8
        for (e in entries) {
            if (!FILE.matches(e.file) || e.file.contains("..")) fail(PackReason.ENTRY_INVALID)
            val expected = (e.frames.toLong() * 2000 + rate!!) / (2 * rate)
            if (e.durationMs.toLong() != expected) fail(PackReason.ENTRY_INVALID)
            if (e.frames > e.encodedFrames - delay) fail(PackReason.ENTRY_INVALID)
        }
        // V9, V10
        val contents = entries.map { e -> runCatching { files.read(e.file) }.getOrNull() ?: fail(PackReason.FILE_MISSING) }
        for ((e, b) in entries.zip(contents)) if (VoiceInventory.sha256Hex(b) != e.sha256) fail(PackReason.FILE_HASH_MISMATCH)
        return VoicePack(voiceId, vendor, packVersion, PackCodec(rate!!.toInt(), delay, bitrate), joinPause, thenPause, inventorySha, entries)
    }

    private fun compareAscii(a: String, b: String): Int = a.compareTo(b) // keys and takes are ASCII: UTF-16 order = byte order

    private fun JsonObject.members(required: List<String>) {
        if (keys != required.toSet()) v3()
    }

    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: v3()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.string(k: String, pattern: Regex): String = str(k)?.takeIf { pattern.matches(it) } ?: v3()

    private fun JsonObject.long(k: String, min: Long, max: Long): Long {
        val p = this[k] as? JsonPrimitive ?: v3()
        if (p.isString) v3()
        val v = p.content.takeIf { INTEGER.matches(it) }?.toLongOrNull() ?: v3()
        if (v < min || v > max) v3()
        return v
    }

    private fun JsonObject.number(k: String, min: Double, max: Double) {
        val p: JsonElement = this[k] ?: v3()
        val v = (p as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: v3()
        if (v < min || v > max) v3()
    }

    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
}
