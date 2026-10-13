package mn.navmn.app.voice.pack

/**
 * NAV-016 M8 (AC 21, 29, 31): pack-format §6. One prompt = trimmed clip 1, pause, trimmed clip 2, … as one PCM buffer
 * for one audio session; an optional device lead-in (NAV-012 AC 33, Bluetooth) goes before the first clip and is not
 * part of the join rules or the duration. Pure; shared by the player and the JVM duration check.
 */
object PromptAssembler {
    const val JOIN_THEN = "join.then"

    /** The pause between consecutive segments [a] → [b]: `thenPauseMs` next to `join.then`, otherwise `joinPauseMs`. */
    fun pauseMs(a: Segment, b: Segment, joinPauseMs: Int, thenPauseMs: Int): Int =
        if (a.key == JOIN_THEN || b.key == JOIN_THEN) thenPauseMs else joinPauseMs

    /** round-half-up(ms × rate / 1000): 80 ms at 22,050 Hz = 1,764 frames. */
    fun pauseFrames(ms: Int, sampleRateHz: Int): Int = ((ms.toLong() * sampleRateHz * 2 + 1000) / 2000).toInt()

    /** The prompt PCM at [sampleRateHz] from the trimmed [clips] of [segments] (same order, same size). */
    fun assemble(clips: List<ShortArray>, segments: List<Segment>, pack: VoicePack, sampleRateHz: Int, leadInFrames: Int = 0): ShortArray {
        require(clips.size == segments.size && clips.isNotEmpty()) { "one clip per segment" }
        val pauses = (1 until segments.size).map {
            pauseFrames(pauseMs(segments[it - 1], segments[it], pack.joinPauseMs, pack.thenPauseMs), sampleRateHz)
        }
        val out = ShortArray(leadInFrames + clips.sumOf { it.size } + pauses.sum())
        var at = leadInFrames
        for ((i, clip) in clips.withIndex()) {
            if (i > 0) at += pauses[i - 1]
            clip.copyInto(out, at)
            at += clip.size
        }
        return out
    }

    /** AC 29 / NAV-007 AC 11: Σ durationMs + Σ pause ms of [segments] in [pack]; null if a segment is not in the pack. */
    fun durationMs(segments: List<Segment>, pack: VoicePack): Int? {
        var total = 0
        for ((i, s) in segments.withIndex()) {
            total += pack.entry(s)?.durationMs ?: return null
            if (i > 0) total += pauseMs(segments[i - 1], s, pack.joinPauseMs, pack.thenPauseMs)
        }
        return total
    }
}
