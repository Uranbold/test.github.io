package mn.navmn.app.voiceplan

import mn.navmn.app.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** navigation-ux §4.5 (AC 34 3 s drop, AC 55 arrival last, AC 37/19 stop). */
class PlaybackQueueTest {
    private class RecSpeaker : Speaker {
        val played = ArrayList<String>()
        var stops = 0
        override fun play(prompt: SpokenPrompt) { played += prompt.text }
        override fun stop() { stops++ }
    }

    private var id = 0L
    private fun p(text: String, at: Long, cls: PromptClass = PromptClass.MANEUVER) = SpokenPrompt(++id, text, Lang.MN, cls, null, at)

    @Test
    fun oneAtATimeAndWaitingDroppedAfterThreeSeconds() {
        val s = RecSpeaker()
        val q = PlaybackQueue(s)
        val a = p("a", 0)
        q.enqueue(a, 0)
        q.enqueue(p("b", 100), 100)
        q.tick(3_200)
        assertNull("b dropped: could not start within 3 s", q.waiting)
        q.onDone(a.id, 3_300)
        assertEquals(listOf("a"), s.played)
    }

    @Test
    fun waitingStartsWhenTheCurrentEndsInTime() {
        val s = RecSpeaker()
        val q = PlaybackQueue(s)
        val a = p("a", 0)
        q.enqueue(a, 0)
        q.enqueue(p("b", 500), 500)
        q.enqueue(p("c", 900), 900) // newer replaces the waiting one
        q.onDone(a.id, 2_000)
        assertEquals(listOf("a", "c"), s.played)
    }

    @Test
    fun arrivalWaitsAtMostThreeSecondsThenNothingElse() {
        val s = RecSpeaker()
        val q = PlaybackQueue(s)
        q.enqueue(p("long", 0), 0)
        q.enqueue(p("arrive", 1_000, PromptClass.ARRIVAL), 1_000)
        q.enqueue(p("late", 1_500), 1_500)
        q.tick(3_900)
        assertEquals(listOf("long"), s.played)
        q.tick(4_000)
        assertEquals(listOf("long", "arrive"), s.played)
        assertEquals(1, s.stops)
        q.onDone(q.current!!.id, 5_000)
        assertTrue(q.closed)
        q.enqueue(p("after", 6_000), 6_000)
        assertEquals(listOf("long", "arrive"), s.played)
    }

    @Test
    fun clearStopsTheCurrentAndDropsTheWaiting() {
        val s = RecSpeaker()
        val q = PlaybackQueue(s)
        q.enqueue(p("a", 0), 0)
        q.enqueue(p("b", 10), 10)
        q.clear()
        assertEquals(1, s.stops)
        assertNull(q.current)
        assertNull(q.waiting)
    }
}
