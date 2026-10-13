package mn.navmn.app.voice.pack

import mn.navmn.app.support.TestVoice
import java.io.File

/**
 * NAV-016 pack-format §8.1 / §8.2 conformance fixtures (task breakdown B9 / M5 / M6, §3.2 reason-code parity): one
 * corrupted pack per single fault of the §8.1 ownership table, the two-fault cases (a V3 fault and a V4 fault → V3), the
 * QA parity cases c01–c21 of tests/android/nav016/reason_code_parity.py, and the §8.2 size caps. Every case starts from
 * the valid stub pack of [TestPacks.build] and is run by both VoicePackValidatorTest (runtime) and VoicePackGuardTest
 * (Gradle guard, debug semantics of `verify`: no V5/V6, the compiled-inventory comparison on). [expected] null = the pack
 * is accepted by both clients (c16: only the generator's G1 `UNLISTED_FILE` sees it).
 *
 * The link fixtures of §8.2 exist only for the guard (assets cannot hold links); VoicePackGuardTest creates them at run
 * time, nothing link-shaped is committed.
 */
object PackFaultFixtures {
    class Case(
        val id: String,
        val expected: PackReason?,
        /** Changes the manifest text (the TestPacks layout: one top-level member per line, one entry per line). */
        val manifest: (String) -> String = { it },
        /** Changes the files (path → bytes; null removes the file). Runs after [manifest]. */
        val files: (MutableMap<String, ByteArray?>) -> Unit = {},
        /** The pack the case starts from. */
        val base: () -> TestPacks.Built = { TestPacks.build() },
        /** Raw manifest bytes instead of the text (for non-UTF-8). */
        val manifestBytes: ((String) -> ByteArray)? = null,
    ) {
        /** The corrupted pack as path → bytes, manifest under [VoicePackValidator.MANIFEST]. */
        fun materialise(): Map<String, ByteArray> {
            val b = base()
            val text = manifest(b.manifest)
            val m = LinkedHashMap<String, ByteArray?>()
            m[VoicePackValidator.MANIFEST] = manifestBytes?.invoke(text) ?: text.toByteArray(Charsets.UTF_8)
            m.putAll(b.files)
            files(m)
            @Suppress("UNCHECKED_CAST")
            return m.filterValues { it != null } as Map<String, ByteArray>
        }

        fun packFiles(): PackFiles {
            val m = materialise()
            return PackFiles { m[it] }
        }

        fun writeTo(dir: File): File {
            dir.mkdirs()
            for ((path, bytes) in materialise()) dir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
            return dir
        }

        override fun toString() = id
    }

    private val R = PackReason.MANIFEST_UNREADABLE

    private fun firstEntryLine(m: String): String = m.lines().first { it.trimStart().startsWith("{ \"key\"") }

    /** Replaces [old] with [new] inside the first entry line only. */
    private fun firstEntry(m: String, old: Regex, new: (MatchResult) -> String): String {
        val line = firstEntryLine(m)
        val hit = requireNotNull(old.find(line)) { "fixture: $old not in the first entry" }
        return m.replaceFirst(line, line.replaceRange(hit.range, new(hit)))
    }

    private fun edit(m: String, old: String, new: String): String {
        require(m.contains(old)) { "fixture: $old not in the manifest" }
        return m.replaceFirst(old, new)
    }

    private fun swapFirstTwoEntries(m: String): String {
        val lines = m.lines().toMutableList()
        val i = lines.indexOfFirst { it.trimStart().startsWith("{ \"key\"") }
        val a = lines[i].removeSuffix(",")
        val b = lines[i + 1].removeSuffix(",")
        lines[i] = "$b,"
        lines[i + 1] = if (lines[i + 1].endsWith(",")) "$a," else a
        return lines.joinToString("\n")
    }

    private fun clipName(): String = TestPacks.build().files.keys.first { it.endsWith("instr.turn.right.final.aac") }

    /** A pack whose wording differs from the app's (join.then), self-consistent: only the compiled comparison fails. */
    val olderWording: () -> TestPacks.Built = {
        TestPacks.build(inventory = VoiceInventory(String(TestVoice.bytes, Charsets.UTF_8).replace("дараа нь", "дараа нь бас").toByteArray(Charsets.UTF_8)))
    }

