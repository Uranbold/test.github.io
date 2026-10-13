package mn.navmn.app.voice

import mn.navmn.app.support.TestVoice
import mn.navmn.app.voice.pack.PackFiles
import mn.navmn.app.voice.pack.PackPolicy
import mn.navmn.app.voice.pack.PackState
import mn.navmn.app.voice.pack.PromptAssembler
import mn.navmn.app.voice.pack.Segment
import mn.navmn.app.voice.pack.TestPacks
import mn.navmn.app.voice.pack.VoicePack
import mn.navmn.app.voice.pack.VoicePackValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NAV-016 M4 (AC 29, NAV-007 AC 11, navigation-ux §4.7): the duration of every prompt of the generator domain
 * ([VoiceDomain]) = Σ segment `durationMs` + the pack-format §6 pauses, from a pack manifest:
 *  - the real pack when `-Pnav.voicePackDir` points at one (validated first, pack-format §8);
 *  - otherwise a Kotlin stub manifest at 70 ms per character (the B2 stub synthesiser, ADR-0010 §4.4).
 * Limits: single ≤ 5,000 ms, chained ≤ 8,000 ms. Writes build/reports/voice/longest-prompts.tsv (pack-format §7 prompt
 * list: the 10 longest single and 10 longest chained prompts with durationMs; the generator's `render` reads it).
 * Enforced for a real pack and whenever nav.voiceDurationEnforce=true (the demo and release builds' checkVoicePackDurations);
 * the stub manifest only reports, because its 70 ms per character is a synthetic model, not speech.
 */
class VoiceDurationCheck {
    data class Timed(val segments: List<Segment>, val ms: Int, val input: String) {
        val chained: Boolean get() = segments.any { it.key == PromptAssembler.JOIN_THEN }
    }

    private fun manifest(): Pair<VoicePack, String> {
        val dir = System.getProperty("nav.voicePackDir").orEmpty().trim()
        if (dir.isEmpty() || dir == "none") {
            val built = TestPacks.build()
            val s = VoicePackValidator.validate(built.packFiles(), TestVoice.inventory, PackPolicy.OPTIONAL)
            return (s as PackState.Valid).pack to "stub manifest (70 ms per character)"
        }
        val root = File(dir)
        val files = PackFiles { path -> root.resolve(path).takeIf { it.isFile }?.readBytes() }
        val s = VoicePackValidator.validate(files, TestVoice.inventory, PackPolicy.OPTIONAL)
        check(s is PackState.Valid) { "nav.voicePackDir: the pack is not valid ($s)" }
        return s.pack to "pack ${s.pack.voiceId} v${s.pack.packVersion} (${s.pack.vendor})"
    }

    @Test
    fun ac29EveryPromptFitsTheLimits() {
        val (pack, label) = manifest()
        val unique = LinkedHashMap<List<Segment>, String>()
        for (item in VoiceDomain.items) item.prompt?.let { unique.putIfAbsent(it.segments, item.input) }
        val timed = unique.map { (segs, input) -> Timed(segs, requireNotNull(PromptAssembler.durationMs(segs, pack)) { "$input: segment not in the pack" }, input) }
        val single = timed.filter { !it.chained }.sortedByDescending { it.ms }
        val chained = timed.filter { it.chained }.sortedByDescending { it.ms }
        val over = single.filter { it.ms > SINGLE_MAX_MS } + chained.filter { it.ms > CHAINED_MAX_MS }
        writeReport(single.take(10), chained.take(10), label)
        println(
            "NAV-016 AC 29 ($label): ${timed.size} distinct prompts; longest single ${single.first().ms} ms " +
                "(${Segment.tokens(single.first().segments)}), longest chained ${chained.first().ms} ms " +
                "(${Segment.tokens(chained.first().segments)}); over the limits: ${over.size}",
        )
        val enforce = pack.vendor != "stub" || System.getProperty("nav.voiceDurationEnforce") == "true"
        if (enforce) {
            assertEquals(
                "AC 29 ($label): prompts over 5,000 ms single / 8,000 ms chained:\n" +
                    over.take(20).joinToString("\n") { "${it.ms} ms ${Segment.tokens(it.segments)} (${it.input})" },
                0,
                over.size,
            )
        }
        assertTrue(single.isNotEmpty() && chained.isNotEmpty())
    }

    private fun writeReport(single: List<Timed>, chained: List<Timed>, label: String) {
        val dir = File(System.getProperty("nav.voiceReportDir")?.takeIf { it.isNotBlank() } ?: "build/reports/voice")
        dir.mkdirs()
        val sb = StringBuilder("id\tsegments\tdurationMs\tkind\tinput\n")
        single.forEachIndexed { i, t -> sb.append("longest-single-%02d\t%s\t%d\tsingle\t%s\n".format(i + 1, Segment.tokens(t.segments), t.ms, t.input)) }
        chained.forEachIndexed { i, t -> sb.append("longest-chained-%02d\t%s\t%d\tchained\t%s\n".format(i + 1, Segment.tokens(t.segments), t.ms, t.input)) }
        dir.resolve("longest-prompts.tsv").writeText(sb.toString())
        dir.resolve("longest-prompts.source.txt").writeText("$label\n")
    }

    companion object {
        const val SINGLE_MAX_MS = 5_000
        const val CHAINED_MAX_MS = 8_000
    }
}
