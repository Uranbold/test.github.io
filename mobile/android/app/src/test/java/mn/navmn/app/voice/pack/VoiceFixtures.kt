package mn.navmn.app.voice.pack

import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import java.io.File

/** glossary-c3-spoken.tsv (the BA's table), read directly from the repository. */
object C3Table {
    data class Row(val key: String, val display: String, val spoken: String)

    val rows: List<Row> by lazy {
        val lines = File(TestStrings.repoRoot, "docs/requirements/glossary-c3-spoken.tsv").readText().split('\n').filter { it.isNotEmpty() }
        val header = lines.first().split('\t')
        lines.drop(1).map { l ->
            val c = l.split('\t')
            Row(c[header.indexOf("key")], c[header.indexOf("display_mn")], c[header.indexOf("spoken_mn")])
        }
    }

    val byKey: Map<String, Row> by lazy { rows.associateBy { it.key } }
}

/**
 * Test packs that satisfy pack-format §3 and §8 (NAV-016 M6 tests). The clips are placeholder bytes (the validator never
 * decodes); [durationMsPerChar] models the B2 stub synthesiser (70 ms per character, ADR-0010 §4.4) plus the two 5 ms
 * edge margins of the processing (pack-format §4).
 */
object TestPacks {
    const val RATE = 22_050
    const val DELAY = 1_024

    data class Built(val manifest: String, val files: Map<String, ByteArray>) {
        fun packFiles(): PackFiles = PackFiles { path -> if (path == VoicePackValidator.MANIFEST) manifest.toByteArray(Charsets.UTF_8) else files[path] }

        fun writeTo(dir: File): File {
            dir.mkdirs()
            dir.resolve("manifest.json").writeText(manifest)
            for ((path, bytes) in files) dir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
            return dir
        }
    }

    /** The stub duration model: 70 ms per character + 2 × 5 ms edge margins, as frames at 22,050 Hz. */
    fun stubFrames(spoken: String, msPerChar: Int = 70): Int = ((msPerChar * spoken.length + 10).toLong() * RATE / 1000).toInt()

    fun durationMs(frames: Int): Int = ((frames.toLong() * 2000 + RATE) / (2 * RATE)).toInt()

    fun clipBytes(key: String, take: String): ByteArray = "ADTS-PLACEHOLDER $key $take".toByteArray(Charsets.US_ASCII)

    /** A complete pack for [inventory] (default: the app's). */
    fun build(
        vendor: String = "stub",
        voiceId: String = "FEMALE5v2",
        inventory: VoiceInventory = TestVoice.inventory,
        inventorySha256: String? = null,
        schemaVersion: String = "1",
        frames: (String, Take, String) -> Int = { _, _, s -> stubFrames(s) },
        joinPauseMs: Int = 80,
        thenPauseMs: Int = 80,
    ): Built {
        val files = LinkedHashMap<String, ByteArray>()
        val entries = inventory.entries.map { (key, take, spoken) ->
            val file = "clips/$key.${take.id}.aac"
            val bytes = clipBytes(key, take.id)
            files[file] = bytes
            val f = frames(key, take, spoken)
            val encoded = ((f + DELAY + 1023) / 1024) * 1024
            "    { \"key\": \"$key\", \"take\": \"${take.id}\", \"spokenText\": \"$spoken\", \"file\": \"$file\", \"frames\": $f, " +
                "\"encodedFrames\": $encoded, \"durationMs\": ${durationMs(f)}, \"sha256\": \"${VoiceInventory.sha256Hex(bytes)}\" }"
        }
        val manifest = buildString {
            append("{\n")
            append("  \"schemaVersion\": $schemaVersion,\n")
            append("  \"packVersion\": 1,\n")
            append("  \"voiceId\": \"$voiceId\",\n")
            append("  \"source\": { \"vendor\": \"$vendor\", \"apiVersion\": \"${if (vendor == "stub") "stub" else "v1.2"}\", \"voice\": \"$voiceId\" },\n")
            append("  \"sourceSampleRateHz\": 22050,\n")
            append("  \"codec\": { \"name\": \"aac-lc\", \"container\": \"adts\", \"fileExtension\": \".aac\", \"sampleRateHz\": $RATE, \"channels\": 1, ")
            append("\"bitrateKbps\": 24, \"encoderDelayFrames\": $DELAY, \"encoder\": \"ffmpeg-7.0.2-aac\" },\n")
            append("  \"takeMethod\": \"carrier-then-v1\",\n")
            append("  \"joinPauseMs\": $joinPauseMs,\n")
            append("  \"thenPauseMs\": $thenPauseMs,\n")
            append("  \"processing\": { \"silenceThresholdDbfs\": -45, \"edgeMarginMs\": 5, \"fadeMs\": 10, \"targetRmsDbfs\": -20, \"peakCeilingDbfs\": -1 },\n")
            append("  \"glossaryRevision\": \"${"0".repeat(64)}\",\n")
            append("  \"inventorySha256\": \"${inventorySha256 ?: inventory.sha256}\",\n")
            append("  \"entries\": [\n")
            append(entries.joinToString(",\n"))
            append("\n  ]\n}\n")
        }
        return Built(manifest, files)
    }

    /** The real vendor name of the schema enum, assembled (NAV-016 AC 6: the name never appears in mobile/). */
    val REAL_VENDOR: String = listOf("chi", "mege").joinToString("")
}