    /** The §8.1 + parity set (no size caps). */
    val ownership: List<Case> = listOf(
        // QA parity cases c01–c21 (reason_code_parity.py), same ids
        Case("c01-byte-changed", PackReason.FILE_HASH_MISMATCH, files = { f -> val k = clipName(); f[k] = f[k]!!.copyOf().also { it[0] = (it[0] + 1).toByte() } }),
        Case("c02-file-deleted", PackReason.FILE_MISSING, files = { it[TestPacks.build().files.keys.first { k -> k.endsWith("dist.m.300.final.aac") }] = null }),
        Case("c03-manifest-truncated", R, manifest = { it.take(it.length / 2) }),
        Case("c04-inventory-sha-changed", PackReason.INVENTORY_MISMATCH, base = { TestPacks.build(inventorySha256 = "0".repeat(64)) }),
        Case("c05-schema-99", PackReason.SCHEMA_UNSUPPORTED, base = { TestPacks.build(schemaVersion = "99") }),
        Case("c06-top-level-array", R, manifest = { "[" + it.trimEnd('\n') + "]\n" }),
        Case("c07-schema-string", PackReason.SCHEMA_UNSUPPORTED, base = { TestPacks.build(schemaVersion = "\"1\"") }),
        Case("c08-schema-1.0", PackReason.SCHEMA_UNSUPPORTED, base = { TestPacks.build(schemaVersion = "1.0") }),
        Case("c09-packversion-1.0", R, manifest = { edit(it, "\"packVersion\": 1,", "\"packVersion\": 1.0,") }),
        Case("c10-bitrate-out-of-range", R, manifest = { edit(it, "\"bitrateKbps\": 24", "\"bitrateKbps\": 999") }),
        Case("c11-codec-name", PackReason.CODEC_UNSUPPORTED, manifest = { edit(it, "\"name\": \"aac-lc\"", "\"name\": \"mp3\"") }),
        Case("c12-two-fault-bitrate-and-name", R, manifest = { edit(edit(it, "\"bitrateKbps\": 24", "\"bitrateKbps\": 999"), "\"name\": \"aac-lc\"", "\"name\": \"mp3\"") }),
        Case("c13-file-escape", PackReason.ENTRY_INVALID, manifest = { firstEntry(it, Regex("\"file\": \"[^\"]+\"")) { "\"file\": \"../x.aac\"" } }),
        Case("c14-voiceid-trailing-newline", R, manifest = { edit(it, "\"voiceId\": \"FEMALE5v2\"", "\"voiceId\": \"FEMALE5v2\\n\"") }),
        Case("c15-extra-top-level-member", R, manifest = { edit(it, "\"packVersion\": 1,", "\"packVersion\": 1, \"builtAt\": \"x\",") }),
        Case("c16-unlisted-extra-file", null, files = { it["clips/extra.final.aac"] = byteArrayOf(120) }),
        Case("c17-entries-unsorted", PackReason.INVENTORY_MISMATCH, manifest = ::swapFirstTwoEntries),
        Case("c18-duration-not-frames", PackReason.ENTRY_INVALID, manifest = { firstEntry(it, Regex("\"durationMs\": (\\d+)")) { m -> "\"durationMs\": ${m.groupValues[1].toInt() + 50}" } }),
        Case("c19-codec-member-missing", R, manifest = { edit(it, ", \"encoder\": \"ffmpeg-7.0.2-aac\"", "") }),
        Case("c20-old-wording-self-consistent", PackReason.INVENTORY_MISMATCH, base = olderWording),
        Case("c21-control-char-in-spoken", R, manifest = { firstEntry(it, Regex("\"spokenText\": \"([^\"]+)\"")) { m -> "\"spokenText\": \"${m.groupValues[1]}\\u0007\"" } }),
        // further single faults of the §8.1 table
        Case("o01-schema-true", PackReason.SCHEMA_UNSUPPORTED, base = { TestPacks.build(schemaVersion = "true") }),
        Case("o02-schema-missing", PackReason.SCHEMA_UNSUPPORTED, manifest = { edit(it, "\"schemaVersion\": 1,\n", "") }),
        Case("o03-packversion-exponent", R, manifest = { edit(it, "\"packVersion\": 1,", "\"packVersion\": 1e0,") }),
        Case("o04-encoder-delay-out-of-range", R, manifest = { edit(it, "\"encoderDelayFrames\": 1024", "\"encoderDelayFrames\": 5000") }),
        Case("o05-encoder-pattern", R, manifest = { edit(it, "\"encoder\": \"ffmpeg-7.0.2-aac\"", "\"encoder\": \"ffmpeg 7\"") }),
        Case("o06-vendor-enum", R, manifest = { edit(it, "\"vendor\": \"stub\"", "\"vendor\": \"other\"") }),
        Case("o07-duration-range", R, manifest = { firstEntry(it, Regex("\"durationMs\": \\d+")) { "\"durationMs\": 4001" } }),
        Case("o08-codec-channels", PackReason.CODEC_UNSUPPORTED, manifest = { edit(it, "\"channels\": 1", "\"channels\": 2") }),
        Case("o09-codec-extension", PackReason.CODEC_UNSUPPORTED, manifest = { edit(it, "\"fileExtension\": \".aac\"", "\"fileExtension\": \".m4a\"") }),
        Case("o10-codec-rate-type", PackReason.CODEC_UNSUPPORTED, manifest = { edit(it, "\"sampleRateHz\": 22050, \"channels\"", "\"sampleRateHz\": \"22050\", \"channels\"") }),
        Case("o11-codec-container", PackReason.CODEC_UNSUPPORTED, manifest = { edit(it, "\"container\": \"adts\"", "\"container\": \"mp4\"") }),
        Case("o12-file-not-a-string", R, manifest = { firstEntry(it, Regex("\"file\": \"[^\"]+\"")) { "\"file\": 7" } }),
        Case("o13-control-char-in-file", R, manifest = { firstEntry(it, Regex("\"file\": \"([^\"]+)\"")) { m -> "\"file\": \"${m.groupValues[1]}\\t\"" } }),
        Case("o14-file-absolute", PackReason.ENTRY_INVALID, manifest = { firstEntry(it, Regex("\"file\": \"[^\"]+\"")) { "\"file\": \"/etc/passwd\"" } }),
        Case("o15-not-utf8", R, manifestBytes = { t -> t.toByteArray(Charsets.UTF_8).also { b -> b[b.indexOf('F'.code.toByte())] = 0xFF.toByte() } }),
        Case("o16-frames-over-encoded", PackReason.ENTRY_INVALID, manifest = { firstEntry(it, Regex("\"encodedFrames\": \\d+")) { "\"encodedFrames\": 1" } }),
        Case("o17-unknown-codec-member", R, manifest = { edit(it, "\"channels\": 1,", "\"channels\": 1, \"profile\": \"lc\",") }),
        Case("o18-control-char-in-member-name", R, manifest = { edit(it, "\"packVersion\": 1,", "\"packVersion\": 1, \"a\\u0001\": 1,") }),
        // two-fault cases (§8.1 conformance: a V3 fault wins over a V4 fault)
        Case("t01-delay-and-container", R, manifest = { edit(edit(it, "\"encoderDelayFrames\": 1024", "\"encoderDelayFrames\": -1"), "\"container\": \"adts\"", "\"container\": \"mp4\"") }),
        Case("t02-codec-and-file-escape", PackReason.CODEC_UNSUPPORTED, manifest = { firstEntry(edit(it, "\"name\": \"aac-lc\"", "\"name\": \"mp3\""), Regex("\"file\": \"[^\"]+\"")) { "\"file\": \"../x.aac\"" } }),
    )

