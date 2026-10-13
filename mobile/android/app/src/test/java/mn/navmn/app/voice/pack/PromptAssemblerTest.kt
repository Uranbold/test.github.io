package mn.navmn.app.voice.pack

import mn.navmn.app.support.TestVoice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** NAV-016 M8 (AC 21, 29): pack-format §6 join rules, PCM layout and the duration formula. */
class PromptAssemblerTest {
    private val pack = (VoicePackValidator.validate(TestPacks.build(joinPauseMs = 80, thenPauseMs = 120).packFiles(), TestVoice.inventory, PackPolicy.OPTIONAL) as PackState.Valid).pack
    private fun s(key: String, take: Take = Take.FINAL) = Segment(key, take)

    @Test
    fun pausesFollowTheJoinRules() {
        val chained = listOf(s("dist.m.200"), s("instr.roundabout.exit.2", Take.NON_FINAL), s("join.then"), s("instr.turn.slightRight"))
        val pauses = (1 until chained.size).map { PromptAssembler.pauseMs(chained[it - 1], chained[it], pack.joinPauseMs, pack.thenPauseMs) }
        assertEquals("after a distance joinPauseMs; around join.then thenPauseMs", listOf(80, 120, 120), pauses)
        assertEquals(80, PromptAssembler.pauseMs(s("cont.km.2"), s("tail.continue"), 80, 120))
        assertEquals("80 ms at 22,050 Hz", 1_764, PromptAssembler.pauseFrames(80, 22_050))
        assertEquals(1_323, PromptAssembler.pauseFrames(60, 22_050)) // 1,323.0
        assertEquals(2, PromptAssembler.pauseFrames(1, 1_500)) // 1.5 rounds half up
    }

    @Test
    fun pcmIsClipsAndPausesWithTheLeadInFirst() {
        val segs = listOf(s("dist.m.300"), s("instr.turn.right"))
        val a = ShortArray(3) { 1 }
        val b = ShortArray(2) { 2 }
        val pcm = PromptAssembler.assemble(listOf(a, b), segs, pack, sampleRateHz = 1_000, leadInFrames = 4)
        val expected = ShortArray(4) + a + ShortArray(80) + b // 80 ms at 1 kHz = 80 frames
        assertArrayEquals(expected, pcm)
    }

    @Test
    fun durationIsTheSumOfClipsAndPauses() {
        val segs = listOf(s("dist.m.200"), s("instr.roundabout.exit.2", Take.NON_FINAL), s("join.then"), s("instr.turn.slightRight"))
        val clips = segs.sumOf { pack.entry(it)!!.durationMs }
        assertEquals(clips + 80 + 120 + 120, PromptAssembler.durationMs(segs, pack))
        assertEquals(pack.entry(s("fixed.offRoute"))!!.durationMs, PromptAssembler.durationMs(listOf(s("fixed.offRoute")), pack))
        assertNull(PromptAssembler.durationMs(listOf(s("dist.km.3")), pack))
    }
}