    private fun sized(n: Int): ByteArray = ByteArray(n) { (it * 31 + 7).toByte() }

    /** §8.2 size caps, the same codes on the guard and at runtime. */
    val sizeCaps: List<Case> = listOf(
        Case("s01-manifest-over-1MiB", R, manifest = { it + " ".repeat(VoicePackValidator.MAX_MANIFEST_BYTES) }),
        Case("s02-clip-262145-bytes", PackReason.FILE_INVALID, base = {
            TestPacks.build(clip = { k, t -> if (k == "instr.turn.right" && t == "final") sized(VoicePackValidator.MAX_CLIP_BYTES + 1) else TestPacks.clipBytes(k, t) })
        }),
        Case("s03-clip-exactly-256KiB", null, base = {
            TestPacks.build(clip = { k, t -> if (k == "instr.turn.right" && t == "final") sized(VoicePackValidator.MAX_CLIP_BYTES) else TestPacks.clipBytes(k, t) })
        }),
        Case("s04-pack-over-6MiB", PackReason.FILE_INVALID, base = {
            // every clip under the cap (25,000 bytes), 304 clips ≈ 7.6 MB in total
            TestPacks.build(clip = { _, _ -> sized(25_000) })
        }),
        Case("s05-oversized-clip-before-a-missing-one", PackReason.FILE_INVALID, base = {
            TestPacks.build(clip = { k, t -> if (k == "dist.km.1" && t == "final") sized(VoicePackValidator.MAX_CLIP_BYTES + 1) else TestPacks.clipBytes(k, t) })
        }, files = { f -> f.keys.filter { it.startsWith("clips/tail.") }.forEach { f[it] = null } }),
    )

    val all: List<Case> = ownership + sizeCaps
}
